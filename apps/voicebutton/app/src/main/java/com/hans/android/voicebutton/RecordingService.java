package com.hans.android.voicebutton;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.reliable.JournaledMp3Recorder;
import com.hans.android.audio.reliable.Mp3Converter;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.network.reliable.ReliableUploader;
import com.hans.android.network.reliable.ReliableUploadClient;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecordingService extends Service {
    public static final String ACTION_START = "com.hans.android.voicebutton.START";
    public static final String ACTION_STOP = "com.hans.android.voicebutton.STOP";
    public static final String ACTION_PAUSE = "com.hans.android.voicebutton.PAUSE";
    public static final String ACTION_FINISH = "com.hans.android.voicebutton.FINISH";
    public static final String ACTION_RESUME = "com.hans.android.voicebutton.RESUME";
    public static final String ACTION_FINISH_AND_START = "com.hans.android.voicebutton.FINISH_AND_START";
    public static final String ACTION_RETRY = "com.hans.android.voicebutton.RETRY";
    public static final String ACTION_DELETE_LOCAL = "com.hans.android.voicebutton.DELETE_LOCAL";
    public static final String ACTION_EXIT = "com.hans.android.voicebutton.EXIT";
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_FOLDER_ID = "folder_id";
    public static final String EXTRA_FOLDER_NAME = "folder_name";

    private static final String CHANNEL_ID = "reliable_voice_capture";
    private static final int NOTIFICATION_ID = 4101;
    private static final int STOP_NONE = 0;
    private static final int STOP_PAUSE = 1;
    private static final int STOP_FINISH = 2;
    private static final int STOP_INTERRUPT = 3;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 5L * 60L * 1000L;

    public interface StatusListener { void onStatus(Snapshot snapshot); }

    public static final class Snapshot {
        public final String state;
        public final String explanation;
        public final boolean recording;
        public final boolean paused;
        public final long durationMs;
        public final long localBytes;
        public final float inputRmsDbfs;
        public final float inputPeakDbfs;
        public final int inputLevelPermille;
        public final boolean inputSignalDetected;
        public final long uploadTotalBytes;
        public final long uploadDurableBytes;
        public final long uploadPendingBytes;
        public final int uploadTotalChunks;
        public final int uploadDurableChunks;
        public final int uploadProgressPermille;
        public final String selectedInput;
        public final String routedInput;
        public final List<ReliableSessionManifest> sessions;
        public final ReliableSessionManifest interrupted;
        public final ReliableSessionManifest openSession;
        public final String currentSessionId;

        Snapshot(String state, String explanation, boolean recording, boolean paused, long durationMs,
                 long localBytes, float inputRmsDbfs, float inputPeakDbfs,
                 int inputLevelPermille, boolean inputSignalDetected,
                 long uploadTotalBytes, long uploadDurableBytes, long uploadPendingBytes,
                 int uploadTotalChunks, int uploadDurableChunks, int uploadProgressPermille,
                 String selectedInput, String routedInput,
                 List<ReliableSessionManifest> sessions, ReliableSessionManifest interrupted,
                 ReliableSessionManifest openSession, String currentSessionId) {
            this.state = state;
            this.explanation = explanation;
            this.recording = recording;
            this.paused = paused;
            this.durationMs = durationMs;
            this.localBytes = localBytes;
            this.inputRmsDbfs = inputRmsDbfs;
            this.inputPeakDbfs = inputPeakDbfs;
            this.inputLevelPermille = inputLevelPermille;
            this.inputSignalDetected = inputSignalDetected;
            this.uploadTotalBytes = uploadTotalBytes;
            this.uploadDurableBytes = uploadDurableBytes;
            this.uploadPendingBytes = uploadPendingBytes;
            this.uploadTotalChunks = uploadTotalChunks;
            this.uploadDurableChunks = uploadDurableChunks;
            this.uploadProgressPermille = uploadProgressPermille;
            this.selectedInput = selectedInput;
            this.routedInput = routedInput;
            this.sessions = sessions;
            this.interrupted = interrupted;
            this.openSession = openSession;
            this.currentSessionId = currentSessionId;
        }

        static Snapshot initial() {
            return new Snapshot("READY", "Choose an input and start recording", false, false, 0L, 0L,
                    -120f, -120f, 0, false,
                    0L, 0L, 0L, 0, 0, 0,
                    "No microphone selected", "Not recording", Collections.emptyList(), null, null, null);
        }
    }

    public final class LocalBinder extends Binder { public RecordingService getService() { return RecordingService.this; } }

    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArraySet<StatusListener> listeners = new CopyOnWriteArraySet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ExecutorService conversion = Executors.newSingleThreadExecutor();
    private final Object fileMaintenanceLock = new Object();
    private final JournaledMp3Recorder recorder = new JournaledMp3Recorder();
    private final Mp3Converter mp3 = new Mp3Converter();

    private ReliableSessionStore store;
    private ReliableUploader uploader;
    private PhoneDiagnostics diagnostics;
    private volatile Snapshot snapshot = Snapshot.initial();
    private volatile String currentSessionId;
    private volatile boolean foreground;
    private volatile boolean captureFailed;
    private volatile int stopDisposition = STOP_NONE;
    private volatile long recordingStartedAt;
    private volatile long recordingBaseDurationMs;
    private volatile long lastHeartbeatElapsedMs;
    private volatile String lastLoggedState = "";
    private volatile String lastLoggedExplanation = "";
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private volatile Thread maintenanceThread;
    private PowerManager.WakeLock captureWakeLock;
    private volatile long wakeLockAcquiredElapsedMs;
    private volatile float liveInputRmsDbfs = -120f;
    private volatile float liveInputPeakDbfs = -120f;
    private volatile long liveInputLevelAtElapsedMs;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (snapshot.recording) {
                refresh(snapshot.state, snapshot.explanation, true, snapshot.routedInput);
                long now = SystemClock.elapsedRealtime();
                if (now - wakeLockAcquiredElapsedMs >= WAKE_LOCK_RENEW_MS) {
                    renewCaptureWakeLock();
                }
                if (now - lastHeartbeatElapsedMs >= 5000L) {
                    lastHeartbeatElapsedMs = now;
                    ReliableSessionManifest open = snapshot.openSession;
                    diag(PhoneDiagnostics.DEBUG, "recording.heartbeat", snapshot.currentSessionId,
                            "Recording heartbeat",
                            PhoneDiagnostics.fields("duration_ms", snapshot.durationMs,
                                    "local_bytes", snapshot.localBytes,
                                    "segment_count", open == null ? 0 : open.segments.size(),
                                    "routed_input", snapshot.routedInput));
                }
                main.postDelayed(this, 200L);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        diagnostics = PhoneDiagnostics.initialize(this,
                BuildConfig.VOICE_BASE_URL, BuildConfig.VERSION_NAME);
        diag(PhoneDiagnostics.INFO, "service.create", null,
                "RecordingService onCreate entered", PhoneDiagnostics.fields());
        createNotificationChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            captureWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "VoiceButton:ReliableCapture");
            captureWakeLock.setReferenceCounted(false);
        }
        try {
            store = new ReliableSessionStore(this);
            recoverPcmJournals();
            normalizeInterruptedSessions();
            uploader = new ReliableUploader(this, store, BuildConfig.VOICE_BASE_URL, uploaderListener);
            uploader.start();
            resumePendingConversions();
            ReliableSessionManifest open = store.latestUnfinished();
            ReliableSessionManifest interrupted = store.latestInterrupted();
            diag(PhoneDiagnostics.INFO, "service.recovery_scan", open == null ? null : open.sessionId,
                    "Private recording storage recovery scan completed",
                    PhoneDiagnostics.fields("session_count", store.list().size(),
                            "open_session", open == null ? "" : open.sessionId,
                            "open_state", open == null ? "" : open.state,
                            "open_paused", open != null && open.paused,
                            "open_finished", open != null && open.recordingFinished,
                            "open_segments", open == null ? 0 : open.segments.size(),
                            "interrupted_session", interrupted == null ? "" : interrupted.sessionId,
                            "local_bytes", store.localBytes()));
            currentSessionId = open == null ? null : open.sessionId;
            if (interrupted != null && interrupted.autoResumeRequested) {
                try {
                    AudioInputOption input = resolveInput(interrupted.selectedDeviceId);
                    store.markResumed(interrupted.sessionId, input.getLabel(), input.getDeviceId());
                    ensureForeground();
                    startCapture(interrupted.sessionId, input);
                    diag(PhoneDiagnostics.WARN, "recovery.capture_auto_resumed",
                            interrupted.sessionId,
                            "Recording automatically resumed after an unexpected Android process restart",
                            PhoneDiagnostics.fields("device_id", input.getDeviceId(),
                                    "folder_id", interrupted.folderId));
                } catch (Exception resumeFailure) {
                    store.markInterrupted(interrupted.sessionId,
                            PhoneDiagnostics.exactFailure("Automatically resuming recording", resumeFailure));
                    refresh("RECOVERY REQUIRED",
                            PhoneDiagnostics.exactFailure("Automatically resuming recording", resumeFailure)
                                    + ". Durable chunks remain safe; choose Resume after restoring the microphone.",
                            false, "Not recording");
                }
            } else if (interrupted != null) {
                refresh("RECOVERY REQUIRED", "An interrupted recording needs your decision", false, "Not recording");
            } else if (open != null && open.paused) {
                refresh("PAUSED", "The current recording is safely paused and available for playback", false, "Not recording");
            } else {
                refresh("READY", "Ready to create a durable compressed recording", false, "Not recording");
            }
        } catch (Exception failure) {
            String exact = PhoneDiagnostics.exactFailure("Opening private recording storage", failure);
            diagError("service.create_failed", null, "Opening private recording storage", failure,
                    PhoneDiagnostics.fields());
            snapshot = new Snapshot("FAILED", exact, false, false,
                    0L, 0L, -120f, -120f, 0, false,
                    0L, 0L, 0L, 0, 0, 0,
                    "No microphone selected", "Not recording", Collections.emptyList(), null, null, null);
        }
        publish();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        int deviceId = intent == null ? AudioInputOption.DEFAULT_DEVICE_ID
                : intent.getIntExtra(EXTRA_DEVICE_ID, AudioInputOption.DEFAULT_DEVICE_ID);
        String sessionId = intent == null ? null : intent.getStringExtra(EXTRA_SESSION_ID);
        String folderId = intent == null ? "default" : intent.getStringExtra(EXTRA_FOLDER_ID);
        String folderName = intent == null ? "Default" : intent.getStringExtra(EXTRA_FOLDER_NAME);
        if (folderId == null || folderId.isEmpty()) folderId = "default";
        if (folderName == null || folderName.isEmpty()) folderName = "Default";
        diag(PhoneDiagnostics.INFO, "service.action_received", sessionId,
                "RecordingService received an action",
                PhoneDiagnostics.fields("action", action == null ? "null" : action,
                        "device_id", deviceId,
                        "start_id", startId,
                        "flags", flags,
                        "recording", recorder.isRecording(),
                        "snapshot_state", snapshot.state));
        boolean foregroundLaunch = ACTION_START.equals(action)
                || ACTION_RESUME.equals(action)
                || ACTION_FINISH_AND_START.equals(action);
        if (foregroundLaunch) ensureForeground();
        try {
            if (ACTION_START.equals(action)) startNew(deviceId, folderId, folderName);
            else if (ACTION_RESUME.equals(action)) resumeSession(sessionId, deviceId);
            else if (ACTION_FINISH_AND_START.equals(action)) finishInterruptedAndStart(sessionId, deviceId, folderId, folderName);
            else if (ACTION_PAUSE.equals(action)) pauseRecording();
            else if (ACTION_FINISH.equals(action) || ACTION_STOP.equals(action)) finishRecording(sessionId);
            else if (ACTION_RETRY.equals(action)) { uploader.signal(); refresh("RECONCILING", "Checking durable server segments", false, snapshot.routedInput); }
            else if (ACTION_DELETE_LOCAL.equals(action)) deleteLocalFiles();
            else if (ACTION_EXIT.equals(action)) shutdownForUserExit("explicit_close");
        } catch (Exception failure) {
            String operation = "Action " + (action == null ? "null" : action);
            String exact = PhoneDiagnostics.exactFailure(operation, failure)
                    + ". Local audio and metadata were preserved.";
            diagError("service.action_failed", sessionId, operation, failure,
                    PhoneDiagnostics.fields("action", action == null ? "null" : action,
                            "device_id", deviceId,
                            "snapshot_state", snapshot.state));
            refresh("FAILED", exact, snapshot.recording, snapshot.routedInput);
            if (!recorder.isRecording()) leaveForegroundIfIdle();
        }
        return recorder.isRecording() ? START_STICKY : START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public void addStatusListener(StatusListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onStatus(snapshot);
    }

    public void removeStatusListener(StatusListener listener) { if (listener != null) listeners.remove(listener); }
    public Snapshot getSnapshot() { return snapshot; }

    public List<ReliableSessionStore.Folder> listFolders() {
        return store == null ? Collections.singletonList(
                new ReliableSessionStore.Folder("default", "Default", 0L)) : store.listFolders();
    }

    public ReliableSessionStore.Folder createFolder(String name) throws IOException {
        if (store == null) throw new IOException("Recording storage is not ready");
        ReliableSessionStore.Folder folder = store.createFolder(name);
        new Thread(() -> {
            try {
                new ReliableUploadClient(BuildConfig.VOICE_BASE_URL,
                        "VoiceButton/" + BuildConfig.VERSION_NAME + " Android")
                        .createFolder(folder.id, folder.name);
                diag(PhoneDiagnostics.INFO, "folder.remote_created", null,
                        "Recording folder was created on the server",
                        PhoneDiagnostics.fields("folder_id", folder.id, "folder_name", folder.name));
            } catch (Exception failure) {
                diagError("folder.remote_create_queued", null,
                        "Creating recording folder on the server", failure,
                        PhoneDiagnostics.fields("folder_id", folder.id, "folder_name", folder.name));
            }
        }, "voicebutton-folder-sync").start();
        refresh(snapshot.state, snapshot.explanation, snapshot.recording, snapshot.routedInput);
        return folder;
    }

    private void startNew(int deviceId, String folderId, String folderName) throws Exception {
        diag(PhoneDiagnostics.INFO, "recording.start_requested", null,
                "A new recording was requested", PhoneDiagnostics.fields("device_id", deviceId));
        if (recorder.isRecording()) throw new IOException("A recording is already active");
        ReliableSessionManifest unfinished = store.latestUnfinished();
        if (unfinished != null) {
            currentSessionId = unfinished.sessionId;
            String state = unfinished.paused ? "PAUSED" : "RECOVERY REQUIRED";
            String detail = unfinished.paused
                    ? "Resume or finish the paused recording before starting another"
                    : "Choose whether to continue or close the interrupted recording";
            refresh(state, detail, false, "Not recording");
            return;
        }
        AudioInputOption input = resolveInput(deviceId);
        ReliableSessionManifest manifest = store.createSession(input.getLabel(), input.getDeviceId(),
                folderId, folderName);
        diag(PhoneDiagnostics.INFO, "recording.session_created", manifest.sessionId,
                "New recording metadata was created",
                PhoneDiagnostics.fields("device_id", input.getDeviceId(),
                        "device_type", input.getDeviceType(), "label", input.getLabel()));
        startCapture(manifest.sessionId, input);
    }

    private void resumeSession(String sessionId, int deviceId) throws Exception {
        diag(PhoneDiagnostics.INFO, "recording.resume_requested", sessionId,
                "Resume was requested", PhoneDiagnostics.fields("device_id", deviceId));
        if (recorder.isRecording()) {
            refresh("RECORDING", "Recording is already active", true, snapshot.routedInput);
            return;
        }
        if (sessionId == null || sessionId.isEmpty()) {
            ReliableSessionManifest latest = store.latestUnfinished();
            if (latest == null) throw new IOException("There is no paused recording to resume");
            sessionId = latest.sessionId;
        }
        ReliableSessionManifest manifest = store.load(sessionId);
        if (manifest.recordingFinished) throw new IOException("That recording is already closed");
        AudioInputOption input = resolveInput(deviceId);
        store.markResumed(sessionId, input.getLabel(), input.getDeviceId());
        startCapture(sessionId, input);
    }

    private void finishInterruptedAndStart(String sessionId, int deviceId,
                                           String folderId, String folderName) throws Exception {
        if (recorder.isRecording()) throw new IOException("A recording is already active");
        long started = SystemClock.elapsedRealtime();
        ReliableSessionManifest old = store.load(sessionId);
        if (!old.recordingFinished) store.markRecordingFinished(sessionId, "interrupted_start_new");
        currentSessionId = null;
        scheduleFinalization(sessionId);
        diag(PhoneDiagnostics.INFO, "recovery.old_recording_closed", sessionId,
                "Interrupted recording was closed before starting a new recording",
                PhoneDiagnostics.fields("operation_duration_ms",
                        Math.max(0L, SystemClock.elapsedRealtime() - started),
                        "segment_count", old.segments.size(),
                        "duration_ms", old.totalDurationMs));
        AudioInputOption input;
        try {
            input = resolveInput(deviceId);
        } catch (Exception failure) {
            throw new IOException("The old recording was closed and is being finalized, but the new recording could not start: "
                    + failure.getMessage(), failure);
        }
        ReliableSessionManifest next = store.createSession(input.getLabel(), input.getDeviceId(),
                folderId, folderName);
        diag(PhoneDiagnostics.INFO, "recording.session_created", next.sessionId,
                "New recording metadata was created after recovery",
                PhoneDiagnostics.fields("previous_session_id", sessionId,
                        "device_id", input.getDeviceId(), "device_type", input.getDeviceType(),
                        "label", input.getLabel()));
        startCapture(next.sessionId, input);
    }

    private void startCapture(String sessionId, AudioInputOption input) {
        ensureForeground();
        acquireCaptureWakeLock();
        currentSessionId = sessionId;
        captureFailed = false;
        liveInputRmsDbfs = -120f;
        liveInputPeakDbfs = -120f;
        liveInputLevelAtElapsedMs = 0L;
        stopDisposition = STOP_NONE;
        try { recordingBaseDurationMs = store.load(sessionId).totalDurationMs; }
        catch (Exception ignored) { recordingBaseDurationMs = 0L; }
        recordingStartedAt = SystemClock.elapsedRealtime();
        lastHeartbeatElapsedMs = recordingStartedAt;
        diag(PhoneDiagnostics.INFO, "recording.capture_starting", sessionId,
                "Microphone capture thread is starting",
                PhoneDiagnostics.fields("device_id", input.getDeviceId(),
                        "device_type", input.getDeviceType(), "label", input.getLabel(),
                        "base_duration_ms", recordingBaseDurationMs));
        refresh("PREPARING", "Opening " + input.getLabel(), true, "Opening microphone");
        if (!recorder.start(this, input, store, sessionId, recorderListener)) {
            boolean removed = false;
            try { removed = store.discardIfEmpty(sessionId); }
            catch (Exception ignored) {}
            if (!removed) {
                try { store.markInterrupted(sessionId,
                        "Microphone capture could not restart because another recorder thread was active"); }
                catch (Exception ignored) {}
            }
            currentSessionId = removed ? null : sessionId;
            String exact = "Starting microphone capture failed: IllegalStateException: a recording thread was already active. "
                    + (removed ? "The empty recording was removed."
                    : "The existing recording was preserved and marked interrupted.");
            diag(PhoneDiagnostics.ERROR, "recording.capture_start_rejected", sessionId,
                    exact, PhoneDiagnostics.fields("device_id", input.getDeviceId(),
                            "empty_session_removed", removed));
            refresh(removed ? "FAILED" : "RECOVERY REQUIRED", exact, false, "Not recording");
            releaseCaptureWakeLock();
        }
    }

    private void pauseRecording() {
        diag(PhoneDiagnostics.INFO, "recording.pause_requested", currentSessionId,
                "Pause was requested", PhoneDiagnostics.fields("duration_ms", snapshot.durationMs));
        if (!recorder.isRecording()) {
            ReliableSessionManifest open = store.latestUnfinished();
            if (open != null && open.paused) {
                refresh("PAUSED", "Recording is already paused and ready to resume", false, "Not recording");
                return;
            }
            if (stopDisposition == STOP_PAUSE) {
                refresh("PAUSING", "Pause is already closing the current MP3 segment", false, "Not recording");
                return;
            }
            throw new IllegalStateException("No recording is currently active to pause");
        }
        stopDisposition = STOP_PAUSE;
        refresh("PAUSING", "Closing and synchronizing the current MP3 segment", true, snapshot.routedInput);
        recorder.stop();
    }

    private void finishRecording(String requestedSessionId) throws Exception {
        diag(PhoneDiagnostics.INFO, "recording.finish_requested", requestedSessionId,
                "Finish was requested", PhoneDiagnostics.fields("recording", recorder.isRecording(),
                        "snapshot_state", snapshot.state));
        if (recorder.isRecording()) {
            stopDisposition = STOP_FINISH;
            refresh("FINISHING", "Closing the final MP3 segment", true, snapshot.routedInput);
            recorder.stop();
            return;
        }
        String sessionId = requestedSessionId;
        if (sessionId == null || sessionId.isEmpty()) sessionId = currentSessionId;
        if (sessionId == null || sessionId.isEmpty()) {
            ReliableSessionManifest latest = store.latestUnfinished();
            if (latest == null) return;
            sessionId = latest.sessionId;
        }
        ReliableSessionManifest manifest = store.load(sessionId);
        if (manifest.recordingFinished && manifest.conversionFinished) {
            currentSessionId = null;
            refresh("READY", "The recording is already finished and playable", false, "Not recording");
            if (uploader != null) uploader.signal();
            return;
        }
        if (!manifest.recordingFinished) {
            store.markRecordingFinished(sessionId,
                    manifest.paused ? "finished_from_pause" : "finished_after_recovery");
        }
        currentSessionId = null;
        scheduleFinalization(sessionId);
        refresh("FINISHING", "Creating the final playable MP3", false, "Not recording");
        if (uploader != null) uploader.signal();
    }

    private AudioInputOption resolveInput(int deviceId) throws IOException {
        long started = SystemClock.elapsedRealtime();
        List<AudioInputOption> options = AudioInputCatalog.list(this);
        org.json.JSONArray devices = new org.json.JSONArray();
        for (AudioInputOption option : options) {
            org.json.JSONObject item = new org.json.JSONObject();
            try {
                item.put("device_id", option.getDeviceId());
                item.put("device_type", option.getDeviceType());
                item.put("label", option.getLabel());
                item.put("category", option.getCategory().name());
            } catch (Exception ignored) {}
            devices.put(item);
        }
        diag(PhoneDiagnostics.INFO, "microphone.resolve", currentSessionId,
                "Currently enumerated physical microphones were checked",
                PhoneDiagnostics.fields("requested_device_id", deviceId,
                        "available_count", options.size(),
                        "devices", devices,
                        "enumeration_duration_ms", Math.max(0L,
                                SystemClock.elapsedRealtime() - started)));
        if (options.isEmpty()) {
            throw new IOException("No real microphone is currently available; connect one and press Refresh connected inputs");
        }
        for (AudioInputOption option : options) {
            if (option.getDeviceId() == deviceId) return option;
        }
        throw new IOException("The selected microphone is no longer available; press Refresh connected inputs and choose again");
    }

    private final JournaledMp3Recorder.Listener recorderListener = new JournaledMp3Recorder.Listener() {
        @Override public void onStarted(String routedDevice) {
            diag(PhoneDiagnostics.INFO, "recording.started", currentSessionId,
                    "AudioRecord entered the recording state",
                    PhoneDiagnostics.fields("routed_device", routedDevice,
                            "start_duration_ms", Math.max(0L,
                                    SystemClock.elapsedRealtime() - recordingStartedAt)));
            refresh("RECORDING", "Audio is journaled locally and compressed in the background", true, routedDevice);
            main.removeCallbacks(ticker);
            main.post(ticker);
        }


        @Override public void onRecorderEvent(String event, int seq, long bytes,
                                              long durationMs, String detail) {
            String level = event.endsWith("exception") ? PhoneDiagnostics.ERROR : PhoneDiagnostics.DEBUG;
            diag(level, event, currentSessionId, detail,
                    PhoneDiagnostics.fields("seq", seq, "bytes", bytes,
                            "duration_ms", durationMs, "detail", detail));
        }

        @Override public void onAudioLevel(float rmsDbfs, float peakDbfs, long capturedSamples) {
            liveInputRmsDbfs = rmsDbfs;
            liveInputPeakDbfs = peakDbfs;
            liveInputLevelAtElapsedMs = SystemClock.elapsedRealtime();
        }

        @Override public void onSegmentCommitted(int seq, File mp3File, long durationMs) {
            diag(PhoneDiagnostics.INFO, "recording.segment_committed", currentSessionId,
                    "Immutable MP3 segment metadata was committed locally",
                    PhoneDiagnostics.fields("seq", seq, "bytes", mp3File.length(),
                            "duration_ms", durationMs, "file_name", mp3File.getName()));
            if (uploader != null) uploader.signal();
            if (stopDisposition == STOP_PAUSE) {
                refresh("PAUSING", "The paused MP3 segment is durable; preparing playback", false, "Not recording");
            } else if (stopDisposition == STOP_FINISH) {
                refresh("FINISHING", "The final MP3 segment is durable; assembling the recording", false, "Not recording");
            } else {
                refresh("RECORDING", "A compressed MP3 segment was synchronized to storage", true, snapshot.routedInput);
            }
        }

        @Override public void onStopped(String sessionId) {
            main.removeCallbacks(ticker);
            releaseCaptureWakeLock();
            liveInputRmsDbfs = -120f;
            liveInputPeakDbfs = -120f;
            liveInputLevelAtElapsedMs = 0L;
            int disposition = stopDisposition;
            diag(PhoneDiagnostics.INFO, "recording.thread_stopped", sessionId,
                    "Recorder thread reported stopped",
                    PhoneDiagnostics.fields("stop_disposition", disposition,
                            "capture_failed", captureFailed,
                            "snapshot_duration_ms", snapshot.durationMs));
            stopDisposition = STOP_NONE;
            if (exitRequested.get()) {
                try {
                    if (!store.discardIfEmpty(sessionId)) {
                        ReliableSessionManifest current = store.load(sessionId);
                        if (!current.recordingFinished && !current.paused) {
                            store.markInterrupted(sessionId,
                                    "App was explicitly closed before the recording was finished");
                        }
                    }
                } catch (Exception failure) {
                    diagError("recording.exit_recovery_failed", sessionId,
                            "Persisting recording state during app exit", failure,
                            PhoneDiagnostics.fields("stop_disposition", disposition));
                }
                return;
            }
            try {
                if (store.discardIfEmpty(sessionId)) {
                    currentSessionId = null;
                    String detail = captureFailed
                            ? "The microphone did not produce durable audio; the empty recording was removed"
                            : "No audio was captured; no stale recording was kept";
                    refresh(captureFailed ? "FAILED" : "READY", detail, false, "Not recording");
                    return;
                }
            } catch (Exception failure) {
                diagError("recording.empty_session_check_failed", sessionId,
                        "Checking whether the stopped session was empty", failure,
                        PhoneDiagnostics.fields());
            }
            try {
                ReliableSessionManifest persisted = store.load(sessionId);
                if (persisted.recordingFinished) {
                    currentSessionId = null;
                    if (!persisted.conversionFinished) {
                        scheduleFinalization(sessionId);
                        refresh("FINISHING", "Creating the final playable MP3", false, "Not recording");
                    } else {
                        refresh("READY", "The recording is finished and playable", false, "Not recording");
                    }
                    if (uploader != null) uploader.signal();
                    return;
                }
            } catch (Exception failure) {
                diagError("recording.persisted_state_check_failed", sessionId,
                        "Checking persisted recording state after capture stopped", failure,
                        PhoneDiagnostics.fields("stop_disposition", disposition));
            }
            if (captureFailed || disposition == STOP_INTERRUPT || disposition == STOP_NONE) {
                try { store.markInterrupted(sessionId, captureFailed ? "Recording stopped unexpectedly" : "Recording service was interrupted"); }
                catch (Exception ignored) {}
                currentSessionId = sessionId;
                schedulePreview(sessionId);
                refresh("RECOVERY REQUIRED", "Recording stopped unexpectedly; choose whether to continue or finish it", false, "Not recording");
            } else if (disposition == STOP_PAUSE) {
                try { store.markPaused(sessionId); }
                catch (Exception failure) { store.markError(sessionId, "Could not save paused state"); }
                currentSessionId = sessionId;
                schedulePreview(sessionId);
                refresh("PAUSED", "Recording is paused; preparing a playable MP3 snapshot", false, "Not recording");
            } else {
                try { store.markRecordingFinished(sessionId, "normal"); }
                catch (Exception failure) { store.markError(sessionId, "Could not close recording metadata"); }
                currentSessionId = null;
                scheduleFinalization(sessionId);
                refresh("FINISHING", "Recording closed; creating the final MP3", false, "Not recording");
            }
            if (uploader != null) uploader.signal();
        }

        @Override public void onFailure(String stage, String exceptionClass, String message) {
            if (exitRequested.get()) return;
            captureFailed = true;
            String exact = "Recording failed during " + stage + ": " + exceptionClass + ": " + message
                    + ". Any durable MP3 frames and metadata were preserved.";
            if (currentSessionId != null) store.markError(currentSessionId, exact);
            diag(PhoneDiagnostics.ERROR, "recording.failure", currentSessionId,
                    exact, PhoneDiagnostics.fields("stage", stage,
                            "exception_class", exceptionClass,
                            "exception_message", message));
            refresh("FAILED", exact, false, "Not recording");
        }
    };

    private void schedulePreview(String sessionId) {
        if (exitRequested.get()) return;
        conversion.execute(() -> {
            synchronized (fileMaintenanceLock) {
                long operationStarted = SystemClock.elapsedRealtime();
                diag(PhoneDiagnostics.INFO, "recording.preview_start", sessionId,
                        "Playable MP3 snapshot assembly started", PhoneDiagnostics.fields());
                try {
                    ReliableSessionManifest manifest = store.load(sessionId);
                    for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                        if (segment.mp3Name.isEmpty() || !store.mp3File(sessionId, segment).isFile()) {
                            if (!segment.pcmJournalName.isEmpty()) {
                                throw new IOException("A durable PCM chunk still requires recovery before preview assembly");
                            }
                            if (segment.wavName.isEmpty()) {
                                throw new IOException("Audio chunk " + segment.seq + " is missing; partial preview was refused");
                            }
                            File wav = store.wavFile(sessionId, segment);
                            File target = new File(store.sessionDirectory(sessionId), String.format(java.util.Locale.US, "segment_%06d.mp3", segment.seq));
                            mp3.encodeSegment(wav, target);
                            store.markSegmentEncoded(sessionId, segment.seq, target);
                        }
                    }
                    manifest = store.load(sessionId);
                    if (manifest.segments.isEmpty()) return;
                    List<File> compressed = new ArrayList<>();
                    for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                        File file = store.mp3File(sessionId, segment);
                        if (!file.isFile() || file.length() != segment.mp3Bytes) {
                            throw new IOException("Audio chunk " + segment.seq
                                    + " is unavailable; partial preview was refused");
                        }
                        compressed.add(file);
                    }
                    if (compressed.isEmpty()) return;
                    File preview = new File(store.sessionDirectory(sessionId), "recording.mp3");
                    mp3.concatenateMp3Segments(compressed, preview);
                    store.markPreviewReady(sessionId, preview);
                    ReliableSessionManifest updated = store.load(sessionId);
                    diag(PhoneDiagnostics.INFO, "recording.preview_complete", sessionId,
                            "Playable MP3 snapshot assembly completed",
                            PhoneDiagnostics.fields("operation_duration_ms",
                                    Math.max(0L, SystemClock.elapsedRealtime() - operationStarted),
                                    "bytes", preview.length(),
                                    "segment_count", compressed.size(),
                                    "sha256", ReliableSessionStore.sha256File(preview)));
                    if (!recorder.isRecording() && updated.paused) {
                        refreshFromWorker("PAUSED", "Recording is paused and ready to play or resume");
                    } else if (!recorder.isRecording() && updated.isInterrupted()) {
                        refreshFromWorker("RECOVERY REQUIRED", "Recovered audio is ready to play; continue or finish the recording");
                    }
                } catch (Exception failure) {
                    if (exitRequested.get() || failure instanceof java.io.InterruptedIOException
                            || Thread.currentThread().isInterrupted()) return;
                    String exact = PhoneDiagnostics.exactFailure("Preparing playable MP3 snapshot", failure)
                            + ". Durable source segments were preserved.";
                    store.markError(sessionId, exact);
                    diagError("recording.preview_failed", sessionId,
                            "Preparing playable MP3 snapshot", failure,
                            PhoneDiagnostics.fields("operation_duration_ms",
                                    Math.max(0L, SystemClock.elapsedRealtime() - operationStarted)));
                    refreshFromWorker("FAILED", exact);
                }
            }
        });
    }

    private void scheduleSegmentEncoding(String sessionId, int seq) {
        conversion.execute(() -> {
            synchronized (fileMaintenanceLock) {
            try {
                ReliableSessionManifest manifest = store.load(sessionId);
                ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
                if (segment == null || segment.wavName.isEmpty()) return;
                if (!segment.mp3Name.isEmpty() && store.mp3File(sessionId, segment).isFile()) return;
                File wav = store.wavFile(sessionId, segment);
                File target = new File(store.sessionDirectory(sessionId), String.format(java.util.Locale.US, "segment_%06d.mp3", seq));
                mp3.encodeSegment(wav, target);
                store.markSegmentEncoded(sessionId, seq, target);
                if (uploader != null) uploader.signal();
                refreshFromWorker("COMPRESSING", "Compressed segment " + (seq + 1) + " is ready for reconciliation");
            } catch (Exception failure) {
                if (exitRequested.get() || failure instanceof java.io.InterruptedIOException
                        || Thread.currentThread().isInterrupted()) return;
                String exact = PhoneDiagnostics.exactFailure("Legacy WAV to MP3 conversion", failure)
                        + ". The original WAV source was preserved.";
                store.markError(sessionId, exact);
                refreshFromWorker("FAILED", exact);
            }
            }
        });
    }

    private void scheduleFinalization(String sessionId) {
        if (!exitRequested.get()) ensureForeground();
        conversion.execute(() -> {
            synchronized (fileMaintenanceLock) {
            long operationStarted = SystemClock.elapsedRealtime();
            diag(PhoneDiagnostics.INFO, "recording.finalization_start", sessionId,
                    "Final MP3 assembly started", PhoneDiagnostics.fields());
            try {
                ReliableSessionManifest manifest = store.load(sessionId);
                File existingFinal = store.finalMp3File(sessionId);
                if (manifest.conversionFinished && existingFinal.isFile() && existingFinal.length() > 0L) {
                    refreshFromWorker("READY", "The recording is already finished and playable");
                    if (uploader != null) uploader.signal();
                    return;
                }
                for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                    if (segment.mp3Name.isEmpty() || !store.mp3File(sessionId, segment).isFile()) {
                        if (segment.wavName.isEmpty()) throw new IOException("A recoverable audio segment is missing");
                        File wav = store.wavFile(sessionId, segment);
                        File target = new File(store.sessionDirectory(sessionId), String.format(java.util.Locale.US, "segment_%06d.mp3", segment.seq));
                        mp3.encodeSegment(wav, target);
                        store.markSegmentEncoded(sessionId, segment.seq, target);
                    }
                }
                manifest = store.load(sessionId);
                if (manifest.segments.isEmpty()) throw new IOException("No audio was captured");
                List<File> compressed = new ArrayList<>();
                for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) compressed.add(store.mp3File(sessionId, segment));
                File finalMp3 = new File(store.sessionDirectory(sessionId), "recording.mp3");
                mp3.concatenateMp3Segments(compressed, finalMp3);
                store.markConversionFinished(sessionId, finalMp3);
                diag(PhoneDiagnostics.INFO, "recording.finalization_complete", sessionId,
                        "Final MP3 assembly completed",
                        PhoneDiagnostics.fields("operation_duration_ms",
                                Math.max(0L, SystemClock.elapsedRealtime() - operationStarted),
                                "bytes", finalMp3.length(),
                                "segment_count", compressed.size(),
                                "sha256", ReliableSessionStore.sha256File(finalMp3)));
                if (uploader != null) uploader.signal();
                refreshFromWorker("READY", "The local MP3 is complete; server reconciliation continues");
            } catch (Exception failure) {
                if (exitRequested.get() || failure instanceof java.io.InterruptedIOException
                        || Thread.currentThread().isInterrupted()) return;
                String exact = PhoneDiagnostics.exactFailure("Final MP3 assembly", failure)
                        + ". Recoverable source segments remain on the phone.";
                store.markError(sessionId, exact);
                diagError("recording.finalization_failed", sessionId,
                        "Final MP3 assembly", failure,
                        PhoneDiagnostics.fields("operation_duration_ms",
                                Math.max(0L, SystemClock.elapsedRealtime() - operationStarted)));
                refreshFromWorker("FAILED", exact);
            }
            }
        });
    }

    private void recoverPcmJournals() {
        for (ReliableSessionManifest manifest : store.list()) {
            long cursor = 0L;
            for (ReliableSessionManifest.Segment existing : manifest.orderedSegments()) {
                if (existing.pcmJournalName.isEmpty()) cursor = Math.max(cursor, existing.endSample);
                else {
                    try {
                        File pcm = store.pcmJournalFile(manifest.sessionId, existing);
                        if (!pcm.isFile() || pcm.length() < 2L) continue;
                        if (store.clearVerifiedPcmJournal(manifest.sessionId, existing.seq)) {
                            cursor = Math.max(cursor, existing.endSample);
                            diag(PhoneDiagnostics.INFO, "recovery.pcm_journal_already_committed",
                                    manifest.sessionId,
                                    "A leftover PCM journal was removed after verifying the already-committed MP3 chunk",
                                    PhoneDiagnostics.fields("seq", existing.seq,
                                            "mp3_bytes", existing.mp3Bytes,
                                            "remote_accepted", existing.remoteAccepted));
                            continue;
                        }
                        File target = new File(store.sessionDirectory(manifest.sessionId),
                                String.format(java.util.Locale.US, "segment_%06d.mp3", existing.seq));
                        mp3.encodeRawPcm(pcm, existing.pcmInputSampleRate, target);
                        long inputSamples = pcm.length() / 2L;
                        long outputSamples = inputSamples * ReliableSessionManifest.OUTPUT_SAMPLE_RATE
                                / Math.max(1, existing.pcmInputSampleRate);
                        long end = cursor + outputSamples;
                        long duration = outputSamples * 1000L / ReliableSessionManifest.OUTPUT_SAMPLE_RATE;
                        store.markPcmJournalEncoded(manifest.sessionId, existing.seq, target,
                                duration, cursor, end);
                        diag(PhoneDiagnostics.WARN, "recovery.pcm_chunk_rebuilt",
                                manifest.sessionId, "A crash-surviving PCM journal was rebuilt as an MP3 chunk",
                                PhoneDiagnostics.fields("seq", existing.seq,
                                        "input_sample_rate", existing.pcmInputSampleRate,
                                        "pcm_bytes", pcm.length(), "output_samples", outputSamples));
                        cursor = end;
                    } catch (Exception failure) {
                        store.markError(manifest.sessionId,
                                PhoneDiagnostics.exactFailure("Recovering durable PCM chunk", failure));
                        diagError("recovery.pcm_chunk_failed", manifest.sessionId,
                                "Recovering durable PCM chunk", failure,
                                PhoneDiagnostics.fields("seq", existing.seq,
                                        "journal", existing.pcmJournalName));
                    }
                }
            }
        }
    }

    private void resumePendingConversions() {
        for (ReliableSessionManifest manifest : store.list()) {
            if (manifest.isDiscardableEmptySession()) {
                try {
                    if (store.discardIfEmpty(manifest.sessionId)) {
                        diag(PhoneDiagnostics.INFO, "recovery.empty_session_removed", manifest.sessionId,
                                "A legacy zero-audio session was removed during recovery",
                                PhoneDiagnostics.fields("state", manifest.state,
                                        "recording_finished", manifest.recordingFinished,
                                        "conversion_finished", manifest.conversionFinished));
                    }
                } catch (Exception failure) {
                    diagError("recovery.empty_session_remove_failed", manifest.sessionId,
                            "Removing legacy zero-audio session", failure, PhoneDiagnostics.fields());
                }
                continue;
            }
            for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                if (!segment.wavName.isEmpty() && segment.mp3Name.isEmpty()) scheduleSegmentEncoding(manifest.sessionId, segment.seq);
            }
            if (manifest.recordingFinished && !manifest.conversionFinished) scheduleFinalization(manifest.sessionId);
            else if (!manifest.recordingFinished && !manifest.autoResumeRequested) {
                try {
                    File preview = store.finalMp3File(manifest.sessionId);
                    if (!preview.isFile()) schedulePreview(manifest.sessionId);
                } catch (Exception failure) {
                    diagError("recovery.preview_check_failed", manifest.sessionId,
                            "Checking recovered playable preview", failure,
                            PhoneDiagnostics.fields());
                }
            }
        }
    }

    private void normalizeInterruptedSessions() {
        List<ReliableSessionManifest> interrupted = new ArrayList<>();
        for (ReliableSessionManifest manifest : store.list()) if (!manifest.recordingFinished) interrupted.add(manifest);
        interrupted.sort(Comparator.comparingLong(value -> value.createdAt));
        for (int i = 0; i + 1 < interrupted.size(); i++) {
            try {
                store.markRecordingFinished(interrupted.get(i).sessionId, "older_interrupted_session");
            } catch (Exception ignored) {}
        }
    }

    private final ReliableUploader.Listener uploaderListener = new ReliableUploader.Listener() {
        @Override public void onState(String sessionId, String humanState) {
            diag(PhoneDiagnostics.INFO, "upload.state", sessionId,
                    humanState, PhoneDiagnostics.fields("snapshot_state", snapshot.state));
            String requested = humanState.startsWith("Stored completely") ? "READY" : "SYNCHRONIZING";
            refreshFromWorker(requested, humanState);
        }
        @Override public void onChanged() {
            main.post(() -> refresh(snapshot.state, snapshot.explanation,
                    snapshot.recording, snapshot.routedInput));
        }

        @Override public void onDiagnostic(String level, String event, String sessionId,
                                           String message, org.json.JSONObject fields,
                                           Throwable failure) {
            if (failure == null) diag(level, event, sessionId, message, fields);
            else diagError(event, sessionId, message, failure, fields);
        }
    };

    private void deleteLocalFiles() throws Exception {
        if (recorder.isRecording()) throw new IOException("Stop recording before deleting local files");
        ensureForeground();
        refresh("CLEANING", "Deleting all local recording files, diagnostics, and cache", false, "Not recording");
        ReliableUploader oldUploader = uploader;
        if (oldUploader != null) oldUploader.stop();
        ExecutorService oldExecutor = conversion;
        oldExecutor.shutdownNow();
        PhoneDiagnostics oldDiagnostics = diagnostics;
        if (oldDiagnostics != null) {
            oldDiagnostics.log(PhoneDiagnostics.WARN, "cleanup.diagnostics_shutdown", null,
                    "Diagnostic journal is stopping for complete local cleanup",
                    PhoneDiagnostics.fields("local_recording_bytes", store.localBytes()));
            oldDiagnostics.shutdownForAppExit();
            diagnostics = null;
        }
        Thread cleanup = new Thread(() -> {
            if (Thread.currentThread().isInterrupted() || exitRequested.get()) return;
            boolean success = false;
            String failureText = "";
            try {
                if (oldUploader != null && !oldUploader.awaitStopped(2000L)) {
                    throw new InterruptedIOException("Server transfer did not stop within two seconds");
                }
                if (!oldExecutor.awaitTermination(2000L, TimeUnit.MILLISECONDS)) {
                    throw new InterruptedIOException("MP3 worker did not stop within two seconds");
                }
                synchronized (fileMaintenanceLock) {
                    checkMaintenanceInterrupted();
                    deleteTree(store.getRoot());
                    deleteTree(new File(getNoBackupFilesDir(), "voice_spool"));
                    deleteTree(new File(getNoBackupFilesDir(), "voice_chat"));
                    deleteTree(new File(getNoBackupFilesDir(), "phone_diagnostics"));
                    deleteTree(getCacheDir());
                    File externalCache = getExternalCacheDir();
                    if (externalCache != null) deleteTree(externalCache);
                    checkMaintenanceInterrupted();
                    store = new ReliableSessionStore(this);
                    conversion = Executors.newSingleThreadExecutor();
                    uploader = new ReliableUploader(this, store, BuildConfig.VOICE_BASE_URL, uploaderListener);
                    uploader.start();
                    success = true;
                }
            } catch (Exception failure) {
                failureText = PhoneDiagnostics.exactFailure("Deleting all local files", failure)
                        + ". Files not yet deleted were preserved where possible.";
            } finally {
                if (exitRequested.get()) return;
                if (!success) {
                    try {
                        store = new ReliableSessionStore(this);
                        conversion = Executors.newSingleThreadExecutor();
                        uploader = new ReliableUploader(this, store, BuildConfig.VOICE_BASE_URL, uploaderListener);
                        uploader.start();
                    } catch (Exception recoveryFailure) {
                        failureText = (failureText.isEmpty() ? "" : failureText + " ")
                                + PhoneDiagnostics.exactFailure("Reopening app storage after cleanup failure", recoveryFailure);
                    }
                }
                diagnostics = PhoneDiagnostics.initialize(this,
                        BuildConfig.VOICE_BASE_URL, BuildConfig.VERSION_NAME);
                if (success) {
                    refreshFromWorker("READY", "All local recording files, diagnostics, and cache were deleted");
                } else {
                    refreshFromWorker("FAILED", failureText.isEmpty()
                            ? "Local cleanup stopped before completion; remaining files were preserved"
                            : failureText);
                }
            }
        }, "reliable-audio-cleanup");
        maintenanceThread = cleanup;
        cleanup.start();
    }

    private boolean hasBackgroundWork() {
        if (store == null) return false;
        for (ReliableSessionManifest manifest : store.list()) if (!manifest.isDone()) return true;
        return false;
    }

    private void refreshFromWorker(String state, String explanation) {
        main.post(() -> refresh(state, explanation, snapshot.recording, snapshot.routedInput));
    }

    private synchronized void refresh(String requestedState, String explanation,
                                      boolean ignoredRecordingHint, String routedInputHint) {
        List<ReliableSessionManifest> sessions = store == null ? Collections.emptyList() : store.list();
        ReliableSessionManifest open = store == null ? null : store.latestUnfinished();
        ReliableSessionManifest interrupted = store == null ? null : store.latestInterrupted();
        boolean actualRecording = recorder.isRecording();
        boolean actualPaused = open != null && open.paused && !actualRecording;
        boolean actualInterrupted = open != null && open.isInterrupted() && !actualRecording;
        String state = RecordingStateResolver.normalize(requestedState,
                actualRecording, actualPaused, actualInterrupted);
        explanation = RecordingStateResolver.explanation(state, explanation);
        if (open != null) currentSessionId = open.sessionId;
        else if (!actualRecording) currentSessionId = null;
        String selected = snapshot.selectedInput;
        if (currentSessionId != null && store != null) {
            try { selected = store.load(currentSessionId).selectedInput; }
            catch (Exception ignored) {}
        }
        long duration = 0L;
        if (actualRecording) {
            duration = recordingBaseDurationMs
                    + Math.max(0L, SystemClock.elapsedRealtime() - recordingStartedAt);
        } else if (open != null) {
            duration = open.totalDurationMs;
        }
        String routedInput = actualRecording
                ? (routedInputHint == null || "Not recording".equals(routedInputHint)
                        ? snapshot.routedInput : routedInputHint)
                : "Not recording";
        long uploadTotalBytes = 0L;
        long uploadDurableBytes = 0L;
        int uploadTotalChunks = 0;
        int uploadDurableChunks = 0;
        for (ReliableSessionManifest manifest : sessions) {
            for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                if (segment.mp3Bytes <= 0L) continue;
                uploadTotalBytes += segment.mp3Bytes;
                uploadTotalChunks++;
                if (segment.remoteAccepted) {
                    uploadDurableBytes += segment.mp3Bytes;
                    uploadDurableChunks++;
                }
            }
        }
        long uploadPendingBytes = Math.max(0L, uploadTotalBytes - uploadDurableBytes);
        int uploadProgressPermille = RecordingFeedback.uploadPermille(
                uploadDurableBytes, uploadTotalBytes);
        long levelAgeMs = liveInputLevelAtElapsedMs <= 0L ? Long.MAX_VALUE
                : Math.max(0L, SystemClock.elapsedRealtime() - liveInputLevelAtElapsedMs);
        float rmsDbfs = actualRecording && levelAgeMs < 1500L ? liveInputRmsDbfs : -120f;
        float peakDbfs = actualRecording && levelAgeMs < 1500L ? liveInputPeakDbfs : -120f;
        int inputLevelPermille = RecordingFeedback.levelPermille(peakDbfs);
        boolean inputSignalDetected = actualRecording && levelAgeMs < 1500L && peakDbfs > -50f;
        snapshot = new Snapshot(state, explanation, actualRecording, actualPaused,
                duration, store == null ? 0L : store.localBytes(),
                rmsDbfs, peakDbfs, inputLevelPermille, inputSignalDetected,
                uploadTotalBytes, uploadDurableBytes, uploadPendingBytes,
                uploadTotalChunks, uploadDurableChunks, uploadProgressPermille,
                selected, routedInput, sessions, interrupted, open, currentSessionId);
        if (!state.equals(lastLoggedState) || !explanation.equals(lastLoggedExplanation)) {
            lastLoggedState = state;
            lastLoggedExplanation = explanation;
            diag(PhoneDiagnostics.INFO, "service.state", currentSessionId,
                    explanation, PhoneDiagnostics.fields("state", state,
                            "requested_state", requestedState,
                            "recording", actualRecording,
                            "paused", actualPaused,
                            "interrupted", actualInterrupted,
                            "duration_ms", duration,
                            "local_bytes", snapshot.localBytes,
                            "session_count", sessions.size(),
                            "routed_input", routedInput));
        }
        publish();
        updateNotification();
        if (!actualRecording && !isCriticalForegroundState(state)) leaveForegroundIfIdle();
    }

    private void diag(String level, String event, String sessionId,
                      String message, org.json.JSONObject fields) {
        PhoneDiagnostics value = diagnostics;
        if (value != null) value.log(level, event, sessionId, message, fields);
    }

    private void diagError(String event, String sessionId, String operation,
                           Throwable failure, org.json.JSONObject fields) {
        PhoneDiagnostics value = diagnostics;
        if (value != null) value.error(event, sessionId, operation, failure, fields);
    }

    private void publish() { for (StatusListener listener : listeners) listener.onStatus(snapshot); }

    private void acquireCaptureWakeLock() {
        PowerManager.WakeLock value = captureWakeLock;
        if (value != null && !value.isHeld()) {
            try {
                value.acquire(WAKE_LOCK_TIMEOUT_MS);
                wakeLockAcquiredElapsedMs = SystemClock.elapsedRealtime();
            } catch (RuntimeException failure) {
                diagError("recording.wake_lock_failed", currentSessionId,
                        "Acquiring CPU wake lock for recording", failure,
                        PhoneDiagnostics.fields());
            }
        }
    }

    private void renewCaptureWakeLock() {
        PowerManager.WakeLock value = captureWakeLock;
        if (value == null) return;
        try {
            if (value.isHeld()) value.release();
            value.acquire(WAKE_LOCK_TIMEOUT_MS);
            wakeLockAcquiredElapsedMs = SystemClock.elapsedRealtime();
            diag(PhoneDiagnostics.DEBUG, "recording.wake_lock_renewed", currentSessionId,
                    "CPU wake lock was renewed for continuous recording",
                    PhoneDiagnostics.fields("timeout_ms", WAKE_LOCK_TIMEOUT_MS));
        } catch (RuntimeException failure) {
            diagError("recording.wake_lock_renew_failed", currentSessionId,
                    "Renewing CPU wake lock for recording", failure,
                    PhoneDiagnostics.fields());
        }
    }

    private void releaseCaptureWakeLock() {
        PowerManager.WakeLock value = captureWakeLock;
        if (value != null && value.isHeld()) {
            try { value.release(); } catch (RuntimeException ignored) {}
        }
        wakeLockAcquiredElapsedMs = 0L;
    }

    private void ensureForeground() {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        } else updateNotification();
    }

    private void leaveForegroundIfIdle() {
        if (snapshot.recording || isCriticalForegroundState(snapshot.state)) return;
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }

    private static boolean isCriticalForegroundState(String state) {
        return "PREPARING".equals(state)
                || "PAUSING".equals(state)
                || "FINISHING".equals(state)
                || "COMPRESSING".equals(state)
                || "CLEANING".equals(state);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_voice_button)
                .setContentTitle(snapshot.recording ? "Reliable recording is active"
                        : snapshot.paused ? "Recording is paused" : "Reliable audio storage")
                .setContentText(snapshot.explanation)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(snapshot.explanation))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(snapshot.recording || isCriticalForegroundState(snapshot.state))
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (snapshot.recording) {
            Intent pause = new Intent(this, RecordingService.class).setAction(ACTION_PAUSE);
            PendingIntent pauseIntent = PendingIntent.getService(this, 1, pause,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent finish = new Intent(this, RecordingService.class).setAction(ACTION_FINISH)
                    .putExtra(EXTRA_SESSION_ID, snapshot.currentSessionId);
            PendingIntent finishIntent = PendingIntent.getService(this, 2, finish,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "Pause", pauseIntent);
            builder.addAction(0, "Finish", finishIntent);
        } else if (snapshot.paused && snapshot.openSession != null) {
            Intent resume = new Intent(this, RecordingService.class).setAction(ACTION_RESUME)
                    .putExtra(EXTRA_SESSION_ID, snapshot.openSession.sessionId)
                    .putExtra(EXTRA_DEVICE_ID, snapshot.openSession.selectedDeviceId);
            PendingIntent resumeIntent = PendingIntent.getService(this, 3, resume,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Intent finish = new Intent(this, RecordingService.class).setAction(ACTION_FINISH)
                    .putExtra(EXTRA_SESSION_ID, snapshot.openSession.sessionId);
            PendingIntent finishIntent = PendingIntent.getService(this, 4, finish,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "Resume", resumeIntent);
            builder.addAction(0, "Finish", finishIntent);
        }
        return builder.build();
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Reliable recording and transfer", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Protects, compresses, and reconciles recordings");
        manager.createNotificationChannel(channel);
    }

    private void shutdownForUserExit(String reason) {
        if (!exitRequested.compareAndSet(false, true)) return;
        diag(PhoneDiagnostics.WARN, "service.user_exit", currentSessionId,
                "Voice Button was explicitly closed; all workers are stopping",
                PhoneDiagnostics.fields("reason", reason,
                        "recording", recorder.isRecording(),
                        "state", snapshot.state,
                        "paused", snapshot.paused,
                        "has_background_work", hasBackgroundWork()));
        main.removeCallbacks(ticker);
        PhoneDiagnostics value = diagnostics;
        if (value != null) value.cancelActiveTransmission();
        if (currentSessionId != null && store != null) {
            try { store.markAutoResumeRequested(currentSessionId, false); }
            catch (Exception failure) {
                diagError("recording.auto_resume_clear_failed", currentSessionId,
                        "Clearing automatic resume before explicit close", failure,
                        PhoneDiagnostics.fields());
            }
        }
        if (recorder.isRecording()) stopDisposition = STOP_INTERRUPT;
        recorder.stop();
        releaseCaptureWakeLock();
        ReliableUploader currentUploader = uploader;
        if (currentUploader != null) currentUploader.stop();
        ExecutorService currentConversion = conversion;
        currentConversion.shutdownNow();
        Thread maintenance = maintenanceThread;
        if (maintenance != null) maintenance.interrupt();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);

        boolean recorderStopped = recorder.awaitStopped(750L);
        boolean uploaderStopped = currentUploader == null || currentUploader.awaitStopped(750L);
        boolean conversionStopped = false;
        try { conversionStopped = currentConversion.awaitTermination(750L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        boolean maintenanceStopped = maintenance == null || !maintenance.isAlive();
        if (maintenance != null && maintenance.isAlive()) {
            try { maintenance.join(250L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            maintenanceStopped = !maintenance.isAlive();
        }
        diag(PhoneDiagnostics.INFO, "service.user_exit_complete", currentSessionId,
                "Voice Button shutdown sequence completed",
                PhoneDiagnostics.fields("recorder_stopped", recorderStopped,
                        "uploader_stopped", uploaderStopped,
                        "conversion_stopped", conversionStopped,
                        "maintenance_stopped", maintenanceStopped));
        if (value != null) value.shutdownForAppExit();
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        shutdownForUserExit("task_removed");
        super.onTaskRemoved(rootIntent);
    }

    private static void checkMaintenanceInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Local file operation cancelled because Voice Button was closed");
        }
    }

    private static void deleteTree(File file) throws IOException {
        checkMaintenanceInterrupted();
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        checkMaintenanceInterrupted();
        if (!file.delete()) throw new IOException("Could not delete " + file.getName());
    }

    @Override public void onDestroy() {
        diag(PhoneDiagnostics.WARN, "service.destroy", currentSessionId,
                "RecordingService onDestroy entered",
                PhoneDiagnostics.fields("recording", recorder.isRecording(),
                        "snapshot_state", snapshot.state,
                        "exit_requested", exitRequested.get()));
        main.removeCallbacks(ticker);
        if (recorder.isRecording()) stopDisposition = STOP_INTERRUPT;
        recorder.stop();
        releaseCaptureWakeLock();
        if (uploader != null) uploader.stop();
        conversion.shutdownNow();
        Thread maintenance = maintenanceThread;
        if (maintenance != null) maintenance.interrupt();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        super.onDestroy();
    }
}
