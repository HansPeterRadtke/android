package com.hans.android.voicebutton;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
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
import java.util.concurrent.atomic.AtomicReference;

public final class RecordingService extends Service {
    public static final String ACTION_START = "com.hans.android.voicebutton.START";
    public static final String ACTION_STOP = "com.hans.android.voicebutton.STOP";
    public static final String ACTION_PAUSE = "com.hans.android.voicebutton.PAUSE";
    public static final String ACTION_FINISH = "com.hans.android.voicebutton.FINISH";
    public static final String ACTION_RESUME = "com.hans.android.voicebutton.RESUME";
    public static final String ACTION_FINISH_AND_START = "com.hans.android.voicebutton.FINISH_AND_START";
    public static final String ACTION_RETRY = "com.hans.android.voicebutton.RETRY";
    public static final String ACTION_PREPARE_PLAYBACK =
            "com.hans.android.voicebutton.PREPARE_PLAYBACK";
    public static final String ACTION_RECOVER_AFTER_BOOT =
            "com.hans.android.voicebutton.RECOVER_AFTER_BOOT";
    public static final String ACTION_DELETE_LOCAL = "com.hans.android.voicebutton.DELETE_LOCAL";
    public static final String ACTION_EXIT = "com.hans.android.voicebutton.EXIT";
    public static final String ACTION_SILENCE_ALARM = "com.hans.android.voicebutton.SILENCE_ALARM";
    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_FOLDER_ID = "folder_id";
    public static final String EXTRA_FOLDER_NAME = "folder_name";

    private static final String CHANNEL_ID = "reliable_voice_capture";
    private static final String ERROR_CHANNEL_ID = "recording_failure_alarm";
    private static final int NOTIFICATION_ID = 4101;
    private static final int STOP_NONE = 0;
    private static final int STOP_PAUSE = 1;
    private static final int STOP_FINISH = 2;
    private static final int STOP_INTERRUPT = 3;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 5L * 60L * 1000L;
    private static final long CONTINUITY_TICK_MS = 5000L;
    private static final long LIVE_TICK_MS = 500L;
    private static final long HEARTBEAT_MS = 30000L;
    private static final long NOTIFICATION_REFRESH_MS = 2000L;
    private static final long STATUS_SESSION_SCAN_INTERVAL_MS = 5000L;

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
        public final String liveUploadOperation;
        public final String liveUploadSessionId;
        public final int liveUploadSequence;
        public final long liveUploadDurableBytes;
        public final long liveUploadTotalBytes;
        public final int liveUploadProgressPermille;
        public final long liveUploadLastProgressWallMs;
        public final String selectedInput;
        public final String routedInput;
        public final List<ReliableSessionManifest> sessions;
        public final ReliableSessionManifest interrupted;
        public final ReliableSessionManifest openSession;
        public final String currentSessionId;
        public final boolean recordingErrorActive;
        public final boolean recordingErrorAlarmAudible;
        public final String recordingErrorMessage;
        public final int recordingRecoveryAttempt;

        Snapshot(String state, String explanation, boolean recording, boolean paused, long durationMs,
                 long localBytes, float inputRmsDbfs, float inputPeakDbfs,
                 int inputLevelPermille, boolean inputSignalDetected,
                 long uploadTotalBytes, long uploadDurableBytes, long uploadPendingBytes,
                 int uploadTotalChunks, int uploadDurableChunks, int uploadProgressPermille,
                 String liveUploadOperation, String liveUploadSessionId,
                 int liveUploadSequence, long liveUploadDurableBytes,
                 long liveUploadTotalBytes, int liveUploadProgressPermille,
                 long liveUploadLastProgressWallMs,
                 String selectedInput, String routedInput,
                 List<ReliableSessionManifest> sessions, ReliableSessionManifest interrupted,
                 ReliableSessionManifest openSession, String currentSessionId,
                 boolean recordingErrorActive, boolean recordingErrorAlarmAudible,
                 String recordingErrorMessage, int recordingRecoveryAttempt) {
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
            this.liveUploadOperation = liveUploadOperation == null ? "" : liveUploadOperation;
            this.liveUploadSessionId = liveUploadSessionId == null ? "" : liveUploadSessionId;
            this.liveUploadSequence = liveUploadSequence;
            this.liveUploadDurableBytes = liveUploadDurableBytes;
            this.liveUploadTotalBytes = liveUploadTotalBytes;
            this.liveUploadProgressPermille = liveUploadProgressPermille;
            this.liveUploadLastProgressWallMs = liveUploadLastProgressWallMs;
            this.selectedInput = selectedInput;
            this.routedInput = routedInput;
            this.sessions = sessions;
            this.interrupted = interrupted;
            this.openSession = openSession;
            this.currentSessionId = currentSessionId;
            this.recordingErrorActive = recordingErrorActive;
            this.recordingErrorAlarmAudible = recordingErrorAlarmAudible;
            this.recordingErrorMessage = recordingErrorMessage;
            this.recordingRecoveryAttempt = recordingRecoveryAttempt;
        }

        static Snapshot initial() {
            return new Snapshot("READY", "Choose an input and start recording", false, false, 0L, 0L,
                    -120f, -120f, 0, false,
                    0L, 0L, 0L, 0, 0, 0,
                    "idle", "", -1, 0L, 0L, 0, 0L,
                    "No microphone selected", "Not recording", Collections.emptyList(), null, null, null,
                    false, false, "", 0);
        }
    }

    private static final class RefreshRequest {
        final String state;
        final String explanation;
        final String routedInput;

        RefreshRequest(String state, String explanation, String routedInput) {
            this.state = state;
            this.explanation = explanation;
            this.routedInput = routedInput;
        }
    }

    public final class LocalBinder extends Binder { public RecordingService getService() { return RecordingService.this; } }

    private final IBinder binder = new LocalBinder();
    private final CopyOnWriteArraySet<StatusListener> listeners = new CopyOnWriteArraySet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ExecutorService conversion = Executors.newSingleThreadExecutor();
    private final ExecutorService serviceExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voicebutton-service-command");
        thread.setDaemon(false);
        return thread;
    });
    private final ExecutorService maintenanceExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voicebutton-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService statusExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voicebutton-status");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<RefreshRequest> pendingRefresh = new AtomicReference<>();
    private final AtomicBoolean refreshWorkerRunning = new AtomicBoolean(false);
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
    private volatile long lastNotificationElapsedMs;
    private volatile boolean backgroundWorkCached;
    private volatile boolean pendingFolderSyncCached;
    private volatile boolean serviceInitializing = true;
    private volatile boolean serviceInitialized;
    private volatile long lastUploaderRefreshElapsedMs;
    private volatile long localBytesCached;
    private volatile long lastLocalBytesScanElapsedMs;
    private volatile long lastSessionListScanElapsedMs;
    private final AtomicReference<RefreshRequest> pendingUploaderRefresh = new AtomicReference<>();
    private volatile String lastLoggedState = "";
    private volatile String lastLoggedExplanation = "";
    private final AtomicBoolean exitRequested = new AtomicBoolean(false);
    private volatile Thread maintenanceThread;
    private PowerManager.WakeLock captureWakeLock;
    private volatile long wakeLockAcquiredElapsedMs;
    private volatile float liveInputRmsDbfs = -120f;
    private volatile float liveInputPeakDbfs = -120f;
    private volatile long liveInputLevelAtElapsedMs;
    private volatile String lastEnhancementSummary =
            "live_platform_agc=unknown offline_enhancement=not_configured";
    private final RecordingFailureAlarm failureAlarm = new RecordingFailureAlarm(main);
    private volatile boolean recordingRecoveryPending;
    private volatile int recordingRecoveryAttempt;
    private volatile String recordingRecoverySessionId = "";
    private volatile String recordingRecoveryDetail = "";
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable continuityTicker = new Runnable() {
        @Override public void run() {
            boolean keep = shouldKeepServiceAlive();
            if (keep) {
                ensureForeground();
                acquireCaptureWakeLock();
                signalUploader("queued_work");
                main.postDelayed(this, CONTINUITY_TICK_MS);
            } else {
                releaseCaptureWakeLock();
                leaveForegroundIfIdle();
            }
        }
    };

    private final Runnable uploaderRefresh = new Runnable() {
        @Override public void run() {
            RefreshRequest request = pendingUploaderRefresh.getAndSet(null);
            if (request == null || exitRequested.get()) return;
            lastUploaderRefreshElapsedMs = SystemClock.elapsedRealtime();
            refresh(request.state, request.explanation, snapshot.recording, request.routedInput);
        }
    };

    private final Runnable recordingRecovery = () -> {
        try { serviceExecutor.execute(this::performRecordingRecovery); }
        catch (RuntimeException ignored) {}
    };

    private void performRecordingRecovery() {
        if (!recordingRecoveryPending || exitRequested.get() || recorder.isRecording()) return;
        String sessionId = recordingRecoverySessionId;
        if (sessionId == null || sessionId.isEmpty() || store == null) return;
        try {
            ReliableSessionManifest manifest = store.load(sessionId);
            if (manifest.recordingFinished || manifest.paused || !manifest.autoResumeRequested) {
                cancelAutomaticRecovery(false);
                return;
            }
            recordingRecoveryAttempt++;
            AudioInputOption input = resolveInput(manifest.selectedDeviceId);
            store.markResumed(sessionId, input.getLabel(), input.getDeviceId());
            diag(PhoneDiagnostics.WARN, "recording.recovery_attempt", sessionId,
                    "Automatically retrying microphone capture",
                    PhoneDiagnostics.fields("attempt", recordingRecoveryAttempt,
                            "device_id", input.getDeviceId(),
                            "device_type", input.getDeviceType(),
                            "detail", recordingRecoveryDetail));
            startCapture(sessionId, input);
        } catch (Exception failure) {
            recordingRecoveryDetail = PhoneDiagnostics.exactFailure(
                    "Automatically recovering microphone capture", failure);
            startFailureIncident(sessionId, recordingRecoveryDetail);
            refresh("RECOVERING", recordingRecoveryDetail
                    + ". Retrying automatically; durable chunks remain safe.",
                    false, "Not recording");
            scheduleAutomaticRecovery(sessionId);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (recorder.isRecording()) {
                publishLiveTelemetry();
                long now = SystemClock.elapsedRealtime();
                if (now - wakeLockAcquiredElapsedMs >= WAKE_LOCK_RENEW_MS) {
                    renewCaptureWakeLock();
                }
                if (now - lastHeartbeatElapsedMs >= HEARTBEAT_MS) {
                    lastHeartbeatElapsedMs = now;
                    ReliableSessionManifest open = snapshot.openSession;
                    diag(PhoneDiagnostics.DEBUG, "recording.heartbeat", snapshot.currentSessionId,
                            "Recording heartbeat",
                            PhoneDiagnostics.fields("duration_ms", snapshot.durationMs,
                                    "local_bytes", snapshot.localBytes,
                                    "segment_count", open == null ? 0 : open.segments.size(),
                                    "routed_input", snapshot.routedInput));
                }
                main.postDelayed(this, LIVE_TICK_MS);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        UploadWorkCoordinator.markServiceStarting();
        createNotificationChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            captureWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "VoiceButton:ReliableCapture");
            captureWakeLock.setReferenceCounted(false);
        }
        snapshot = new Snapshot("STARTING", "Opening protected recording storage",
                false, false, 0L, 0L, -120f, -120f, 0, false,
                0L, 0L, 0L, 0, 0, 0,
                "idle", "", -1, 0L, 0L, 0, 0L,
                "Microphone not ready", "Not recording", Collections.emptyList(),
                null, null, null, false, false, "", 0);
        publish();
        ensureForeground();
        try { serviceExecutor.execute(this::initializeService); }
        catch (RuntimeException failure) {
            serviceInitializing = false;
            snapshot = new Snapshot("FAILED", PhoneDiagnostics.exactFailure(
                    "Starting the recording worker", failure), false, false,
                    0L, 0L, -120f, -120f, 0, false,
                    0L, 0L, 0L, 0, 0, 0,
                    "idle", "", -1, 0L, 0L, 0, 0L,
                    "Microphone unavailable", "Not recording", Collections.emptyList(),
                    null, null, null, false, false, "", 0);
            publish();
        }
    }

    private void initializeService() {
        long started = SystemClock.elapsedRealtime();
        diagnostics = PhoneDiagnostics.initialize(this,
                BuildConfig.VOICE_BASE_URL, BuildConfig.VERSION_NAME);
        diag(PhoneDiagnostics.INFO, "service.create", null,
                "RecordingService background initialization entered",
                PhoneDiagnostics.fields("thread", Thread.currentThread().getName()));
        try {
            UploadWorkCoordinator.awaitServiceOwnership();
            store = ReliableSessionStore.openForBrowsing(this);
            uploader = new ReliableUploader(this, store,
                    BuildConfig.VOICE_BASE_URL, uploaderListener);
            registerNetworkCallback();
            serviceInitialized = true;
            serviceInitializing = false;
            diag(PhoneDiagnostics.INFO, "service.fast_ready", null,
                    "RecordingService is ready for capture before backlog recovery",
                    PhoneDiagnostics.fields("initialization_ms", Math.max(0L,
                                    SystemClock.elapsedRealtime() - started),
                            "thread", Thread.currentThread().getName()));
            publishImmediateState("READY",
                    "Ready to create a loss-protected recording",
                    "service_fast_ready");
            refresh("READY", "Ready to create a loss-protected recording",
                    false, "Not recording");
            scheduleDeferredStartupRecovery("service_initialized");
        } catch (Exception failure) {
            serviceInitialized = false;
            serviceInitializing = false;
            String exact = PhoneDiagnostics.exactFailure(
                    "Opening private recording storage", failure);
            diagError("service.create_failed", null,
                    "Opening private recording storage", failure,
                    PhoneDiagnostics.fields(
                            "thread", Thread.currentThread().getName()));
            snapshot = new Snapshot("FAILED", exact, false, false,
                    0L, 0L, -120f, -120f, 0, false,
                    0L, 0L, 0L, 0, 0, 0,
                    "idle", "", -1, 0L, 0L, 0, 0L,
                    "No microphone selected", "Not recording",
                    Collections.emptyList(), null, null, null,
                    failureAlarm.isActive(), failureAlarm.isAudible(),
                    failureAlarm.getMessage(), recordingRecoveryAttempt);
            publish();
        }
        main.removeCallbacks(continuityTicker);
        main.post(continuityTicker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        final int deviceId = intent == null ? AudioInputOption.DEFAULT_DEVICE_ID
                : intent.getIntExtra(EXTRA_DEVICE_ID,
                        AudioInputOption.DEFAULT_DEVICE_ID);
        final String sessionId = intent == null ? null
                : intent.getStringExtra(EXTRA_SESSION_ID);
        String requestedFolderId = intent == null ? "default"
                : intent.getStringExtra(EXTRA_FOLDER_ID);
        String requestedFolderName = intent == null ? "Default"
                : intent.getStringExtra(EXTRA_FOLDER_NAME);
        final String folderId = requestedFolderId == null
                || requestedFolderId.isEmpty() ? "default" : requestedFolderId;
        final String folderName = requestedFolderName == null
                || requestedFolderName.isEmpty() ? "Default" : requestedFolderName;
        boolean foregroundLaunch = ACTION_START.equals(action)
                || ACTION_RESUME.equals(action)
                || ACTION_FINISH_AND_START.equals(action)
                || ACTION_RECOVER_AFTER_BOOT.equals(action)
                || ACTION_PREPARE_PLAYBACK.equals(action);
        if (foregroundLaunch) ensureForeground();
        try {
            serviceExecutor.execute(() -> executeServiceAction(action, deviceId,
                    sessionId, folderId, folderName, startId, flags));
        } catch (RuntimeException failure) {
            refresh("FAILED", PhoneDiagnostics.exactFailure(
                    "Queueing the recording action", failure),
                    snapshot.recording, snapshot.routedInput);
        }
        return START_STICKY;
    }

    private void executeServiceAction(String action, int deviceId,
                                      String sessionId, String folderId,
                                      String folderName, int startId, int flags) {
        diag(PhoneDiagnostics.INFO, "service.action_received", sessionId,
                "RecordingService received an action",
                PhoneDiagnostics.fields("action", action == null ? "null" : action,
                        "device_id", deviceId, "start_id", startId,
                        "flags", flags, "recording", recorder.isRecording(),
                        "snapshot_state", snapshot.state,
                        "thread", Thread.currentThread().getName()));
        try {
            if (ACTION_START.equals(action)) startNew(deviceId, folderId, folderName);
            else if (ACTION_RESUME.equals(action)) resumeSession(sessionId, deviceId);
            else if (ACTION_FINISH_AND_START.equals(action)) {
                finishInterruptedAndStart(sessionId, deviceId, folderId, folderName);
            } else if (ACTION_PAUSE.equals(action)) pauseRecording();
            else if (ACTION_FINISH.equals(action) || ACTION_STOP.equals(action)) {
                finishRecording(sessionId);
            } else if (ACTION_RETRY.equals(action)) {
                retrySynchronizationNow("legacy_retry_action");
            } else if (ACTION_PREPARE_PLAYBACK.equals(action)) {
                prepareRecordingPlayback(sessionId);
            } else if (ACTION_RECOVER_AFTER_BOOT.equals(action)) {
                diag(PhoneDiagnostics.INFO, "service.boot_recovery_checked",
                        currentSessionId,
                        "Boot recovery checked persisted capture continuity",
                        PhoneDiagnostics.fields("recording",
                                recorder.isRecording(),
                                "state", snapshot.state));
            } else if (ACTION_DELETE_LOCAL.equals(action)) deleteLocalFiles();
            else if (ACTION_SILENCE_ALARM.equals(action)) silenceFailureAlarm();
            else if (ACTION_EXIT.equals(action)) shutdownForUserExit("explicit_close");
        } catch (Exception failure) {
            String operation = "Action " + (action == null ? "null" : action);
            String exact = PhoneDiagnostics.exactFailure(operation, failure)
                    + ". Local audio and metadata were preserved.";
            diagError("service.action_failed", sessionId, operation, failure,
                    PhoneDiagnostics.fields("action", action == null ? "null" : action,
                            "device_id", deviceId,
                            "snapshot_state", snapshot.state,
                            "thread", Thread.currentThread().getName()));
            refresh("FAILED", exact, snapshot.recording, snapshot.routedInput);
            if (!recorder.isRecording()) main.post(this::leaveForegroundIfIdle);
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public void addStatusListener(StatusListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onStatus(snapshot);
    }

    public void removeStatusListener(StatusListener listener) { if (listener != null) listeners.remove(listener); }
    public Snapshot getSnapshot() { return snapshot; }

    public void retrySynchronization() {
        try {
            maintenanceExecutor.execute(() -> {
                try {
                    retrySynchronizationNow("manual_retry");
                } catch (Exception failure) {
                    diagError("upload.manual_retry_failed", null,
                            "Restarting synchronization", failure,
                            PhoneDiagnostics.fields("recording",
                                    recorder.isRecording(),
                                    "state", snapshot.state));
                    if (!recorder.isRecording()) {
                        refresh("FAILED", PhoneDiagnostics.exactFailure(
                                "Restarting synchronization", failure)
                                + ". Local recordings remain preserved.",
                                false, snapshot.routedInput);
                    }
                }
            });
        } catch (RuntimeException failure) {
            diagError("upload.manual_retry_queue_failed", null,
                    "Queueing synchronization retry", failure,
                    PhoneDiagnostics.fields("state", snapshot.state));
        }
    }

    private void prepareRecordingPlayback(String sessionId)
            throws IOException {
        if (store == null) throw new IOException(
                "Recording storage is not ready");
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IOException("Recording identity is missing");
        }
        ReliableSessionManifest manifest = store.load(sessionId);
        File finalFile = store.finalMp3File(sessionId);
        RecordingPlaybackPolicy.Action action = RecordingPlaybackPolicy.decide(
                manifest, finalFile.isFile() && finalFile.length() > 0L,
                recorder.isRecording());
        diag(PhoneDiagnostics.INFO, "player.prepare_requested", sessionId,
                "A playable local recording was requested",
                PhoneDiagnostics.fields("policy_action", action.name(),
                        "recording_finished", manifest.recordingFinished,
                        "conversion_finished", manifest.conversionFinished,
                        "segment_count", manifest.segments.size(),
                        "final_exists", finalFile.isFile(),
                        "capture_active", recorder.isRecording()));
        if (action == RecordingPlaybackPolicy.Action.READY) {
            diag(PhoneDiagnostics.INFO, "player.prepare_ready", sessionId,
                    "The local recording is already playable",
                    PhoneDiagnostics.fields("bytes", finalFile.length()));
            refresh("READY", "The selected recording is ready to play",
                    false, snapshot.routedInput);
            return;
        }
        if (action == RecordingPlaybackPolicy.Action.BLOCK_CAPTURE) {
            throw new IOException("Pause or finish microphone capture before preparing another recording for playback");
        }
        if (action == RecordingPlaybackPolicy.Action.NO_AUDIO) {
            throw new IOException("This recording has no durable local audio to play");
        }
        ensureForeground();
        if (action == RecordingPlaybackPolicy.Action.FINALIZE) {
            refresh("FINISHING", "Preparing the selected recording for playback",
                    false, snapshot.routedInput);
            scheduleFinalization(sessionId);
        } else {
            refresh("PREPARING", "Creating a safe playable snapshot from durable audio",
                    false, snapshot.routedInput);
            schedulePreview(sessionId);
        }
    }

    private void retrySynchronizationNow(String reason) throws IOException {
        if (recorder.isRecording()) {
            diag(PhoneDiagnostics.INFO, "upload.manual_retry_deferred", currentSessionId,
                    "Synchronization retry was deferred until recording stops",
                    PhoneDiagnostics.fields("reason", reason,
                            "recording", true));
            return;
        }
        restartUploader(reason);
        refresh("RECONCILING",
                "Restarted synchronization and checking Jetson durable offsets",
                false, snapshot.routedInput);
    }

    public List<ReliableSessionStore.Folder> listFolders() {
        return store == null ? Collections.singletonList(
                new ReliableSessionStore.Folder("default", "Default", 0L)) : store.listFolders();
    }

    public ReliableSessionStore.Folder createFolder(String name)
            throws IOException {
        return createFolder(name, "");
    }

    public ReliableSessionStore.Folder createFolder(String name,
                                                     String parentFolderId)
            throws IOException {
        if (store == null) throw new IOException(
                "Recording storage is not ready");
        ReliableSessionStore.Folder folder =
                store.createFolder(name, parentFolderId);
        new Thread(() -> {
            try {
                new ReliableUploadClient(BuildConfig.VOICE_BASE_URL,
                        "VoiceButton/" + BuildConfig.VERSION_NAME + " Android")
                        .createFolder(folder.id, folder.name, folder.parentId);
                store.markFolderRemote(folder.id, folder.name,
                        folder.parentId);
                signalUploader("queued_work");
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

    public String buildSupportSummary() {
        Snapshot value = snapshot;
        StringBuilder out = new StringBuilder(8192);
        out.append("Voice Button support summary\n");
        out.append("app_version=").append(BuildConfig.VERSION_NAME)
                .append(" code=").append(BuildConfig.VERSION_CODE).append('\n');
        out.append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n');
        PhoneDiagnostics diagnosticValue = diagnostics;
        out.append("installation_id=")
                .append(diagnosticValue == null ? "unavailable" : diagnosticValue.getInstallationId())
                .append('\n');
        out.append("state=").append(value.state)
                .append(" recording=").append(value.recording)
                .append(" paused=").append(value.paused)
                .append(" alarm=").append(value.recordingErrorActive).append('\n');
        out.append("status=").append(limit(value.explanation, 400)).append('\n');
        out.append("microphone=").append(limit(value.routedInput, 240))
                .append(" signal=").append(value.inputSignalDetected).append('\n');
        out.append("duration_ms=").append(value.durationMs)
                .append(" local_bytes=").append(value.localBytes).append('\n');
        out.append("sync_bytes=").append(value.uploadDurableBytes).append('/')
                .append(value.uploadTotalBytes)
                .append(" pending=").append(value.uploadPendingBytes)
                .append(" progress_permille=").append(value.uploadProgressPermille).append('\n');
        out.append("sessions=").append(value.sessions.size())
                .append(" current_session=").append(value.currentSessionId).append('\n');
        out.append("library_filename_layout=")
                .append(FileNameParts.LAYOUT_ID).append('\n');
        out.append("player_lifecycle=foreground_service_atomic_checkpoint_v2\n");
        out.append("player=").append(limit(readLatestPlayerSummary(), 1000)).append('\n');
        ReliableUploader uploaderValue = uploader;
        out.append("uploader=").append(uploaderValue == null
                ? "unavailable" : limit(uploaderValue.debugSummary(), 1000)).append('\n');
        int start = Math.max(0, value.sessions.size() - 5);
        for (int i = start; i < value.sessions.size(); i++) {
            ReliableSessionManifest session = value.sessions.get(i);
            out.append("session ").append(session.sessionId)
                    .append(" folder=").append(limit(session.folderName, 120))
                    .append(" state=").append(session.state)
                    .append(" chunks=").append(session.segments.size())
                    .append(" remote=").append(session.durableRemoteChunkCount())
                    .append(" pending_bytes=").append(session.pendingRemoteBytes())
                    .append(" error=").append(limit(session.error, 300)).append('\n');
        }
        if (out.length() > 24000) return out.substring(0, 24000) + "\n[summary truncated]\n";
        return out.toString();
    }

    private String readLatestPlayerSummary() {
        File file = new File(new File(getNoBackupFilesDir(), "player_state"),
                "latest_summary.txt");
        if (!file.isFile()) return "unavailable";
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            String value = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? "empty" : value;
        } catch (Exception failure) {
            return "unreadable: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage();
        }
    }

    private static String limit(String value, int maximum) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= maximum ? text : text.substring(0, maximum) + "…";
    }

    public synchronized String buildDebugReport() {
        StringBuilder out = new StringBuilder(16384);
        Snapshot value = snapshot;
        out.append("Voice Button debug report\n");
        out.append("generated_wall_ms=").append(System.currentTimeMillis()).append('\n');
        out.append("app_version=").append(BuildConfig.VERSION_NAME)
                .append(" code=").append(BuildConfig.VERSION_CODE).append('\n');
        out.append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" android=").append(Build.VERSION.RELEASE).append('\n');
        PhoneDiagnostics diagnosticValue = diagnostics;
        out.append("installation_id=")
                .append(diagnosticValue == null ? "unavailable" : diagnosticValue.getInstallationId())
                .append('\n');
        out.append("server_url=").append(BuildConfig.VOICE_BASE_URL).append('\n');
        out.append("state=").append(value.state)
                .append(" explanation=").append(value.explanation).append('\n');
        out.append("recording=").append(value.recording)
                .append(" paused=").append(value.paused)
                .append(" current_session=").append(value.currentSessionId).append('\n');
        out.append("selected_input=").append(value.selectedInput)
                .append(" routed_input=").append(value.routedInput).append('\n');
        out.append("input_rms_dbfs=").append(value.inputRmsDbfs)
                .append(" input_peak_dbfs=").append(value.inputPeakDbfs)
                .append(" input_level_permille=").append(value.inputLevelPermille)
                .append(" signal_detected=").append(value.inputSignalDetected).append('\n');
        out.append("upload_bytes=").append(value.uploadDurableBytes).append('/')
                .append(value.uploadTotalBytes)
                .append(" pending=").append(value.uploadPendingBytes)
                .append(" progress_permille=").append(value.uploadProgressPermille).append('\n');
        out.append("upload_chunks=").append(value.uploadDurableChunks).append('/')
                .append(value.uploadTotalChunks).append('\n');
        ReliableUploader uploaderValue = uploader;
        out.append("uploader=")
                .append(uploaderValue == null ? "unavailable" : uploaderValue.debugSummary())
                .append('\n');
        out.append("local_total_bytes=").append(value.localBytes)
                .append(" session_count=").append(value.sessions.size()).append('\n');
        List<ReliableSessionManifest> sessions = new ArrayList<>(value.sessions);
        sessions.sort(Comparator.comparingLong(session -> session.createdAt));
        for (ReliableSessionManifest session : sessions) {
            out.append("\nSESSION ").append(session.sessionId).append('\n');
            out.append(" folder=").append(session.folderId).append(" name=")
                    .append(session.folderName).append('\n');
            out.append(" state=").append(session.state)
                    .append(" recording_finished=").append(session.recordingFinished)
                    .append(" paused=").append(session.paused)
                    .append(" conversion_finished=").append(session.conversionFinished)
                    .append(" remote_committed=").append(session.remoteCommitted)
                    .append(" auto_resume=").append(session.autoResumeRequested).append('\n');
            out.append(" created_at=").append(session.createdAt)
                    .append(" updated_at=").append(session.updatedAt)
                    .append(" duration_ms=").append(session.totalDurationMs)
                    .append(" segment_bytes=").append(session.totalSegmentBytes)
                    .append(" final_bytes=").append(session.finalMp3Bytes).append('\n');
            out.append(" server_id=").append(session.remoteServerId)
                    .append(" server_revision=").append(session.remoteManifestRevision)
                    .append(" error=").append(session.error).append('\n');
            for (ReliableSessionManifest.Segment segment : session.orderedSegments()) {
                out.append("  CHUNK seq=").append(segment.seq)
                        .append(" bytes=").append(segment.mp3Bytes)
                        .append(" local_durable_at=").append(segment.localDurableAtMs)
                        .append(" remote_accepted=").append(segment.remoteAccepted)
                        .append(" remote_partial=").append(segment.remotePartialBytes)
                        .append(" attempts=").append(segment.sendAttempts)
                        .append(" first_send=").append(segment.firstSendAtMs)
                        .append(" last_send=").append(segment.lastSendAtMs)
                        .append(" server=").append(segment.remoteServerId)
                        .append(" revision=").append(segment.remoteManifestRevision)
                        .append(" server_received=").append(segment.remoteReceivedAtMs)
                        .append(" server_durable=").append(segment.remoteDurableAtMs)
                        .append(" transcript=").append(segment.transcriptState)
                        .append(" last_error=").append(segment.lastSendError)
                        .append(" sha256=").append(segment.sha256)
                        .append('\n');
                if (store != null) {
                    try {
                        File file = store.mp3File(session.sessionId, segment);
                        out.append("   local_file_exists=").append(file.isFile())
                                .append(" local_file_bytes=").append(file.isFile() ? file.length() : -1L)
                                .append('\n');
                    } catch (Exception failure) {
                        out.append("   local_file_check_error=")
                                .append(failure.getClass().getSimpleName()).append(": ")
                                .append(failure.getMessage()).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private void signalUploader(String reason) {
        if (!RecordingIsolationPolicy.mayRunDeferredWork(
                recorder.isRecording(), exitRequested.get())) return;
        ReliableUploader value = uploader;
        if (value == null) {
            try {
                value = new ReliableUploader(this, store,
                        BuildConfig.VOICE_BASE_URL, uploaderListener);
                uploader = value;
            } catch (RuntimeException failure) {
                diagError("upload.worker_create_failed", null,
                        "Creating the transfer worker", failure,
                        PhoneDiagnostics.fields("reason", reason));
                return;
            }
        }
        boolean restarted = value.ensureRunning();
        value.signal();
        if (restarted) {
            diag(PhoneDiagnostics.WARN,
                    "upload.worker_auto_restarted", null,
                    "A stopped transfer worker was recreated automatically",
                    PhoneDiagnostics.fields("reason", reason,
                            "worker", value.debugSummary()));
        }
    }

    private synchronized void suspendDeferredWorkForCapture()
            throws IOException {
        ReliableUploader currentUploader = uploader;
        if (currentUploader != null) currentUploader.stop();
        diag(PhoneDiagnostics.INFO,
                "recording.deferred_work_deprioritized", currentSessionId,
                "Background upload was asked to stop without blocking microphone capture",
                PhoneDiagnostics.fields(
                        "uploader_present", currentUploader != null,
                        "conversion_stopped", false));
    }

    private void resumeDeferredWork(String reason) {
        if (!RecordingIsolationPolicy.mayRunDeferredWork(
                recorder.isRecording(), exitRequested.get())
                || store == null) {
            return;
        }
        signalUploader(reason);
        ExecutorService current = conversion;
        try {
            current.execute(() -> {
                synchronized (fileMaintenanceLock) {
                    if (recorder.isRecording() || exitRequested.get()) return;
                    recoverPcmJournals();
                }
                if (!recorder.isRecording() && !exitRequested.get()) {
                    resumePendingConversions();
                    signalUploader("deferred_work_recovered");
                }
            });
        } catch (RuntimeException failure) {
            diagError("recording.deferred_work_resume_failed", null,
                    "Resuming conversion and transfer work", failure,
                    PhoneDiagnostics.fields("reason", reason));
        }
    }

    private synchronized void restartUploader(String reason) throws IOException {
        if (store == null) throw new IOException("Recording storage is not ready");
        ReliableUploader old = uploader;
        String oldSummary = old == null ? "unavailable" : old.debugSummary();
        if (old != null) {
            old.stop();
            old.awaitStopped(1500L);
        }
        ReliableUploader replacement = new ReliableUploader(
                this, store, BuildConfig.VOICE_BASE_URL, uploaderListener);
        uploader = replacement;
        replacement.start();
        replacement.signal();
        diag(PhoneDiagnostics.WARN, "upload.worker_restarted", null,
                "The transfer worker was replaced",
                PhoneDiagnostics.fields("reason", reason,
                        "previous_worker", oldSummary,
                        "new_worker", replacement.debugSummary()));
    }

    private void startNew(int deviceId, String folderId, String folderName) throws Exception {
        diag(PhoneDiagnostics.INFO, "recording.start_requested", null,
                "A new recording was requested", PhoneDiagnostics.fields("device_id", deviceId));
        if (recorder.isRecording()) throw new IOException("A recording is already active");
        ReliableSessionManifest unfinished = snapshot.openSession;
        if (unfinished == null && currentSessionId != null && !currentSessionId.isEmpty()) {
            try { unfinished = store.load(currentSessionId); }
            catch (Exception ignored) { unfinished = null; }
        }
        if (unfinished != null && !unfinished.recordingFinished) {
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
        try {
            startCapture(manifest.sessionId, input);
        } catch (Exception failure) {
            try { store.discardIfEmpty(manifest.sessionId); }
            catch (Exception ignored) {}
            throw failure;
        }
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
        try {
            startCapture(next.sessionId, input);
        } catch (Exception failure) {
            try { store.discardIfEmpty(next.sessionId); }
            catch (Exception ignored) {}
            throw failure;
        }
    }

    private void startCapture(String sessionId, AudioInputOption input)
            throws IOException {
        suspendDeferredWorkForCapture();
        ensureForeground();
        acquireCaptureWakeLock();
        currentSessionId = sessionId;
        backgroundWorkCached = true;
        captureFailed = false;
        main.removeCallbacks(recordingRecovery);
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
        cancelAutomaticRecovery(true);
        diag(PhoneDiagnostics.INFO, "recording.pause_requested", currentSessionId,
                "Pause was requested", PhoneDiagnostics.fields("duration_ms", snapshot.durationMs));
        if (!recorder.isRecording()) {
            ReliableSessionManifest open = store.latestUnfinished();
            if (open != null && open.paused) {
                refresh("PAUSED", "Recording is already paused and ready to resume", false, "Not recording");
                return;
            }
            if (open != null && (open.isInterrupted() || recordingRecoveryPending)) {
                try {
                    store.markPaused(open.sessionId);
                } catch (IOException failure) {
                    String exact = PhoneDiagnostics.exactFailure(
                            "Saving paused state during automatic recovery", failure);
                    diagError("recording.recovery_pause_failed", open.sessionId,
                            "Saving paused state during automatic recovery", failure,
                            PhoneDiagnostics.fields());
                    refresh("FAILED", exact, false, "Not recording");
                    return;
                }
                currentSessionId = open.sessionId;
                cancelAutomaticRecovery(true);
                schedulePreview(open.sessionId);
                refresh("PAUSED", "Automatic recovery stopped; recording is paused and ready to resume",
                        false, "Not recording");
                signalUploader("queued_work");
                return;
            }
            if (stopDisposition == STOP_PAUSE) {
                refresh("PAUSING", "Pause is already closing the current durable PCM journal", false, "Not recording");
                return;
            }
            throw new IllegalStateException("No recording is currently active to pause");
        }
        stopDisposition = STOP_PAUSE;
        refresh("PAUSING", "Closing and synchronizing the current durable PCM journal", true, snapshot.routedInput);
        recorder.stop();
    }

    private void finishRecording(String requestedSessionId) throws Exception {
        cancelAutomaticRecovery(true);
        diag(PhoneDiagnostics.INFO, "recording.finish_requested", requestedSessionId,
                "Finish was requested", PhoneDiagnostics.fields("recording", recorder.isRecording(),
                        "snapshot_state", snapshot.state));
        if (recorder.isRecording()) {
            stopDisposition = STOP_FINISH;
            refresh("FINISHING", "Closing and synchronizing the final durable PCM journal", true, snapshot.routedInput);
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
            signalUploader("queued_work");
            return;
        }
        if (!manifest.recordingFinished) {
            store.markRecordingFinished(sessionId,
                    manifest.paused ? "finished_from_pause" : "finished_after_recovery");
        }
        currentSessionId = null;
        scheduleFinalization(sessionId);
        refresh("FINISHING", "Creating the final playable MP3", false, "Not recording");
        signalUploader("queued_work");
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

    private final JournaledMp3Recorder.Listener recorderListener =
            new JournaledMp3Recorder.Listener() {
        @Override public void onStarted(String routedDevice) {
            cancelAutomaticRecovery(true);
            diag(PhoneDiagnostics.INFO, "recording.started",
                    currentSessionId,
                    "AudioRecord entered the recording state with isolated direct PCM journaling",
                    PhoneDiagnostics.fields("routed_device", routedDevice,
                            "start_duration_ms", Math.max(0L,
                                    SystemClock.elapsedRealtime()
                                            - recordingStartedAt),
                            "live_mp3", false,
                            "upload_during_capture", false));
            refresh("RECORDING",
                    "Microphone audio is being written directly to durable local storage; conversion and transfer are suspended",
                    true, routedDevice);
            main.removeCallbacks(ticker);
            main.post(ticker);
        }

        @Override public void onRecorderEvent(String event, int seq,
                                              long bytes,
                                              long durationMs,
                                              String detail) {
            if ("capture.automatic_gain_control".equals(event)) {
                lastEnhancementSummary = "live_platform_agc=" + detail
                        + "; offline_enhancement=not_configured"
                        + "; finish_blocks_for_encoding_only=false";
            } else if ("capture.pipeline_start".equals(event)) {
                lastEnhancementSummary = "live_platform_agc=pending; offline_enhancement=not_configured"
                        + "; capture_pipeline=" + detail;
            }
            String level = event.endsWith("exception")
                    ? PhoneDiagnostics.ERROR : PhoneDiagnostics.DEBUG;
            diag(level, event, currentSessionId, detail,
                    PhoneDiagnostics.fields("seq", seq,
                            "bytes", bytes,
                            "duration_ms", durationMs,
                            "detail", detail));
        }

        @Override public void onAudioLevel(float rmsDbfs,
                                           float peakDbfs,
                                           long capturedSamples) {
            liveInputRmsDbfs = rmsDbfs;
            liveInputPeakDbfs = peakDbfs;
            liveInputLevelAtElapsedMs = SystemClock.elapsedRealtime();
        }

        @Override public void onJournalCommitted(String sessionId,
                                                  int seq,
                                                  File pcmJournal,
                                                  int inputSampleRate,
                                                  long pcmBytes,
                                                  long durationMs) {
            long durableAtMs = System.currentTimeMillis();
            long createdAtMs = Math.max(0L,
                    durableAtMs - Math.max(0L, durationMs));
            try {
                maintenanceExecutor.execute(() -> commitPcmJournalWithRetry(
                        sessionId, seq, pcmJournal, inputSampleRate,
                        pcmBytes, durationMs, createdAtMs, durableAtMs));
            } catch (RuntimeException rejected) {
                String exact = PhoneDiagnostics.exactFailure(
                        "Queueing durable PCM metadata", rejected);
                captureFailed = true;
                recordingRecoveryDetail = exact;
                startFailureIncident(sessionId, exact);
                diagError("recording.pcm_metadata_queue_failed",
                        sessionId, "Queueing durable PCM metadata",
                        rejected, PhoneDiagnostics.fields("seq", seq,
                                "pcm_bytes", pcmBytes,
                                "file_name", pcmJournal.getName()));
            }
        }

        @Override public void onStopped(String sessionId) {
            try {
                maintenanceExecutor.execute(() ->
                        handleRecorderStopped(sessionId));
            } catch (RuntimeException rejected) {
                String exact = PhoneDiagnostics.exactFailure(
                        "Queueing recorder shutdown recovery", rejected);
                captureFailed = true;
                recordingRecoveryDetail = exact;
                startFailureIncident(sessionId, exact);
                diagError("recording.stop_handler_queue_failed",
                        sessionId,
                        "Queueing recorder shutdown recovery",
                        rejected, PhoneDiagnostics.fields());
            }
        }

        @Override public void onFailure(String stage,
                                        String exceptionClass,
                                        String message) {
            if (exitRequested.get()) return;
            captureFailed = true;
            String exact = "Recording failed during " + stage + ": "
                    + exceptionClass + ": " + message
                    + ". Every PCM byte synchronized before the failure remains recoverable.";
            recordingRecoveryDetail = exact;
            startFailureIncident(currentSessionId, exact);
            diag(PhoneDiagnostics.ERROR, "recording.failure",
                    currentSessionId, exact,
                    PhoneDiagnostics.fields("stage", stage,
                            "exception_class", exceptionClass,
                            "exception_message", message,
                            "alarm_started_before_journal_close", true));
            refresh("FAILED", exact, false, "Not recording");
        }
    };

    private void commitPcmJournalWithRetry(String sessionId, int seq,
                                           File pcmJournal,
                                           int inputSampleRate,
                                           long pcmBytes,
                                           long durationMs,
                                           long createdAtMs,
                                           long durableAtMs) {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                store.commitPcmJournal(sessionId, seq, pcmJournal,
                        inputSampleRate, pcmBytes, durationMs,
                        createdAtMs, durableAtMs);
                backgroundWorkCached = true;
                diag(PhoneDiagnostics.INFO,
                        "recording.pcm_journal_committed", sessionId,
                        "Direct PCM journal metadata was committed after microphone capture stopped",
                        PhoneDiagnostics.fields("seq", seq,
                                "bytes", pcmJournal.length(),
                                "duration_ms", durationMs,
                                "input_sample_rate", inputSampleRate,
                                "file_name", pcmJournal.getName(),
                                "attempt", attempt));
                return;
            } catch (Exception failure) {
                last = failure;
                if (attempt < 3) SystemClock.sleep(100L * attempt);
            }
        }
        captureFailed = true;
        String exact = PhoneDiagnostics.exactFailure(
                "Committing direct PCM journal metadata", last)
                + ". The PCM file itself remains on storage for startup recovery.";
        recordingRecoveryDetail = exact;
        startFailureIncident(sessionId, exact);
        diagError("recording.pcm_metadata_commit_failed", sessionId,
                "Committing direct PCM journal metadata", last,
                PhoneDiagnostics.fields("seq", seq,
                        "pcm_bytes", pcmJournal.length(),
                        "file_name", pcmJournal.getName(),
                        "attempts", 3));
    }

    private void handleRecorderStopped(String sessionId) {
        main.removeCallbacks(ticker);
        liveInputRmsDbfs = -120f;
        liveInputPeakDbfs = -120f;
        liveInputLevelAtElapsedMs = 0L;
        int disposition = stopDisposition;
        diag(PhoneDiagnostics.INFO, "recording.thread_stopped",
                sessionId, "Direct PCM recorder thread reported stopped",
                PhoneDiagnostics.fields("stop_disposition", disposition,
                        "capture_failed", captureFailed,
                        "snapshot_duration_ms", snapshot.durationMs,
                        "deferred_work_was_suspended", true));
        stopDisposition = STOP_NONE;
        if (exitRequested.get()) {
            try {
                if (!store.discardIfEmpty(sessionId)) {
                    ReliableSessionManifest current = store.load(sessionId);
                    if (!current.recordingFinished && !current.paused) {
                        if (disposition == STOP_PAUSE) {
                            store.markPaused(sessionId);
                        } else {
                            store.markInterrupted(sessionId,
                                    "App was explicitly closed before the recording was finished");
                        }
                    }
                }
            } catch (Exception failure) {
                diagError("recording.exit_recovery_failed", sessionId,
                        "Persisting recording state during app exit",
                        failure, PhoneDiagnostics.fields(
                                "stop_disposition", disposition));
            }
            return;
        }
        try {
            if (store.discardIfEmpty(sessionId)) {
                currentSessionId = null;
                String detail = captureFailed
                        ? "The microphone did not produce durable audio; the empty recording was removed"
                        : "No audio was captured; no stale recording was kept";
                refresh(captureFailed ? "FAILED" : "READY",
                        detail, false, "Not recording");
                resumeDeferredWork("empty_recording_removed");
                return;
            }
        } catch (Exception failure) {
            diagError("recording.empty_session_check_failed", sessionId,
                    "Checking whether the stopped session was empty",
                    failure, PhoneDiagnostics.fields());
        }
        try {
            ReliableSessionManifest persisted = store.load(sessionId);
            if (persisted.recordingFinished) {
                currentSessionId = null;
                refresh(persisted.conversionFinished
                                ? "READY" : "FINISHING",
                        persisted.conversionFinished
                                ? "The recording is finished and playable"
                                : "Creating the final playable MP3 from durable PCM",
                        false, "Not recording");
                resumeDeferredWork("finished_recording_stopped");
                return;
            }
        } catch (Exception failure) {
            diagError("recording.persisted_state_check_failed",
                    sessionId,
                    "Checking persisted recording state after capture stopped",
                    failure, PhoneDiagnostics.fields(
                            "stop_disposition", disposition));
        }
        if (captureFailed || disposition == STOP_INTERRUPT
                || disposition == STOP_NONE) {
            String detail = recordingRecoveryDetail == null
                    || recordingRecoveryDetail.isEmpty()
                    ? (captureFailed ? "Recording stopped unexpectedly"
                    : "Recording service was interrupted")
                    : recordingRecoveryDetail;
            try { store.markInterrupted(sessionId, detail); }
            catch (Exception ignored) {}
            currentSessionId = sessionId;
            startFailureIncident(sessionId, detail);
            scheduleAutomaticRecovery(sessionId);
            refresh("RECOVERING", detail
                            + ". Retrying automatically; durable PCM remains safe.",
                    false, "Not recording");
        } else if (disposition == STOP_PAUSE) {
            try { store.markPaused(sessionId); }
            catch (Exception failure) {
                store.markError(sessionId,
                        "Could not save paused state");
            }
            currentSessionId = sessionId;
            refresh("PAUSED",
                    "Recording is paused; creating a playable MP3 from durable PCM",
                    false, "Not recording");
        } else {
            try { store.markRecordingFinished(sessionId, "normal"); }
            catch (Exception failure) {
                store.markError(sessionId,
                        "Could not close recording metadata");
            }
            currentSessionId = null;
            refresh("FINISHING",
                    "Recording closed; creating the final MP3 from durable PCM",
                    false, "Not recording");
        }
        resumeDeferredWork("recording_stopped");
    }

    private void schedulePreview(String sessionId) {
        if (exitRequested.get()) return;
        conversion.execute(() -> {
            synchronized (fileMaintenanceLock) {
                long operationStarted = SystemClock.elapsedRealtime();
                diag(PhoneDiagnostics.INFO, "recording.preview_start", sessionId,
                        "Playable MP3 snapshot assembly started", PhoneDiagnostics.fields());
                try {
                    recoverPcmJournalsForSession(sessionId);
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
                    File preview = store.finalMp3File(sessionId);
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
                signalUploader("queued_work");
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
                recoverPcmJournalsForSession(sessionId);
                ReliableSessionManifest manifest = store.load(sessionId);
                File existingFinal = store.finalMp3File(sessionId);
                if (manifest.conversionFinished && existingFinal.isFile() && existingFinal.length() > 0L) {
                    refreshFromWorker("READY", "The recording is already finished and playable");
                    signalUploader("queued_work");
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
                File finalMp3 = store.finalMp3File(sessionId);
                mp3.concatenateMp3Segments(compressed, finalMp3);
                store.markConversionFinished(sessionId, finalMp3);
                diag(PhoneDiagnostics.INFO, "recording.finalization_complete", sessionId,
                        "Final MP3 assembly completed",
                        PhoneDiagnostics.fields("operation_duration_ms",
                                Math.max(0L, SystemClock.elapsedRealtime() - operationStarted),
                                "bytes", finalMp3.length(),
                                "segment_count", compressed.size(),
                                "sha256", ReliableSessionStore.sha256File(finalMp3)));
                signalUploader("queued_work");
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
            try {
                recoverPcmJournalsForSession(manifest.sessionId);
            } catch (Exception failure) {
                store.markError(manifest.sessionId,
                        PhoneDiagnostics.exactFailure(
                                "Recovering durable PCM journals", failure));
                diagError("recovery.pcm_session_failed",
                        manifest.sessionId,
                        "Recovering durable PCM journals", failure,
                        PhoneDiagnostics.fields());
            }
        }
    }

    private void recoverPcmJournalsForSession(String sessionId)
            throws IOException {
        ReliableSessionManifest manifest = store.load(sessionId);
        long cursor = 0L;
        for (ReliableSessionManifest.Segment existing
                : manifest.orderedSegments()) {
            if (existing.pcmJournalName.isEmpty()) {
                cursor = Math.max(cursor, existing.endSample);
                continue;
            }
            File pcm = store.pcmJournalFile(sessionId, existing);
            if (!pcm.isFile() || pcm.length() < 2L) {
                throw new IOException("Durable PCM journal "
                        + existing.pcmJournalName + " is missing or empty");
            }
            if (store.clearVerifiedPcmJournal(sessionId,
                    existing.seq)) {
                cursor = Math.max(cursor, existing.endSample);
                diag(PhoneDiagnostics.INFO,
                        "recovery.pcm_journal_already_committed",
                        sessionId,
                        "A leftover PCM journal was removed after verifying the already-committed MP3 chunk",
                        PhoneDiagnostics.fields("seq", existing.seq,
                                "mp3_bytes", existing.mp3Bytes,
                                "remote_accepted",
                                existing.remoteAccepted));
                continue;
            }
            File target = new File(store.sessionDirectory(sessionId),
                    String.format(java.util.Locale.US,
                            "segment_%06d.mp3", existing.seq));
            mp3.encodeRawPcm(pcm, existing.pcmInputSampleRate,
                    target);
            long inputSamples = pcm.length() / 2L;
            long outputSamples = inputSamples
                    * ReliableSessionManifest.OUTPUT_SAMPLE_RATE
                    / Math.max(1, existing.pcmInputSampleRate);
            long startSample = Math.max(cursor,
                    Math.max(0L, existing.startSample));
            long endSample = startSample + outputSamples;
            long duration = outputSamples * 1000L
                    / ReliableSessionManifest.OUTPUT_SAMPLE_RATE;
            store.markPcmJournalEncoded(sessionId, existing.seq,
                    target, duration, startSample, endSample);
            diag(PhoneDiagnostics.INFO,
                    "recovery.pcm_chunk_encoded", sessionId,
                    "A durable PCM journal was encoded after microphone capture stopped",
                    PhoneDiagnostics.fields("seq", existing.seq,
                            "input_sample_rate",
                            existing.pcmInputSampleRate,
                            "pcm_bytes", pcm.length(),
                            "output_samples", outputSamples,
                            "start_sample", startSample,
                            "end_sample", endSample));
            cursor = endSample;
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

    private void scheduleDeferredStartupRecovery(String reason) {
        main.postDelayed(() -> {
            if (exitRequested.get() || recorder.isRecording() || store == null) return;
            ExecutorService current = conversion;
            try {
                current.execute(() -> {
                    if (exitRequested.get() || recorder.isRecording() || store == null) return;
                    synchronized (fileMaintenanceLock) {
                        if (exitRequested.get() || recorder.isRecording()) return;
                        try {
                            long started = SystemClock.elapsedRealtime();
                            store.recoverAll();
                            normalizeInterruptedSessions();
                            localBytesCached = store.localBytes();
                            lastLocalBytesScanElapsedMs = SystemClock.elapsedRealtime();
                            diag(PhoneDiagnostics.INFO,
                                    "service.deferred_recovery_scan", currentSessionId,
                                    "Private recording storage recovery completed after capture became available",
                                    PhoneDiagnostics.fields("reason", reason,
                                            "local_bytes", localBytesCached,
                                            "duration_ms", Math.max(0L,
                                                    SystemClock.elapsedRealtime() - started)));
                            if (!recorder.isRecording()) {
                                resumeDeferredWork("deferred_recovery_complete");
                                refresh("READY",
                                        "Ready to create a loss-protected recording",
                                        false, "Not recording");
                            }
                        } catch (Exception failure) {
                            diagError("service.deferred_recovery_failed",
                                    currentSessionId,
                                    "Deferred storage recovery", failure,
                                    PhoneDiagnostics.fields("reason", reason));
                        }
                    }
                });
            } catch (RuntimeException failure) {
                diagError("service.deferred_recovery_queue_failed", currentSessionId,
                        "Queueing deferred storage recovery", failure,
                        PhoneDiagnostics.fields("reason", reason));
            }
        }, 5000L);
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
            boolean terminal = humanState.startsWith("Stored completely")
                    || humanState.startsWith("Waiting for network")
                    || humanState.startsWith("Committing")
                    || humanState.startsWith("Reconciling");
            long now = SystemClock.elapsedRealtime();
            if (terminal || now - lastUploaderRefreshElapsedMs >= 500L) {
                String requested = humanState.startsWith("Stored completely")
                        ? "READY" : "SYNCHRONIZING";
                pendingUploaderRefresh.set(new RefreshRequest(
                        requested, humanState, snapshot.routedInput));
                main.removeCallbacks(uploaderRefresh);
                main.post(terminal ? uploaderRefresh : () -> {
                    main.removeCallbacks(uploaderRefresh);
                    main.postDelayed(uploaderRefresh, 250L);
                });
            }
        }

        @Override public void onChanged() {
            pendingUploaderRefresh.set(new RefreshRequest(
                    snapshot.state, snapshot.explanation, snapshot.routedInput));
            main.removeCallbacks(uploaderRefresh);
            main.postDelayed(uploaderRefresh, 350L);
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
        return backgroundWorkCached;
    }

    private void publishImmediateState(String state, String explanation,
                                       String reason) {
        main.post(() -> {
            snapshot = copySnapshotWithState(snapshot, state, explanation);
            VoiceButtonLocalTrace.log(this, "recording.service.immediate_state",
                    "reason", reason,
                    "state", state,
                    "explanation", explanation,
                    "uploader", uploader == null ? "missing" : uploader.debugSummary());
            publish();
            updateNotification();
        });
    }

    private static Snapshot copySnapshotWithState(Snapshot previous,
                                                  String state,
                                                  String explanation) {
        if (previous == null) {
            return new Snapshot(state, explanation, false, false,
                    0L, 0L, -120f, -120f, 0, false,
                    0L, 0L, 0L, 0, 0, 0,
                    "idle", "", -1, 0L, 0L, 0, 0L,
                    "Microphone unavailable", "Not recording",
                    Collections.emptyList(), null, null, null,
                    false, false, "", 0);
        }
        return new Snapshot(state, explanation, previous.recording,
                previous.paused, previous.durationMs, previous.localBytes,
                previous.inputRmsDbfs, previous.inputPeakDbfs,
                previous.inputLevelPermille, previous.inputSignalDetected,
                previous.uploadTotalBytes, previous.uploadDurableBytes,
                previous.uploadPendingBytes, previous.uploadTotalChunks,
                previous.uploadDurableChunks, previous.uploadProgressPermille,
                previous.liveUploadOperation, previous.liveUploadSessionId,
                previous.liveUploadSequence, previous.liveUploadDurableBytes,
                previous.liveUploadTotalBytes, previous.liveUploadProgressPermille,
                previous.liveUploadLastProgressWallMs,
                previous.selectedInput, previous.routedInput,
                previous.sessions, previous.interrupted, previous.openSession,
                previous.currentSessionId, previous.recordingErrorActive,
                previous.recordingErrorAlarmAudible,
                previous.recordingErrorMessage, previous.recordingRecoveryAttempt);
    }

    private void refreshFromWorker(String state, String explanation) {
        refresh(state, explanation, snapshot.recording, snapshot.routedInput);
    }

    private void refresh(String requestedState, String explanation,
                         boolean ignoredRecordingHint, String routedInputHint) {
        pendingRefresh.set(new RefreshRequest(requestedState, explanation, routedInputHint));
        scheduleRefreshWorker();
    }

    private void scheduleRefreshWorker() {
        if (exitRequested.get() || !refreshWorkerRunning.compareAndSet(false, true)) return;
        try {
            statusExecutor.execute(this::drainRefreshRequests);
        } catch (RuntimeException rejected) {
            refreshWorkerRunning.set(false);
        }
    }

    private void drainRefreshRequests() {
        try {
            while (!exitRequested.get()) {
                RefreshRequest request = pendingRefresh.getAndSet(null);
                if (request == null) break;
                try {
                    Snapshot built = buildSnapshot(request);
                    main.post(() -> applySnapshot(built, request));
                } catch (Exception failure) {
                    String message = "Status refresh failed; keeping last usable service state: "
                            + failure.getClass().getSimpleName() + ": "
                            + String.valueOf(failure.getMessage());
                    diagError("service.refresh_failed", snapshot.currentSessionId,
                            "Building service status snapshot", failure,
                            PhoneDiagnostics.fields("requested_state", request.state,
                                    "requested_explanation", request.explanation));
                    main.post(() -> {
                        String fallbackState = "STARTING".equals(snapshot.state)
                                ? "READY" : snapshot.state;
                        snapshot = copySnapshotWithState(snapshot, fallbackState,
                                message);
                        publish();
                        if (shouldKeepServiceAlive()) updateNotification();
                    });
                }
            }
        } finally {
            refreshWorkerRunning.set(false);
            if (pendingRefresh.get() != null && !exitRequested.get()) scheduleRefreshWorker();
        }
    }

    private Snapshot buildSnapshot(RefreshRequest request) {
        Snapshot previous = snapshot;
        boolean actualRecording = recorder.isRecording();
        boolean captureState = actualRecording
                || "RECORDING".equals(request.state)
                || "PREPARING".equals(request.state);
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean scanSessions = store != null && !captureState
                && (lastSessionListScanElapsedMs <= 0L
                || nowElapsed - lastSessionListScanElapsedMs
                >= STATUS_SESSION_SCAN_INTERVAL_MS);
        List<ReliableSessionManifest> sessions = previous.sessions;
        ReliableSessionManifest open = previous.openSession;
        ReliableSessionManifest interrupted = previous.interrupted;
        if (store == null) {
            sessions = Collections.emptyList();
            open = null;
            interrupted = null;
        } else if (scanSessions) {
            try {
                sessions = store.list();
                open = store.latestUnfinished();
                interrupted = store.latestInterrupted();
                lastSessionListScanElapsedMs = nowElapsed;
            } catch (Exception scanFailure) {
                scanSessions = false;
                diagError("service.status_scan_failed", previous.currentSessionId,
                        "Scanning recording status", scanFailure,
                        PhoneDiagnostics.fields("previous_state", previous.state,
                                "session_count", previous.sessions.size()));
            }
        }
        boolean actualPaused = open != null && open.paused && !actualRecording;
        boolean actualInterrupted = open != null && open.isInterrupted() && !actualRecording;
        String state = RecordingStateResolver.normalize(request.state,
                actualRecording, actualPaused, actualInterrupted);
        String explanation = RecordingStateResolver.explanation(state, request.explanation);
        String resolvedSessionId = open != null ? open.sessionId
                : actualRecording ? currentSessionId : null;
        String selected = previous.selectedInput;
        if (resolvedSessionId != null && store != null && !actualRecording) {
            try { selected = store.load(resolvedSessionId).selectedInput; }
            catch (Exception ignored) {}
        }
        long duration = actualRecording
                ? recordingBaseDurationMs
                        + Math.max(0L, SystemClock.elapsedRealtime() - recordingStartedAt)
                : open == null ? 0L : open.totalDurationMs;
        String routedInput = actualRecording
                ? (request.routedInput == null || "Not recording".equals(request.routedInput)
                        ? previous.routedInput : request.routedInput)
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
                } else {
                    uploadDurableBytes += Math.max(0L,
                            Math.min(segment.mp3Bytes, segment.remotePartialBytes));
                }
            }
        }
        ReliableUploader.LiveProgress liveUpload = uploader == null ? null : uploader.liveProgress();
        String liveOperation = liveUpload == null ? "idle" : liveUpload.operation;
        String liveSessionId = liveUpload == null ? "" : liveUpload.sessionId;
        int liveSequence = liveUpload == null ? -1 : liveUpload.sequence;
        long liveDurable = liveUpload == null ? 0L : liveUpload.durableBytes;
        long liveTotal = liveUpload == null ? 0L : liveUpload.totalBytes;
        int livePermille = RecordingFeedback.uploadPermille(liveDurable, liveTotal);
        long liveWall = liveUpload == null ? 0L : liveUpload.lastProgressWallMs;
        if ("upload_chunk".equals(liveOperation) && liveTotal > 0L
                && liveSequence >= 0 && !liveSessionId.isEmpty()) {
            long persisted = persistedUploadBytes(sessions, liveSessionId,
                    liveSequence);
            long liveDelta = Math.max(0L,
                    Math.min(liveTotal, liveDurable) - persisted);
            if (liveDelta > 0L) uploadDurableBytes += liveDelta;
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
        if (store != null && !actualRecording) {
            if (lastLocalBytesScanElapsedMs <= 0L) {
                lastLocalBytesScanElapsedMs = nowElapsed;
            } else if (nowElapsed - lastLocalBytesScanElapsedMs >= 30000L) {
                try {
                    localBytesCached = store.localBytes();
                    lastLocalBytesScanElapsedMs = nowElapsed;
                } catch (Exception bytesFailure) {
                    diagError("service.local_bytes_scan_failed",
                            previous.currentSessionId,
                            "Scanning local recording byte count", bytesFailure,
                            PhoneDiagnostics.fields("previous_state", previous.state));
                }
            }
        }
        long localBytes = localBytesCached;
        if (store == null) pendingFolderSyncCached = false;
        else if (scanSessions) {
            try { pendingFolderSyncCached = store.hasPendingFolderSync(); }
            catch (Exception folderFailure) {
                diagError("service.folder_sync_status_failed",
                        previous.currentSessionId,
                        "Scanning folder synchronization state", folderFailure,
                        PhoneDiagnostics.fields("previous_state", previous.state));
            }
        }
        return new Snapshot(state, explanation, actualRecording, actualPaused,
                duration, localBytes, rmsDbfs, peakDbfs,
                inputLevelPermille, inputSignalDetected,
                uploadTotalBytes, uploadDurableBytes, uploadPendingBytes,
                uploadTotalChunks, uploadDurableChunks, uploadProgressPermille,
                liveOperation, liveSessionId, liveSequence, liveDurable, liveTotal,
                livePermille, liveWall,
                selected, routedInput, sessions, interrupted, open, resolvedSessionId,
                failureAlarm.isActive(), failureAlarm.isAudible(),
                failureAlarm.getMessage(), recordingRecoveryAttempt);
    }

    private static long persistedUploadBytes(List<ReliableSessionManifest> sessions,
                                             String sessionId, int sequence) {
        if (sessions == null || sessionId == null || sessionId.isEmpty()) return 0L;
        for (ReliableSessionManifest manifest : sessions) {
            if (manifest == null || !sessionId.equals(manifest.sessionId)) continue;
            ReliableSessionManifest.Segment segment = manifest.findSegment(sequence);
            if (segment == null) return 0L;
            if (segment.remoteAccepted) return Math.max(0L, segment.mp3Bytes);
            return Math.max(0L, Math.min(segment.mp3Bytes,
                    segment.remotePartialBytes));
        }
        return 0L;
    }

    private void applySnapshot(Snapshot built, RefreshRequest request) {
        if (exitRequested.get()) return;
        snapshot = built;
        currentSessionId = built.currentSessionId;
        backgroundWorkCached = pendingFolderSyncCached || computeBackgroundWork(built.sessions);
        boolean stateChanged = !built.state.equals(lastLoggedState)
                || !built.explanation.equals(lastLoggedExplanation);
        if (stateChanged) {
            lastLoggedState = built.state;
            lastLoggedExplanation = built.explanation;
            diag(PhoneDiagnostics.INFO, "service.state", currentSessionId,
                    built.explanation, PhoneDiagnostics.fields("state", built.state,
                            "requested_state", request.state,
                            "recording", built.recording,
                            "paused", built.paused,
                            "duration_ms", built.durationMs,
                            "local_bytes", built.localBytes,
                            "session_count", built.sessions.size(),
                            "routed_input", built.routedInput));
        }
        publish();
        long now = SystemClock.elapsedRealtime();
        if (shouldKeepServiceAlive()) {
            ensureForeground();
            if (stateChanged || now - lastNotificationElapsedMs >= NOTIFICATION_REFRESH_MS) {
                lastNotificationElapsedMs = now;
                updateNotification();
            }
        } else if (!built.recording && !isCriticalForegroundState(built.state)) {
            leaveForegroundIfIdle();
        }
    }

    private void publishLiveTelemetry() {
        Snapshot previous = snapshot;
        if (!recorder.isRecording()) return;
        long now = SystemClock.elapsedRealtime();
        long duration = recordingBaseDurationMs
                + Math.max(0L, now - recordingStartedAt);
        long levelAgeMs = liveInputLevelAtElapsedMs <= 0L ? Long.MAX_VALUE
                : Math.max(0L, now - liveInputLevelAtElapsedMs);
        float rmsDbfs = levelAgeMs < 1500L ? liveInputRmsDbfs : -120f;
        float peakDbfs = levelAgeMs < 1500L ? liveInputPeakDbfs : -120f;
        Snapshot live = new Snapshot(previous.state, previous.explanation,
                true, false, duration, previous.localBytes,
                rmsDbfs, peakDbfs, RecordingFeedback.levelPermille(peakDbfs),
                levelAgeMs < 1500L && peakDbfs > -50f,
                previous.uploadTotalBytes, previous.uploadDurableBytes,
                previous.uploadPendingBytes, previous.uploadTotalChunks,
                previous.uploadDurableChunks, previous.uploadProgressPermille,
                previous.liveUploadOperation, previous.liveUploadSessionId,
                previous.liveUploadSequence, previous.liveUploadDurableBytes,
                previous.liveUploadTotalBytes, previous.liveUploadProgressPermille,
                previous.liveUploadLastProgressWallMs,
                previous.selectedInput, previous.routedInput, previous.sessions,
                previous.interrupted, previous.openSession, previous.currentSessionId,
                previous.recordingErrorActive, previous.recordingErrorAlarmAudible,
                previous.recordingErrorMessage, previous.recordingRecoveryAttempt);
        snapshot = live;
        if (!listeners.isEmpty()) publish();
    }

    private static boolean computeBackgroundWork(List<ReliableSessionManifest> sessions) {
        for (ReliableSessionManifest manifest : sessions) {
            boolean pendingAudio = false;
            boolean pendingTranscript = false;
            for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                if (!segment.remoteAccepted) pendingAudio = true;
                if (!"COMPLETE".equals(segment.transcriptState)) pendingTranscript = true;
            }
            if (RecordingContinuityPolicy.sessionNeedsSynchronization(
                    manifest.recordingFinished, manifest.conversionFinished,
                    manifest.remoteCommitted, pendingAudio, pendingTranscript)) return true;
        }
        return false;
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

    private boolean shouldKeepServiceAlive() {
        return serviceInitializing || RecordingContinuityPolicy.keepServiceAlive(
                recorder.isRecording(), hasBackgroundWork(),
                recordingRecoveryPending, failureAlarm.isActive());
    }

    private void startFailureIncident(String sessionId, String detail) {
        failureAlarm.start(detail);
        ensureForeground();
        acquireCaptureWakeLock();
        diag(PhoneDiagnostics.ERROR, "recording.failure_alarm_started", sessionId,
                detail, PhoneDiagnostics.fields(
                        "audible", failureAlarm.isAudible(),
                        "recovery_attempt", recordingRecoveryAttempt));
        publish();
        updateNotification();
    }

    private void silenceFailureAlarm() {
        failureAlarm.silence();
        diag(PhoneDiagnostics.WARN, "recording.failure_alarm_silenced",
                currentSessionId, "The repeating recording failure alarm was silenced; automatic recovery continues",
                PhoneDiagnostics.fields("recovery_pending", recordingRecoveryPending,
                        "recovery_attempt", recordingRecoveryAttempt));
        refresh(snapshot.state, snapshot.explanation, snapshot.recording, snapshot.routedInput);
    }

    private void scheduleAutomaticRecovery(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || exitRequested.get()) return;
        recordingRecoveryPending = true;
        recordingRecoverySessionId = sessionId;
        main.removeCallbacks(recordingRecovery);
        long delay = RecordingContinuityPolicy.recoveryDelayMs(recordingRecoveryAttempt);
        main.postDelayed(recordingRecovery, delay);
        ensureForeground();
        acquireCaptureWakeLock();
    }

    private void cancelAutomaticRecovery(boolean resolved) {
        main.removeCallbacks(recordingRecovery);
        recordingRecoveryPending = false;
        recordingRecoverySessionId = "";
        recordingRecoveryDetail = "";
        recordingRecoveryAttempt = 0;
        if (resolved) failureAlarm.resolve();
        if (!shouldKeepServiceAlive()) releaseCaptureWakeLock();
        updateNotification();
    }

    private void registerNetworkCallback() {
        if (networkCallback != null) return;
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                ReliableUploader value = uploader;
                if (value != null) value.onNetworkChanged();
                signalUploader("network_available");
                if (shouldKeepServiceAlive()) {
                    ensureForeground();
                    acquireCaptureWakeLock();
                }
                diag(PhoneDiagnostics.INFO, "network.available", null,
                        "A network became available; queued chunks were signaled immediately",
                        PhoneDiagnostics.fields());
            }
            @Override public void onLost(Network network) {
                ReliableUploader value = uploader;
                if (value != null) value.onNetworkChanged();
                diag(PhoneDiagnostics.WARN, "network.lost", null,
                        "A network was lost; every unconfirmed chunk remains queued",
                        PhoneDiagnostics.fields());
            }
        };
        try { connectivityManager.registerDefaultNetworkCallback(networkCallback); }
        catch (RuntimeException failure) {
            diagError("network.callback_failed", null,
                    "Registering network availability callback", failure,
                    PhoneDiagnostics.fields());
            networkCallback = null;
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager manager = connectivityManager;
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        connectivityManager = null;
        if (manager != null && callback != null) {
            try { manager.unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) {}
        }
    }

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
            lastNotificationElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    private void leaveForegroundIfIdle() {
        if (shouldKeepServiceAlive() || isCriticalForegroundState(snapshot.state)) return;
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
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
        String notificationChannel = failureAlarm.isActive() ? ERROR_CHANNEL_ID : CHANNEL_ID;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, notificationChannel)
                .setSmallIcon(R.drawable.ic_voice_button)
                .setContentTitle(failureAlarm.isActive() ? "RECORDING INTERRUPTED"
                        : snapshot.recording ? "Reliable recording is active"
                        : snapshot.paused ? "Recording is paused"
                        : hasBackgroundWork() ? "Synchronizing recorded audio"
                        : "Reliable audio storage")
                .setContentText(snapshot.explanation)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(snapshot.explanation))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(shouldKeepServiceAlive() || isCriticalForegroundState(snapshot.state))
                .setCategory(failureAlarm.isActive()
                        ? NotificationCompat.CATEGORY_ERROR : NotificationCompat.CATEGORY_SERVICE)
                .setPriority(failureAlarm.isActive()
                        ? NotificationCompat.PRIORITY_MAX : NotificationCompat.PRIORITY_LOW);
        if (failureAlarm.isActive() && failureAlarm.isAudible()) {
            Intent silence = new Intent(RecordingService.this, RecordingService.class)
                    .setAction(ACTION_SILENCE_ALARM);
            PendingIntent silenceIntent = PendingIntent.getService(RecordingService.this, 9, silence,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "Silence alarm", silenceIntent);
        }
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
        NotificationChannel errorChannel = new NotificationChannel(ERROR_CHANNEL_ID,
                "Recording interruption alarm", NotificationManager.IMPORTANCE_HIGH);
        errorChannel.setDescription("Urgent repeating alert when microphone recording stops unexpectedly");
        errorChannel.enableVibration(true);
        manager.createNotificationChannel(errorChannel);
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
        main.removeCallbacks(continuityTicker);
        main.removeCallbacks(recordingRecovery);
        main.removeCallbacks(uploaderRefresh);
        failureAlarm.release();
        unregisterNetworkCallback();
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
        if (recorder.isRecording()) {
            stopDisposition = "task_removed".equals(reason)
                    ? STOP_PAUSE : STOP_INTERRUPT;
        }
        recorder.stop();
        // Data safety has priority over making the task disappear instantly.
        // Keep foreground execution and the wake lock until the journal has
        // flushed, synchronized, and atomically closed.
        boolean recorderStopped = recorder.awaitStopped(30_000L);
        if (!recorderStopped) {
            diag(PhoneDiagnostics.ERROR,
                    "recording.exit_journal_close_timeout", currentSessionId,
                    "Explicit close reached thirty seconds before the PCM journal thread stopped; the open journal remains recoverable",
                    PhoneDiagnostics.fields("reason", reason));
        }
        releaseCaptureWakeLock();
        ReliableUploader currentUploader = uploader;
        if (currentUploader != null) currentUploader.stop();
        ExecutorService currentConversion = conversion;
        currentConversion.shutdownNow();
        Thread maintenance = maintenanceThread;
        if (maintenance != null) maintenance.interrupt();

        boolean uploaderStopped = currentUploader == null
                || currentUploader.awaitStopped(5000L);
        boolean conversionStopped = false;
        try {
            conversionStopped = currentConversion.awaitTermination(
                    5000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        NotificationManager manager = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
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
        diag(PhoneDiagnostics.WARN, "service.task_removed", currentSessionId,
                "The app task was swiped away; recording is being paused and all background work is stopping",
                PhoneDiagnostics.fields("recording", recorder.isRecording(),
                        "background_work", hasBackgroundWork(),
                        "recovery_pending", recordingRecoveryPending,
                        "alarm_active", failureAlarm.isActive()));
        try {
            Thread closeThread = new Thread(
                    () -> shutdownForUserExit("task_removed"),
                    "voicebutton-recording-task-close");
            closeThread.setDaemon(false);
            closeThread.start();
        } catch (RuntimeException failure) {
            stopSelf();
        }
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
        boolean pendingUpload = hasBackgroundWork();
        diag(PhoneDiagnostics.WARN, "service.destroy", currentSessionId,
                "RecordingService onDestroy entered",
                PhoneDiagnostics.fields("recording", recorder.isRecording(),
                        "snapshot_state", snapshot.state,
                        "exit_requested", exitRequested.get()));
        main.removeCallbacks(ticker);
        main.removeCallbacks(continuityTicker);
        main.removeCallbacks(recordingRecovery);
        main.removeCallbacks(uploaderRefresh);
        failureAlarm.release();
        unregisterNetworkCallback();
        if (recorder.isRecording()) {
            stopDisposition = exitRequested.get() ? STOP_PAUSE : STOP_INTERRUPT;
        }
        recorder.stop();
        boolean recorderStopped = recorder.awaitStopped(5000L);
        if (!recorderStopped) {
            diag(PhoneDiagnostics.ERROR,
                    "recording.destroy_journal_close_timeout",
                    currentSessionId,
                    "Service destruction reached five seconds before the PCM journal thread stopped; startup recovery will use the open journal",
                    PhoneDiagnostics.fields());
        }
        releaseCaptureWakeLock();
        if (uploader != null) uploader.stop();
        conversion.shutdownNow();
        maintenanceExecutor.shutdownNow();
        statusExecutor.shutdownNow();
        serviceExecutor.shutdownNow();
        Thread maintenance = maintenanceThread;
        if (maintenance != null) maintenance.interrupt();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        UploadWorkCoordinator.markServiceStopped();
        if (pendingUpload) UploadWorkScheduler.enqueue(this, "service_destroyed");
        super.onDestroy();
    }
}
