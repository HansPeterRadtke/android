package com.hans.android.voicebutton;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerPlaybackService extends Service
        implements VlcAudioPlayer.Listener {
    static final String ACTION_ACTIVATE =
            "com.hans.android.voicebutton.PLAYER_ACTIVATE";
    static final String ACTION_RESTORE =
            "com.hans.android.voicebutton.PLAYER_RESTORE";
    static final String ACTION_PLAY =
            "com.hans.android.voicebutton.PLAYER_PLAY";
    static final String ACTION_PAUSE =
            "com.hans.android.voicebutton.PLAYER_PAUSE";
    static final String ACTION_PREVIOUS =
            "com.hans.android.voicebutton.PLAYER_PREVIOUS";
    static final String ACTION_NEXT =
            "com.hans.android.voicebutton.PLAYER_NEXT";
    static final String ACTION_REWIND =
            "com.hans.android.voicebutton.PLAYER_REWIND";
    static final String ACTION_FORWARD =
            "com.hans.android.voicebutton.PLAYER_FORWARD";
    static final String ACTION_CLOSE =
            "com.hans.android.voicebutton.PLAYER_CLOSE";

    private static final String CHANNEL_ID = "voicebutton_player_controls";
    private static final int NOTIFICATION_ID = 7021;
    private static final long CHECKPOINT_INTERVAL_MS =
            PlayerLifecyclePolicy.checkpointIntervalMs();

    interface Listener { void onPlayerSnapshot(Snapshot snapshot); }

    static final class Snapshot {
        final String state;
        final String error;
        final boolean playing;
        final boolean seekable;
        final long physicalTimeMs;
        final long physicalLengthMs;
        final float rate;
        final PlayerSource originalSource;
        final PlayerSource activeSource;
        final boolean studioActive;
        final float studioSpeed;
        final int queueIndex;
        final int queueSize;
        final String engineSummary;

        Snapshot(String state, String error, boolean playing, boolean seekable,
                 long physicalTimeMs, long physicalLengthMs, float rate,
                 PlayerSource originalSource, PlayerSource activeSource,
                 boolean studioActive, float studioSpeed,
                 int queueIndex, int queueSize, String engineSummary) {
            this.state = state == null ? "idle" : state;
            this.error = error == null ? "" : error;
            this.playing = playing;
            this.seekable = seekable;
            this.physicalTimeMs = Math.max(0L, physicalTimeMs);
            this.physicalLengthMs = Math.max(0L, physicalLengthMs);
            this.rate = Math.max(.01f, rate);
            this.originalSource = originalSource;
            this.activeSource = activeSource;
            this.studioActive = studioActive;
            this.studioSpeed = Math.max(.01f, studioSpeed);
            this.queueIndex = queueIndex;
            this.queueSize = Math.max(0, queueSize);
            this.engineSummary = engineSummary == null ? "starting" : engineSummary;
        }

        long logicalTimeMs() {
            return PlayerTimeline.logicalTime(
                    physicalTimeMs, studioActive, studioSpeed);
        }

        long logicalLengthMs() {
            return PlayerTimeline.logicalLength(
                    physicalLengthMs, studioActive, studioSpeed);
        }

        static Snapshot initial() {
            return new Snapshot("idle", "", false, false,
                    0L, 0L, 1f, null, null,
                    false, 1f, -1, 0, "starting");
        }
    }

    final class LocalBinder extends Binder {
        PlayerPlaybackService service() { return PlayerPlaybackService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<>();
    private final ArrayList<PlayerSource> queue = new ArrayList<>();
    private final ExecutorService checkpointExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable,
                        "voicebutton-player-checkpoint");
                thread.setDaemon(false);
                return thread;
            });

    private VlcAudioPlayer player;
    private MediaSession mediaSession;
    private PlayerCheckpointStore checkpointStore;
    private PlayerSettings settings;
    private PlayerSource originalSource;
    private PlayerSource activeSource;
    private int queueIndex = -1;
    private boolean studioActive;
    private float studioSpeed = 1f;
    private float instantSpeed = 1f;
    private int volume = 100;
    private boolean muted;
    private boolean loop;
    private float skipBack = 10f;
    private float skipForward = 10f;
    private boolean autoplay;
    private boolean foreground;
    private boolean closing;
    private int startAttemptGeneration;
    private volatile Snapshot snapshot = Snapshot.initial();

    private final Runnable checkpointTicker = new Runnable() {
        @Override public void run() {
            if (activeSource == null || closing) return;
            saveCheckpointAsync(player != null && player.isPlaying());
            updateMediaSession();
            publish();
            main.postDelayed(this, CHECKPOINT_INTERVAL_MS);
        }
    };

    private boolean noisyReceiverRegistered;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
                PhoneDiagnostics diagnostics = PhoneDiagnostics.get();
                if (diagnostics != null) diagnostics.log(PhoneDiagnostics.WARN,
                        "player.output_disconnected", null,
                        "Playback paused because the audio output disconnected",
                        PhoneDiagnostics.fields());
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        player = new VlcAudioPlayer(this, this);
        initializeMediaSession();
        ContextCompat.registerReceiver(this, noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        noisyReceiverRegistered = true;
        checkpointExecutor.execute(() -> {
            checkpointStore = new PlayerCheckpointStore(this);
            settings = new PlayerSettings(this);
            main.post(this::copySettingsFromPreferences);
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_ACTIVATE : intent.getAction();
        if (ACTION_ACTIVATE.equals(action)) {
            promoteImmediately();
        } else if (ACTION_RESTORE.equals(action)) {
            promoteImmediately();
            restoreCheckpointAsync();
        } else if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_PREVIOUS.equals(action)) changeQueue(-1);
        else if (ACTION_NEXT.equals(action)) changeQueue(1);
        else if (ACTION_REWIND.equals(action)) skip(-skipBack);
        else if (ACTION_FORWARD.equals(action)) skip(skipForward);
        else if (ACTION_CLOSE.equals(action)) closeAsync(false);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    void addListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onPlayerSnapshot(currentSnapshot());
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    boolean hasSource() { return activeSource != null; }

    Snapshot currentSnapshot() {
        VlcAudioPlayer value = player;
        return new Snapshot(snapshot.state, snapshot.error,
                value != null && value.isPlaying(),
                value != null && value.isSeekable(),
                value == null ? snapshot.physicalTimeMs : value.time(),
                value == null ? snapshot.physicalLengthMs : value.length(),
                value == null ? snapshot.rate : value.rate(),
                originalSource, activeSource, studioActive, studioSpeed,
                queueIndex, queue.size(),
                value == null ? snapshot.engineSummary : value.technicalSummary());
    }

    void open(PlayerSource original, PlayerSource active,
              List<PlayerSource> requestedQueue, int requestedIndex,
              long logicalPositionMs, boolean shouldPlay,
              boolean requestedStudio, float requestedStudioSpeed,
              float requestedInstantSpeed, int requestedVolume,
              boolean requestedMuted, boolean requestedLoop,
              float requestedSkipBack, float requestedSkipForward,
              boolean requestedAutoplay) {
        if (original == null || active == null || closing) return;
        originalSource = original;
        activeSource = active;
        studioActive = requestedStudio;
        studioSpeed = Math.max(.01f, requestedStudioSpeed);
        instantSpeed = Math.max(.01f, requestedInstantSpeed);
        volume = Math.max(0, Math.min(100, requestedVolume));
        muted = requestedMuted;
        loop = requestedLoop;
        skipBack = Math.max(.1f, requestedSkipBack);
        skipForward = Math.max(.1f, requestedSkipForward);
        autoplay = requestedAutoplay;
        queue.clear();
        if (requestedQueue != null) queue.addAll(requestedQueue);
        queueIndex = requestedIndex;
        promoteImmediately();
        long physical = PlayerTimeline.physicalTime(logicalPositionMs,
                studioActive, studioSpeed);
        float playbackRate = studioActive ? 1f : instantSpeed;
        player.openAt(activeSource.uri, physical, shouldPlay,
                playbackRate, volume, muted, loop);
        snapshot = new Snapshot("opening", "", shouldPlay, false,
                physical, 0L, playbackRate, originalSource, activeSource,
                studioActive, studioSpeed, queueIndex, queue.size(),
                player.technicalSummary());
        scheduleStartTimeout("opening audio");
        restartCheckpointTicker();
        saveCheckpointAsync(shouldPlay);
        publish();
        updateMediaSession();
        updateNotification();
    }

    void setSpeed(float speed) {
        instantSpeed = Math.max(.01f, speed);
        if (!studioActive) player.setSpeed(instantSpeed);
        saveCheckpointAsync(player.isPlaying());
        publish();
    }

    void setVolume(int requestedVolume, boolean requestedMuted) {
        volume = Math.max(0, Math.min(100, requestedVolume));
        muted = requestedMuted;
        player.setVolume(volume, muted);
    }

    void setLoop(boolean requestedLoop) {
        loop = requestedLoop;
        player.setLoop(loop);
    }

    void updateSkipValues(float backward, float forward) {
        skipBack = Math.max(.1f, backward);
        skipForward = Math.max(.1f, forward);
    }

    void previous() { changeQueue(-1); }
    void next() { changeQueue(1); }

    void playPause() {
        PlayerControlState controls = PlayerControlState.from(currentSnapshot());
        if (!controls.playEnabled) return;
        if (player.isPlaying()) pause(); else play();
    }

    void play() {
        PlayerControlState controls = PlayerControlState.from(currentSnapshot());
        if (activeSource == null || !controls.playEnabled) return;
        promoteImmediately();
        if (PlayerTerminalPolicy.restartFromBeginning(snapshot.state,
                player.time(), player.length())) {
            player.seek(0L);
        }
        snapshot = new Snapshot("starting playback", "", false,
                player.isSeekable(), player.time(), player.length(),
                player.rate(), originalSource, activeSource,
                studioActive, studioSpeed, queueIndex, queue.size(),
                player.technicalSummary());
        publish(); updateMediaSession(); updateNotification();
        player.play();
        scheduleStartTimeout("starting playback");
        saveCheckpointAsync(true);
    }

    void pause() {
        PlayerControlState controls = PlayerControlState.from(currentSnapshot());
        if (player == null || !controls.playEnabled) return;
        snapshot = new Snapshot("pausing", "", true,
                player.isSeekable(), player.time(), player.length(),
                player.rate(), originalSource, activeSource,
                studioActive, studioSpeed, queueIndex, queue.size(),
                player.technicalSummary());
        publish(); updateMediaSession(); updateNotification();
        player.pause();
        saveCheckpointAsync(false);
    }

    void stopPlayback() {
        if (player == null) return;
        player.stop();
        saveCheckpointAsync(false);
    }

    void seekPhysical(long physicalTimeMs) {
        if (player == null) return;
        player.seek(physicalTimeMs);
        saveCheckpointAsync(player.isPlaying());
    }

    void seekLogical(long logicalTimeMs) {
        seekPhysical(PlayerTimeline.physicalTime(logicalTimeMs,
                studioActive, studioSpeed));
    }

    void skip(float seconds) {
        if (player == null) return;
        player.skip(seconds);
        saveCheckpointAsync(player.isPlaying());
    }

    private void changeQueue(int delta) {
        int target = queueIndex + delta;
        if (target < 0 || target >= queue.size()) return;
        boolean shouldPlay = autoplay || player.isPlaying();
        queueIndex = target;
        PlayerSource source = queue.get(target);
        open(source, source, new ArrayList<>(queue), queueIndex,
                0L, shouldPlay, false, 1f, instantSpeed,
                volume, muted, loop, skipBack, skipForward, autoplay);
    }

    @Override public void onState(String state) {
        if (PlayerTerminalPolicy.ignoreStateAfterError(snapshot.error, state)) return;
        if (!PlayerTerminalPolicy.startIsPending(state)) cancelStartTimeout();
        snapshot = new Snapshot(state, "", player.isPlaying(),
                player.isSeekable(), player.time(), player.length(),
                player.rate(), originalSource, activeSource,
                studioActive, studioSpeed, queueIndex, queue.size(),
                player.technicalSummary());
        if ("ended".equals(state) && autoplay) {
            changeQueue(1);
            return;
        }
        saveCheckpointAsync(player.isPlaying());
        updateMediaSession();
        updateNotification();
        publish();
    }

    @Override public void onPosition(long timeMs, long lengthMs) {
        snapshot = new Snapshot(snapshot.state, snapshot.error,
                player.isPlaying(), player.isSeekable(), timeMs, lengthMs,
                player.rate(), originalSource, activeSource,
                studioActive, studioSpeed, queueIndex, queue.size(),
                player.technicalSummary());
        publish();
    }

    @Override public void onError(String detail) {
        cancelStartTimeout();
        snapshot = new Snapshot("error", detail, false, false,
                player.time(), player.length(), player.rate(),
                originalSource, activeSource, studioActive, studioSpeed,
                queueIndex, queue.size(), player.technicalSummary());
        saveCheckpointAsync(false);
        updateMediaSession();
        updateNotification();
        publish();
        PhoneDiagnostics diagnostics = PhoneDiagnostics.get();
        if (diagnostics != null) diagnostics.log(PhoneDiagnostics.ERROR,
                "player.service_error", null, detail,
                PhoneDiagnostics.fields("source",
                        originalSource == null ? "" : originalSource.title,
                        "engine", player.technicalSummary()));
    }

    private void scheduleStartTimeout(String operation) {
        int generation = ++startAttemptGeneration;
        main.postDelayed(() -> {
            if (generation != startAttemptGeneration || closing) return;
            Snapshot value = snapshot;
            if (!PlayerTerminalPolicy.startIsPending(value.state)) return;
            String detail = "Playback did not finish " + operation
                    + " within 15 seconds";
            PhoneDiagnostics diagnostics = PhoneDiagnostics.get();
            if (diagnostics != null) diagnostics.log(PhoneDiagnostics.ERROR,
                    "player.start_timeout", null, detail,
                    PhoneDiagnostics.fields("state", value.state,
                            "source", originalSource == null ? ""
                                    : originalSource.title,
                            "time_ms", value.physicalTimeMs,
                            "length_ms", value.physicalLengthMs,
                            "engine", value.engineSummary));
            onError(detail);
        }, 15000L);
    }

    private void cancelStartTimeout() {
        startAttemptGeneration++;
    }

    private void restoreCheckpointAsync() {
        checkpointExecutor.execute(() -> {
            PlayerCheckpointStore store = checkpointStore;
            if (store == null) {
                store = new PlayerCheckpointStore(this);
                checkpointStore = store;
            }
            PlayerCheckpoint checkpoint = store.load();
            main.post(() -> restoreCheckpoint(checkpoint));
        });
    }

    private void restoreCheckpoint(PlayerCheckpoint checkpoint) {
        if (closing || checkpoint == null || !checkpoint.hasSource()
                || activeSource != null) {
            if (activeSource == null) demoteIfIdle();
            return;
        }
        PlayerSource active = checkpoint.active;
        boolean restoredStudio = checkpoint.studioActive;
        if (restoredStudio && "file".equalsIgnoreCase(active.uri.getScheme())
                && active.uri.getPath() != null
                && !new File(active.uri.getPath()).isFile()) {
            active = checkpoint.original;
            restoredStudio = false;
        }
        PlayerSettings currentSettings = settings;
        int restoredVolume = currentSettings == null ? volume : currentSettings.volume;
        boolean restoredMuted = currentSettings != null && currentSettings.muted;
        boolean restoredLoop = currentSettings != null && currentSettings.loop;
        float restoredBack = currentSettings == null ? skipBack : currentSettings.skipBack;
        float restoredForward = currentSettings == null ? skipForward : currentSettings.skipForward;
        boolean restoredAutoplay = currentSettings != null && currentSettings.autoplay;
        open(checkpoint.original, active, checkpoint.queue,
                checkpoint.queueIndex, checkpoint.logicalPositionMs,
                checkpoint.resumeOnOpen, restoredStudio,
                restoredStudio ? checkpoint.studioSpeed : 1f,
                checkpoint.instantSpeed, restoredVolume, restoredMuted,
                restoredLoop, restoredBack, restoredForward, restoredAutoplay);
    }

    private void copySettingsFromPreferences() {
        PlayerSettings current = settings;
        if (current == null) return;
        volume = current.volume;
        muted = current.muted;
        loop = current.loop;
        skipBack = current.skipBack;
        skipForward = current.skipForward;
        autoplay = current.autoplay;
    }

    private PlayerCheckpoint checkpoint(boolean resumeOnOpen) {
        Snapshot value = currentSnapshot();
        return new PlayerCheckpoint(originalSource, activeSource,
                new ArrayList<>(queue), queueIndex,
                value.logicalTimeMs(), resumeOnOpen,
                studioActive, studioSpeed, instantSpeed,
                System.currentTimeMillis());
    }

    private void saveCheckpointAsync(boolean resumeOnOpen) {
        if (activeSource == null || closing) return;
        PlayerCheckpoint value = checkpoint(resumeOnOpen);
        checkpointExecutor.execute(() -> saveCheckpoint(value));
    }

    private void saveCheckpoint(PlayerCheckpoint value) {
        try {
            PlayerCheckpointStore store = checkpointStore;
            if (store == null) {
                store = new PlayerCheckpointStore(this);
                checkpointStore = store;
            }
            store.save(value);
        } catch (Exception failure) {
            PhoneDiagnostics diagnostics = PhoneDiagnostics.get();
            if (diagnostics != null) diagnostics.error(
                    "player.checkpoint_failed", null,
                    "Saving player checkpoint", failure,
                    PhoneDiagnostics.fields());
        }
    }

    private void restartCheckpointTicker() {
        main.removeCallbacks(checkpointTicker);
        main.postDelayed(checkpointTicker, CHECKPOINT_INTERVAL_MS);
    }

    private void publish() {
        Snapshot value = currentSnapshot();
        snapshot = value;
        for (Listener listener : listeners) listener.onPlayerSnapshot(value);
    }

    private void promoteImmediately() {
        if (foreground) return;
        startForeground(NOTIFICATION_ID, buildNotification());
        foreground = true;
    }

    private void demoteIfIdle() {
        if (activeSource != null || !foreground) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent content = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 700,
                content, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = player != null && player.isPlaying();
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_voice_button)
                .setContentTitle(originalSource == null
                        ? "Voice Button player" : originalSource.title)
                .setContentText(activeSource == null
                        ? "Opening player" : playing ? "Playing" : "Paused")
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(notificationAction(R.drawable.ic_player_previous,
                        "Previous", ACTION_PREVIOUS, 701))
                .addAction(notificationAction(R.drawable.ic_player_rewind,
                        "Rewind", ACTION_REWIND, 702))
                .addAction(notificationAction(playing
                                ? R.drawable.ic_player_pause
                                : R.drawable.ic_player_play,
                        playing ? "Pause" : "Play",
                        playing ? ACTION_PAUSE : ACTION_PLAY, 703))
                .addAction(notificationAction(R.drawable.ic_player_forward,
                        "Forward", ACTION_FORWARD, 704))
                .addAction(notificationAction(R.drawable.ic_player_next,
                        "Next", ACTION_NEXT, 705))
                .addAction(notificationAction(R.drawable.ic_player_close,
                        "Close", ACTION_CLOSE, 706));
        if (mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(1, 2, 3));
        }
        return builder.build();
    }

    private Notification.Action notificationAction(int icon,
                                                   String title,
                                                   String action,
                                                   int requestCode) {
        return new Notification.Action.Builder(icon, title,
                serviceIntent(action, requestCode)).build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlayerPlaybackService.class)
                .setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void initializeMediaSession() {
        mediaSession = new MediaSession(this, "VoiceButtonPlayerService");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { closeAsync(false); }
            @Override public void onSeekTo(long position) { seekLogical(position); }
            @Override public void onSkipToNext() { changeQueue(1); }
            @Override public void onSkipToPrevious() { changeQueue(-1); }
            @Override public void onFastForward() { skip(skipForward); }
            @Override public void onRewind() { skip(-skipBack); }
        });
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        Snapshot value = currentSnapshot();
        if (originalSource != null) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE,
                            originalSource.title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                            originalSource.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST,
                            "Voice Button")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION,
                            value.logicalLengthMs())
                    .build());
        }
        int state = value.playing ? PlaybackState.STATE_PLAYING
                : value.state.startsWith("buffering")
                || "opening".equals(value.state)
                ? PlaybackState.STATE_BUFFERING
                : "stopped".equals(value.state)
                || "ended".equals(value.state)
                ? PlaybackState.STATE_STOPPED
                : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, value.logicalTimeMs(),
                        value.studioActive ? value.studioSpeed : value.rate,
                        SystemClock.elapsedRealtime())
                .build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Player controls", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Playback controls while the Voice Button task is open");
        manager.createNotificationChannel(channel);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        closeAsync(PlayerLifecyclePolicy.resumeAfterTaskRemoval(
                player != null && player.isPlaying()));
        super.onTaskRemoved(rootIntent);
    }

    private void closeAsync(boolean resumeOnNextOpen) {
        if (closing) return;
        closing = true;
        main.removeCallbacks(checkpointTicker);
        PlayerCheckpoint finalCheckpoint =
                PlayerLifecyclePolicy.persistCheckpointOnClose(activeSource != null)
                ? checkpoint(resumeOnNextOpen) : null;
        Thread closeThread = new Thread(() -> {
            if (finalCheckpoint != null) saveCheckpoint(finalCheckpoint);
            main.post(this::finishClose);
        }, "voicebutton-player-task-close");
        closeThread.setDaemon(false);
        closeThread.start();
    }

    private void finishClose() {
        if (player != null) player.stop();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    @Override public void onDestroy() {
        main.removeCallbacks(checkpointTicker);
        if (!closing && activeSource != null) {
            saveCheckpointAsync(player != null && player.isPlaying());
        }
        if (noisyReceiverRegistered) {
            try { unregisterReceiver(noisyReceiver); }
            catch (IllegalArgumentException ignored) {}
            noisyReceiverRegistered = false;
        }
        if (player != null) player.release();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        checkpointExecutor.shutdown();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
