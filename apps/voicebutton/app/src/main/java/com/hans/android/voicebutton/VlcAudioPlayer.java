package com.hans.android.voicebutton;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.List;

final class VlcAudioPlayer {
    interface Listener {
        void onState(String state);
        void onPosition(long timeMs, long lengthMs);
        void onError(String detail);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LibVLC libVLC;
    private final MediaPlayer player;
    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private final AudioManager.OnAudioFocusChangeListener legacyFocusListener;
    private Listener listener;
    private boolean loop;
    private Uri sourceUri;
    private float desiredRate = 1f;
    private int desiredVolume = 100;
    private boolean muted;
    private boolean focusHeld;

    VlcAudioPlayer(Context context, Listener listener) {
        this.listener = listener;
        List<String> options = new ArrayList<>();
        options.add("--audio-time-stretch");
        options.add("--no-video-title-show");
        options.add("--file-caching=1000");
        options.add("--network-caching=1500");
        options.add("--clock-jitter=0");
        Context app = context.getApplicationContext();
        libVLC = new LibVLC(app, options);
        player = new MediaPlayer(libVLC);
        player.setEventListener(this::event);
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
        } else {
            focusRequest = null;
        }
    }

    void setListener(Listener value) { listener = value; }

    void open(Uri uri, boolean autoplay, float speed, int volume, boolean muted, boolean loop) {
        this.loop = loop; sourceUri = uri; desiredRate = speed;
        Media media = new Media(libVLC, uri);
        media.setHWDecoderEnabled(true, false);
        media.addOption(":no-video");
        media.addOption(":audio-time-stretch");
        player.setMedia(media); media.release();
        setVolume(volume, muted); player.setRate(desiredRate);
        if (autoplay) play();
        notifyState("ready");
    }

    void playPause() {
        if (player.isPlaying()) pause(); else play();
    }

    void play() {
        if (!requestAudioFocus()) {
            notifyError("Another application currently owns audio playback");
            return;
        }
        player.play();
    }
    void pause() { player.pause(); }
    void stop() { player.stop(); abandonAudioFocus(); }
    boolean isPlaying() { return player.isPlaying(); }
    boolean isSeekable() { return player.isSeekable(); }
    long time() { return Math.max(0L, player.getTime()); }
    long length() { return Math.max(0L, player.getLength()); }
    float rate() { return player.getRate(); }
    Uri sourceUri() { return sourceUri; }

    void seek(long timeMs) { player.setTime(Math.max(0L, Math.min(length(), timeMs))); }
    void skip(float seconds) { seek(time() + Math.round(seconds * 1000f)); }
    void setSpeed(float speed) { desiredRate = speed; player.setRate(speed); }
    void setLoop(boolean value) { loop = value; }
    void setVolume(int volume, boolean muted) {
        desiredVolume = Math.max(0, Math.min(100, volume));
        this.muted = muted;
        player.setVolume(muted ? 0 : desiredVolume);
    }

    String technicalSummary() {
        return "LibVLC " + LibVLC.version() + " · rate " + String.format(java.util.Locale.US, "%.2f", rate())
                + "× · audio tracks " + player.getAudioTracksCount()
                + " · seekable " + player.isSeekable();
    }

    void release() {
        abandonAudioFocus();
        try { player.setEventListener(null); player.stop(); player.release(); }
        catch (Exception ignored) {}
        try { libVLC.release(); } catch (Exception ignored) {}
    }

    private void event(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                player.setRate(desiredRate);
                player.setVolume(muted ? 0 : desiredVolume);
                notifyState("playing");
                break;
            case MediaPlayer.Event.Paused: notifyState("paused"); break;
            case MediaPlayer.Event.Stopped: notifyState("stopped"); break;
            case MediaPlayer.Event.Opening: notifyState("opening"); break;
            case MediaPlayer.Event.Buffering: notifyState("buffering " + Math.round(event.getBuffering()) + "%"); break;
            case MediaPlayer.Event.TimeChanged:
            case MediaPlayer.Event.LengthChanged: notifyPosition(); break;
            case MediaPlayer.Event.EndReached:
                if (loop) { seek(0L); play(); }
                else { abandonAudioFocus(); notifyState("ended"); }
                break;
            case MediaPlayer.Event.EncounteredError:
                abandonAudioFocus();
                notifyError("LibVLC could not decode or play this source");
                break;
            default: break;
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
            } else {
                audioManager.abandonAudioFocus(legacyFocusListener);
            }
        }
        focusHeld = false;
    }

    private void onAudioFocusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusHeld = true;
            player.setVolume(muted ? 0 : desiredVolume);
        } else if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player.isPlaying()) {
                player.pause();
                notifyState("paused for another audio application");
            }
            if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false;
        }
    }

    private void notifyState(String state) {
        Listener value = listener; if (value != null) main.post(() -> value.onState(state));
    }
    private void notifyPosition() {
        Listener value = listener; if (value != null) {
            long time = time(), length = length(); main.post(() -> value.onPosition(time, length));
        }
    }
    private void notifyError(String error) {
        Listener value = listener; if (value != null) main.post(() -> value.onError(error));
    }
}
