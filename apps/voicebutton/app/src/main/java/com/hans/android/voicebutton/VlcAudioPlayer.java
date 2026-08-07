package com.hans.android.voicebutton;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class VlcAudioPlayer {
    interface Listener {
        void onState(String state);
        void onPosition(long timeMs, long lengthMs);
        void onError(String detail);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread engineThread = new HandlerThread(
            "voicebutton-libvlc", android.os.Process.THREAD_PRIORITY_AUDIO);
    private final Handler engine;
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final AudioManager.OnAudioFocusChangeListener legacyFocusListener;

    private volatile Listener listener;
    private LibVLC libVLC;
    private MediaPlayer player;
    private ParcelFileDescriptor sourceDescriptor;
    private volatile Uri sourceUri;
    private volatile boolean playing;
    private volatile boolean seekable;
    private volatile long cachedTimeMs;
    private volatile long cachedLengthMs;
    private volatile float cachedRate = 1f;
    private volatile int cachedAudioTracks;
    private volatile String engineState = "starting";
    private volatile boolean released;
    private boolean loop;
    private float desiredRate = 1f;
    private int desiredVolume = 100;
    private boolean muted;
    private boolean focusHeld;
    private int lastBufferPercent = -100;
    private boolean terminalError;
    private long pendingStartMs = -1L;
    private boolean pendingShouldPlay;

    VlcAudioPlayer(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        legacyFocusListener = this::onAudioFocusChanged;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(legacyFocusListener, main)
                    .build();
        } else focusRequest = null;
        engineThread.start();
        engine = new Handler(engineThread.getLooper());
        engine.post(this::initializeEngine);
    }

    void setListener(Listener value) { listener = value; }

    void open(Uri uri, boolean autoplay, float speed, int volume,
              boolean muted, boolean loop) {
        openAt(uri, 0L, autoplay, speed, volume, muted, loop);
    }

    void openAt(Uri uri, long startMs, boolean shouldPlay, float speed,
                int volume, boolean muted, boolean loop) {
        sourceUri = uri;
        desiredRate = speed;
        desiredVolume = Math.max(0, Math.min(100, volume));
        this.muted = muted;
        this.loop = loop;
        pendingStartMs = Math.max(0L, startMs);
        pendingShouldPlay = shouldPlay;
        terminalError = false;
        lastBufferPercent = -100;
        playing = false;
        cachedTimeMs = pendingStartMs;
        cachedLengthMs = 0L;
        engineState = "queued";
        notifyState("opening");
        post(() -> openOnEngine(uri, shouldPlay || pendingStartMs > 0L));
    }

    void playPause() { if (playing) pause(); else play(); }
    void play() {
        engineState = "play-requested";
        notifyState("starting playback");
        post(this::playOnEngine);
    }
    void pause() { post(() -> { if (player != null) player.pause(); }); }
    void stop() { post(() -> { if (player != null) player.stop(); abandonAudioFocus(); }); }
    boolean isPlaying() { return playing; }
    boolean isSeekable() { return seekable; }
    long time() { return Math.max(0L, cachedTimeMs); }
    long length() { return Math.max(0L, cachedLengthMs); }
    float rate() { return cachedRate; }
    Uri sourceUri() { return sourceUri; }

    void seek(long timeMs) {
        long bounded = Math.max(0L, cachedLengthMs > 0L
                ? Math.min(cachedLengthMs, timeMs) : timeMs);
        cachedTimeMs = bounded;
        post(() -> { if (player != null) player.setTime(bounded); });
    }

    void skip(float seconds) { seek(time() + Math.round(seconds * 1000f)); }

    void setSpeed(float speed) {
        desiredRate = speed;
        cachedRate = speed;
        post(() -> { if (player != null) player.setRate(speed); });
    }

    void setLoop(boolean value) { loop = value; }

    void setVolume(int volume, boolean muted) {
        desiredVolume = Math.max(0, Math.min(100, volume));
        this.muted = muted;
        post(() -> { if (player != null) player.setVolume(muted ? 0 : desiredVolume); });
    }

    String technicalSummary() {
        String version = LibVlcVersionGuard.safeVersion(
                libVLC != null, LibVLC::version);
        return "LibVLC " + version + " · engine " + engineState
                + " · thread " + engineThread.getName()
                + " · rate " + String.format(Locale.US, "%.2f", cachedRate)
                + "× · audio tracks " + cachedAudioTracks
                + " · seekable " + seekable;
    }

    void release() {
        released = true;
        listener = null;
        engine.post(() -> {
            abandonAudioFocus();
            closeDescriptor();
            try {
                if (player != null) {
                    player.setEventListener(null);
                    player.stop();
                    player.release();
                }
            } catch (Exception ignored) {}
            player = null;
            try { if (libVLC != null) libVLC.release(); }
            catch (Exception ignored) {}
            libVLC = null;
            engineThread.quitSafely();
        });
    }

    private void initializeEngine() {
        if (released || libVLC != null) return;
        try {
            List<String> options = new ArrayList<>();
            options.add("--audio-time-stretch");
            options.add("--no-video-title-show");
            options.add("--file-caching=1000");
            options.add("--network-caching=1500");
            options.add("--clock-jitter=0");
            libVLC = new LibVLC(app, options);
            player = new MediaPlayer(libVLC);
            player.setEventListener(this::event);
            engineState = "ready";
        } catch (Exception failure) {
            engineState = "failed";
            notifyError("LibVLC initialization failed: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void openOnEngine(Uri uri, boolean autoplay) {
        try {
            initializeEngine();
            if (player == null) throw new IllegalStateException("LibVLC is not ready");
            closeDescriptor();
            Media media;
            String scheme = uri == null ? null : uri.getScheme();
            String route = PlayerOpenRoute.forScheme(scheme);
            if (PlayerOpenRoute.CONTENT_FILE_DESCRIPTOR.equals(route)) {
                sourceDescriptor = app.getContentResolver().openFileDescriptor(uri, "r");
                if (sourceDescriptor == null) {
                    throw new java.io.FileNotFoundException(
                            "Android could not open the selected content URI");
                }
                media = new Media(libVLC, sourceDescriptor.getFileDescriptor());
            } else if (PlayerOpenRoute.FILE_PATH.equals(route)
                    && uri != null && uri.getPath() != null) {
                media = new Media(libVLC, uri.getPath());
            } else media = new Media(libVLC, uri);
            media.setHWDecoderEnabled(true, false);
            media.addOption(":no-video");
            media.addOption(":audio-time-stretch");
            player.setMedia(media);
            media.release();
            player.setVolume(muted ? 0 : desiredVolume);
            player.setRate(desiredRate);
            cachedRate = desiredRate;
            engineState = "media-ready";
            notifyState("ready");
            if (autoplay) playOnEngine();
        } catch (Exception failure) {
            engineState = "open-failed";
            closeDescriptor();
            notifyError("Could not open audio: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    + " · " + technicalSummary());
        }
    }

    private void playOnEngine() {
        if (player == null) {
            notifyError("LibVLC is still starting");
            return;
        }
        if (!requestAudioFocus()) {
            notifyError("Another application currently owns audio playback");
            return;
        }
        engineState = "starting playback";
        notifyState("starting playback");
        player.play();
    }

    private void event(MediaPlayer.Event event) {
        if (released) return;
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                terminalError = false;
                playing = true;
                engineState = "playing";
                player.setRate(desiredRate);
                player.setVolume(muted ? 0 : desiredVolume);
                if (pendingStartMs >= 0L) {
                    long start = pendingStartMs;
                    boolean keepPlaying = pendingShouldPlay;
                    pendingStartMs = -1L;
                    pendingShouldPlay = false;
                    player.setTime(start);
                    cachedTimeMs = start;
                    if (!keepPlaying) {
                        player.pause();
                        playing = false;
                        engineState = "paused";
                        notifyState("paused");
                        break;
                    }
                }
                cachedRate = player.getRate();
                cachedAudioTracks = player.getAudioTracksCount();
                seekable = player.isSeekable();
                notifyState("playing");
                break;
            case MediaPlayer.Event.Paused:
                playing = false;
                engineState = "paused";
                notifyState("paused");
                break;
            case MediaPlayer.Event.Stopped:
                playing = false;
                if (!terminalError) {
                    engineState = "stopped";
                    notifyState("stopped");
                }
                break;
            case MediaPlayer.Event.Opening:
                engineState = "opening";
                notifyState("opening");
                break;
            case MediaPlayer.Event.Buffering:
                engineState = "buffering";
                int percent = Math.round(event.getBuffering());
                if (Math.abs(percent - lastBufferPercent) >= 10 || percent >= 100) {
                    lastBufferPercent = percent;
                    notifyState("buffering " + percent + "%");
                }
                break;
            case MediaPlayer.Event.TimeChanged:
            case MediaPlayer.Event.LengthChanged:
                cachedTimeMs = Math.max(0L, player.getTime());
                cachedLengthMs = Math.max(0L, player.getLength());
                seekable = player.isSeekable();
                break;
            case MediaPlayer.Event.EndReached:
                playing = false;
                if (cachedLengthMs > 0L) cachedTimeMs = cachedLengthMs;
                notifyPosition(cachedTimeMs, cachedLengthMs);
                if (loop) {
                    player.setTime(0L);
                    playOnEngine();
                } else {
                    abandonAudioFocus();
                    engineState = "ended";
                    notifyState("ended");
                }
                break;
            case MediaPlayer.Event.EncounteredError:
                terminalError = true;
                playing = false;
                abandonAudioFocus();
                engineState = "decode-error";
                notifyError("LibVLC could not decode or play this source · "
                        + technicalSummary());
                break;
            default: break;
        }
    }

    private void post(Runnable action) {
        if (released) return;
        try { engine.post(action); }
        catch (RuntimeException failure) {
            notifyError("Player engine rejected an operation: " + failure.getMessage());
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (focusHeld) return true;
        if (Build.VERSION.SDK_INT >= 26) {
            focusHeld = audioManager.requestAudioFocus(focusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            focusHeld = audioManager.requestAudioFocus(legacyFocusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return focusHeld;
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusHeld) {
            if (Build.VERSION.SDK_INT >= 26) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else audioManager.abandonAudioFocus(legacyFocusListener);
        }
        focusHeld = false;
    }

    private void onAudioFocusChanged(int change) {
        post(() -> {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                focusHeld = true;
                if (player != null) player.setVolume(muted ? 0 : desiredVolume);
            } else if (change == AudioManager.AUDIOFOCUS_LOSS
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (player != null && playing) {
                    player.pause();
                    notifyState("paused for another audio application");
                }
                if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false;
            }
        });
    }

    private void closeDescriptor() {
        if (sourceDescriptor == null) return;
        try { sourceDescriptor.close(); } catch (Exception ignored) {}
        sourceDescriptor = null;
    }

    private void notifyState(String state) {
        Listener value = listener;
        if (value != null) main.post(() -> {
            Listener current = listener;
            if (current != null) current.onState(state);
        });
    }

    private void notifyPosition(long timeMs, long lengthMs) {
        Listener value = listener;
        if (value != null) main.post(() -> {
            Listener current = listener;
            if (current != null) current.onPosition(timeMs, lengthMs);
        });
    }

    private void notifyError(String error) {
        Listener value = listener;
        if (value != null) main.post(() -> {
            Listener current = listener;
            if (current != null) current.onError(error);
        });
    }
}
