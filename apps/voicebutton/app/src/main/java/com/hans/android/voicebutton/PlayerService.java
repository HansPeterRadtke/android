package com.hans.android.voicebutton;

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

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerService extends Service implements VlcAudioPlayer.Listener {
    static final String ACTION_START = "com.hans.android.voicebutton.PLAYER_START";
    static final String ACTION_PLAY_PAUSE = "com.hans.android.voicebutton.PLAYER_PLAY_PAUSE";
    static final String ACTION_PLAY = "com.hans.android.voicebutton.PLAYER_PLAY";
    static final String ACTION_PAUSE = "com.hans.android.voicebutton.PLAYER_PAUSE";
    static final String ACTION_PREVIOUS = "com.hans.android.voicebutton.PLAYER_PREVIOUS";
    static final String ACTION_NEXT = "com.hans.android.voicebutton.PLAYER_NEXT";
    static final String ACTION_REWIND = "com.hans.android.voicebutton.PLAYER_REWIND";
    static final String ACTION_FORWARD = "com.hans.android.voicebutton.PLAYER_FORWARD";
    static final String ACTION_CLOSE = "com.hans.android.voicebutton.PLAYER_CLOSE";

    private static final String CHANNEL_ID = "voicebutton_player";
    private static final int NOTIFICATION_ID = 7021;
    private static final long CHECKPOINT_MS = 5000L;
    private static final String EXTRA_SUPPRESS_RESTORE = "suppress_restore";

    interface Listener { void onPlayerSnapshot(Snapshot snapshot); }

    static final class Snapshot {
        final String state;
        final String error;
        final boolean playing;
        final boolean seekable;
        final long timeMs;
        final long lengthMs;
        final float rate;
        final PlayerSource originalSource;
        final PlayerSource activeSource;
        final boolean studioActive;
        final float studioSpeed;
        final int queueIndex;
        final int queueSize;
        final String engineSummary;

        Snapshot(String state, String error, boolean playing, boolean seekable,
                 long timeMs, long lengthMs, float rate,
                 PlayerSource originalSource, PlayerSource activeSource,
                 boolean studioActive, float studioSpeed,
                 int queueIndex, int queueSize, String engineSummary) {
            this.state = state == null ? "idle" : state;
            this.error = error == null ? "" : error;
            this.playing = playing;
            this.seekable = seekable;
            this.timeMs = Math.max(0L, timeMs);
            this.lengthMs = Math.max(0L, lengthMs);
            this.rate = rate;
            this.originalSource = originalSource;
            this.activeSource = activeSource;
            this.studioActive = studioActive;
            this.studioSpeed = Math.max(.01f, studioSpeed);
            this.queueIndex = queueIndex;
            this.queueSize = Math.max(0, queueSize);
            this.engineSummary = engineSummary == null ? "unavailable" : engineSummary;
        }

        static Snapshot initial() {
            return new Snapshot("idle", "", false, false,
                    0L, 0L, 1f, null, null,
                    false, 1f, -1, 0, "starting");
        }

        long logicalTimeMs() {
            return PlayerTimeline.logicalTime(timeMs, studioActive, studioSpeed);
        }

        long logicalLengthMs() {
            return PlayerTimeline.logicalLength(lengthMs, studioActive, studioSpeed);
        }
    }

    final class PlayerBinder extends Binder {
        PlayerService service() { return PlayerService.this; }
    }

    private final PlayerBinder binder = new PlayerBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayList<PlayerSource> queue = new ArrayList<>();
    private final ExecutorService lifecycleExecutor = Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "voicebutton-player-lifecycle");
                thread.setDaemon(false);
                return thread;
            });
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

    private VlcAudioPlayer player;
    private PlayerSettings settings;
    private PlayerStateStore stateStore;
    private MediaSession mediaSession;
    private PlayerSource originalSource;
    private PlayerSource activeSource;
    private int queueIndex = -1;
    private boolean studioActive;
    private float studioSpeed = 1f;
    private boolean foreground;
    private boolean restored;
    private boolean closedExplicitly;
    private boolean resumeAfterTaskRemoval;
    private volatile Snapshot snapshot = Snapshot.initial();

    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            persist(false, player != null && player.isPlaying());
            publish();
            if (activeSource != null) main.postDelayed(this, CHECKPOINT_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        settings = new PlayerSettings(this);
        stateStore = new PlayerStateStore(this);
        createChannel();
        player = new VlcAudioPlayer(this, this);
        initMediaSession();
        registerReceiver(noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        noisyReceiverRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_START.equals(action) && !restored) {
            boolean suppressRestore = intent != null
                    && intent.getBooleanExtra(EXTRA_SUPPRESS_RESTORE, false);
            if (suppressRestore) restored = true;
            else lifecycleExecutor.execute(this::restoreSavedState);
        } else if (ACTION_PLAY_PAUSE.equals(action)) playPause();
        else if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_PREVIOUS.equals(action)) skipToPrevious();
        else if (ACTION_NEXT.equals(action)) skipToNext();
        else if (ACTION_REWIND.equals(action)) skip(-settings.skipBack);
        else if (ACTION_FORWARD.equals(action)) skip(settings.skipForward);
        else if (ACTION_CLOSE.equals(action)) {
            lifecycleExecutor.execute(() -> closePlayer(false));
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    void addListener(Listener listener) {
        if (listener == null) return;
        listeners.add(listener);
        listener.onPlayerSnapshot(snapshot());
    }

    void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    Snapshot snapshot() {
        VlcAudioPlayer value = player;
        return new Snapshot(snapshot.state, snapshot.error,
                value != null && value.isPlaying(), value != null && value.isSeekable(),
                value == null ? snapshot.timeMs : value.time(),
                value == null ? snapshot.lengthMs : value.length(),
                value == null ? snapshot.rate : value.rate(),
                originalSource, activeSource, studioActive, studioSpeed,
                queueIndex, queue.size(),
                value == null ? snapshot.engineSummary : value.technicalSummary());
    }

    void open(PlayerSource source, List<PlayerSource> requestedQueue,
              int requestedIndex, boolean autoplay) {
        if (source == null) return;
        originalSource = source;
        activeSource = source;
        studioActive = false;
        studioSpeed = 1f;
        queue.clear();
        if (requestedQueue != null) queue.addAll(requestedQueue);
        queueIndex = requestedIndex;
        ensureForeground();
        player.openAt(source.uri, 0L, autoplay, settings.speed,
                settings.volume, settings.muted, settings.loop);
        snapshot = new Snapshot("opening", "", autoplay, false,
                0L, 0L, settings.speed, originalSource, activeSource,
                false, 1f, queueIndex, queue.size(), player.technicalSummary());
        persist(false, autoplay);
        main.removeCallbacks(checkpoint);
        main.post(checkpoint);
        publish();
    }

    void switchToStudio(PlayerSource studioSource, float speed,
                        long logicalPositionMs, boolean shouldPlay) {
        if (studioSource == null || originalSource == null) return;
        activeSource = studioSource;
        studioActive = true;
        studioSpeed = Math.max(.01f, speed);
        long physical = PlayerTimeline.physicalTime(logicalPositionMs,
                true, studioSpeed);
        ensureForeground();
        player.openAt(studioSource.uri, physical, shouldPlay,
                1f, settings.volume, settings.muted, settings.loop);
        persist(false, shouldPlay);
        publish();
    }

    void switchToOriginal(float speed, long logicalPositionMs,
                          boolean shouldPlay) {
        if (originalSource == null) return;
        activeSource = originalSource;
        studioActive = false;
        studioSpeed = 1f;
        player.openAt(originalSource.uri, logicalPositionMs, shouldPlay,
                speed, settings.volume, settings.muted, settings.loop);
        persist(false, shouldPlay);
        publish();
    }

    void playPause() { if (player.isPlaying()) pause(); else play(); }
    void play() { ensureForeground(); player.play(); persist(false, true); }
    void pause() { player.pause(); persist(false, false); }
    void stop() { player.stop(); persist(false, false); }
    void seekPhysical(long timeMs) { player.seek(timeMs); persist(false, player.isPlaying()); }
    void seekLogical(long logicalMs) {
        seekPhysical(PlayerTimeline.physicalTime(logicalMs,
                studioActive, studioSpeed));
    }
    void skip(float seconds) { player.skip(seconds); persist(false, player.isPlaying()); }
    void setSpeed(float speed) { player.setSpeed(speed); persist(false, player.isPlaying()); }
    void setVolume(int volume, boolean muted) { player.setVolume(volume, muted); }
    void setLoop(boolean loop) { player.setLoop(loop); }
    boolean isPlaying() { return player != null && player.isPlaying(); }
    String technicalSummary() { return player == null ? "unavailable" : player.technicalSummary(); }

    void skipToPrevious() { changeQueue(-1); }
    void skipToNext() { changeQueue(1); }

    private void changeQueue(int delta) {
        int target = queueIndex + delta;
        if (target < 0 || target >= queue.size()) return;
        queueIndex = target;
        PlayerSource source = queue.get(target);
        originalSource = source;
        activeSource = source;
        studioActive = false;
        studioSpeed = 1f;
        ensureForeground();
        boolean shouldPlay = settings.autoplay || player.isPlaying();
        player.openAt(source.uri, 0L, shouldPlay,
                settings.speed, settings.volume, settings.muted, settings.loop);
        persist(false, shouldPlay);
        publish();
    }

    private void restoreSavedState() {
        if (restored) return;
        restored = true;
        PlayerStateStore.Saved saved = stateStore.load();
        if (saved.original == null) return;
        originalSource = saved.original;
        activeSource = saved.active == null ? saved.original : saved.active;
        boolean restoredStudio = saved.studioActive;
        if (restoredStudio && "file".equalsIgnoreCase(activeSource.uri.getScheme())
                && activeSource.uri.getPath() != null
                && !new java.io.File(activeSource.uri.getPath()).isFile()) {
            activeSource = originalSource;
            restoredStudio = false;
        }
        queue.clear();
        queue.addAll(saved.queue);
        queueIndex = saved.queueIndex;
        studioActive = restoredStudio;
        studioSpeed = restoredStudio ? saved.studioSpeed : 1f;
        ensureForeground();
        long physical = PlayerTimeline.physicalTime(saved.logicalPositionMs,
                studioActive, studioSpeed);
        float rate = studioActive ? 1f : settings.speed;
        player.openAt(activeSource.uri, physical, saved.resumeOnOpen,
                rate, settings.volume, settings.muted, settings.loop);
        snapshot = new Snapshot("restoring", "", saved.resumeOnOpen, false,
                physical, 0L, rate, originalSource, activeSource,
                studioActive, studioSpeed, queueIndex, queue.size(),
                player.technicalSummary());
        main.post(checkpoint);
        publish();
    }

    @Override public void onState(String state) {
        snapshot = new Snapshot(state, "", player.isPlaying(), player.isSeekable(),
                player.time(), player.length(), player.rate(),
                originalSource, activeSource, studioActive, studioSpeed,
                queueIndex, queue.size(), player.technicalSummary());
        if ("ended".equals(state) && settings.autoplay) {
            skipToNext();
            return;
        }
        updateMediaSession();
        updateNotification();
        persist(false, player.isPlaying());
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
        snapshot = new Snapshot("error", detail, false, false,
                player.time(), player.length(), player.rate(),
                originalSource, activeSource, studioActive, studioSpeed,
                queueIndex, queue.size(), player.technicalSummary());
        updateMediaSession();
        updateNotification();
        persist(false, false);
        publish();
        PhoneDiagnostics diagnostics = PhoneDiagnostics.get();
        if (diagnostics != null) diagnostics.log(PhoneDiagnostics.ERROR,
                "player.service_error", null, detail,
                PhoneDiagnostics.fields("source",
                        originalSource == null ? "" : originalSource.title,
                        "engine", player.technicalSummary()));
    }

    private void persist(boolean durable, boolean resumeOnOpen) {
        if (stateStore == null) return;
        Snapshot value = snapshot();
        stateStore.save(originalSource, activeSource,
                new ArrayList<>(queue), queueIndex,
                value.logicalTimeMs(), resumeOnOpen,
                studioActive, studioSpeed, durable);
    }

    private void publish() {
        Snapshot value = snapshot();
        snapshot = value;
        for (Listener listener : listeners) listener.onPlayerSnapshot(value);
    }

    private void ensureForeground() {
        if (activeSource == null) return;
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        } else updateNotification();
    }

    private void updateNotification() {
        if (!foreground) return;
        NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private android.app.Notification buildNotification() {
        Intent content = new Intent(this, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 700,
                content, PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = player != null && player.isPlaying();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                this, CHANNEL_ID)
                .setSmallIcon(com.hans.android.voicebutton.R.drawable.ic_voice_button)
                .setContentTitle(originalSource == null ? "Voice Button player"
                        : originalSource.title)
                .setContentText(playing ? "Playing" : "Paused")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_voice_button, "Previous", serviceIntent(ACTION_PREVIOUS, 701))
                .addAction(R.drawable.ic_voice_button, "Rewind", serviceIntent(ACTION_REWIND, 702))
                .addAction(R.drawable.ic_voice_button, playing ? "Pause" : "Play",
                        serviceIntent(playing ? ACTION_PAUSE : ACTION_PLAY, 703))
                .addAction(R.drawable.ic_voice_button, "Forward", serviceIntent(ACTION_FORWARD, 704))
                .addAction(R.drawable.ic_voice_button, "Next", serviceIntent(ACTION_NEXT, 705));
        return builder.build();
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Player controls", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Playback controls while Voice Button remains open");
        manager.createNotificationChannel(channel);
    }

    private void initMediaSession() {
        mediaSession = new MediaSession(this, "VoiceButtonPlayerService");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() {
                lifecycleExecutor.execute(() -> closePlayer(false));
            }
            @Override public void onSeekTo(long position) { seekLogical(position); }
            @Override public void onSkipToNext() { skipToNext(); }
            @Override public void onSkipToPrevious() { skipToPrevious(); }
            @Override public void onFastForward() { skip(settings.skipForward); }
            @Override public void onRewind() { skip(-settings.skipBack); }
        });
        mediaSession.setActive(true);
        updateMediaSession();
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        Snapshot value = snapshot();
        if (originalSource != null) {
            mediaSession.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, originalSource.title)
                    .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                            originalSource.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Voice Button")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION,
                            value.logicalLengthMs()).build());
        }
        int state = value.playing ? PlaybackState.STATE_PLAYING
                : value.state.startsWith("buffering")
                || "opening".equals(value.state)
                || "restoring".equals(value.state)
                ? PlaybackState.STATE_BUFFERING
                : "ended".equals(value.state) || "stopped".equals(value.state)
                ? PlaybackState.STATE_STOPPED : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD
                | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, value.logicalTimeMs(),
                        value.studioActive ? value.studioSpeed : value.rate,
                        SystemClock.elapsedRealtime()).build());
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        try { lifecycleExecutor.execute(() -> closePlayer(true)); }
        catch (RuntimeException ignored) { stopSelf(); }
        super.onTaskRemoved(rootIntent);
    }

    private void closePlayer(boolean resumeOnNextOpen) {
        closedExplicitly = true;
        resumeAfterTaskRemoval = resumeOnNextOpen
                && player != null && player.isPlaying();
        persist(true, resumeAfterTaskRemoval);
        main.removeCallbacks(checkpoint);
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
        main.removeCallbacks(checkpoint);
        if (!closedExplicitly) persist(false, player != null && player.isPlaying());
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
        lifecycleExecutor.shutdownNow();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
