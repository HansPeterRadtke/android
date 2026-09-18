package myapp.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** Bounded PCM player for a static recording or a growing server audio stream. */
@SuppressLint("ViewConstructor")
final class VoicePcmPlayerView extends LinearLayout {
  private static final int SAMPLE_WIDTH = 2;
  private static final int CHANNELS = 1;
  private static final int JUMP_MS = 5000;

  private static final class PcmBuffer extends ByteArrayOutputStream {
    PcmBuffer(int initial) { super(initial); }
    synchronized boolean appendBounded(byte[] value, int maxBytes) {
      if (value == null || value.length == 0) return true;
      if (count + value.length > maxBytes) return false;
      write(value, 0, value.length);
      return true;
    }
    synchronized int copyFrom(int offset, byte[] target) {
      if (offset < 0 || offset >= count) return 0;
      int length = Math.min(target.length, count - offset);
      System.arraycopy(buf, offset, target, 0, length);
      return length;
    }
    synchronized int bytes() { return count; }
    synchronized byte[] snapshot() { return toByteArray(); }
    synchronized void replace(byte[] value) {
      reset();
      if (value != null) write(value, 0, value.length);
    }
  }

  private final int sampleRate;
  private final int maxBytes;
  private final Object lock = new Object();
  private final PcmBuffer buffer;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final TextView title;
  private final TextView times;
  private final MaterialButton playPause;
  private final MaterialButton stop;
  private final MaterialButton back;
  private final MaterialButton forward;
  private final SeekBar seek;
  private AudioTrack track;
  private Thread thread;
  private volatile boolean playing;
  private volatile boolean streamOpen;
  private volatile boolean remoteCancelable;
  private volatile int positionBytes;
  private volatile long serial;
  private Runnable remoteStopListener;
  private boolean userSeeking;

  VoicePcmPlayerView(Context context, int sampleRate, int maxBytes, String label) {
    super(context);
    this.sampleRate = sampleRate;
    this.maxBytes = maxBytes;
    this.buffer = new PcmBuffer(Math.min(maxBytes, sampleRate * SAMPLE_WIDTH * 4));
    setOrientation(VERTICAL);
    setPadding(dp(8), dp(6), dp(8), dp(6));
    setVisibility(GONE);

    title = new TextView(context);
    title.setText(label);
    title.setTextSize(14);
    addView(title, matchWrap());

    LinearLayout transportRow = new LinearLayout(context);
    transportRow.setOrientation(HORIZONTAL);
    LinearLayout seekRow = new LinearLayout(context);
    seekRow.setOrientation(HORIZONTAL);
    playPause = button(context, R.string.play);
    stop = button(context, R.string.stop_audio);
    back = button(context, R.string.jump_back);
    forward = button(context, R.string.jump_forward);
    transportRow.addView(playPause, weighted());
    transportRow.addView(stop, weighted());
    seekRow.addView(back, weighted());
    seekRow.addView(forward, weighted());
    addView(transportRow, matchWrap());
    addView(seekRow, matchWrap());

    seek = new SeekBar(context);
    seek.setMax(1);
    addView(seek, matchWrap());
    times = new TextView(context);
    times.setTextSize(13);
    addView(times, matchWrap());

    playPause.setOnClickListener(v -> toggle());
    stop.setOnClickListener(v -> stopFromUser());
    back.setOnClickListener(v -> jump(-JUMP_MS));
    forward.setOnClickListener(v -> jump(JUMP_MS));
    seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        if (fromUser && userSeeking) seekToMs(progress);
      }
      @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
      @Override public void onStopTrackingTouch(SeekBar bar) { userSeeking = false; seekToMs(bar.getProgress()); }
    });
    handler.post(ticker);
  }

  void setRemoteStopListener(Runnable listener) { remoteStopListener = listener; }

  boolean startStream(boolean autoPlay, boolean cancelable) {
    synchronized (lock) {
      serial++;
      stopTrackLocked();
      buffer.replace(null);
      positionBytes = 0;
      streamOpen = true;
      remoteCancelable = cancelable;
      playing = false;
    }
    setVisibility(VISIBLE);
    if (autoPlay) startPlaying();
    updateUi();
    return true;
  }

  boolean append(byte[] pcm) {
    boolean ok;
    synchronized (lock) { ok = buffer.appendBounded(pcm, maxBytes); }
    if (ok) post(this::updateUi);
    return ok;
  }

  void endStream() {
    streamOpen = false;
    post(this::updateUi);
  }

  void cancelStream() {
    synchronized (lock) {
      streamOpen = false;
      playing = false;
      serial++;
      stopTrackLocked();
    }
    post(this::updateUi);
  }

  void setStaticPcm(byte[] pcm) {
    synchronized (lock) {
      serial++;
      playing = false;
      streamOpen = false;
      remoteCancelable = false;
      stopTrackLocked();
      buffer.replace(pcm);
      positionBytes = 0;
    }
    setVisibility(pcm != null && pcm.length > 0 ? VISIBLE : GONE);
    updateUi();
  }

  byte[] snapshot() { synchronized (lock) { return buffer.snapshot(); } }
  boolean hasAudio() { synchronized (lock) { return buffer.bytes() > 0; } }
  boolean isStreamOpen() { return streamOpen; }
  int availableMs() { synchronized (lock) { return bytesToMs(buffer.bytes()); } }
  int currentMs() { return bytesToMs(positionBytes); }

  private void toggle() {
    if (playing) pausePlaying(); else startPlaying();
  }

  private void startPlaying() {
    synchronized (lock) {
      if (buffer.bytes() == 0 && !streamOpen) return;
      if (positionBytes >= buffer.bytes() && !streamOpen) positionBytes = 0;
      serial++;
      playing = true;
      stopTrackLocked();
      initTrackLocked();
      track.play();
      long mine = serial;
      thread = new Thread(() -> playbackLoop(mine), "voice-pcm-player");
      thread.start();
    }
    updateUi();
  }

  private void pausePlaying() {
    synchronized (lock) {
      playing = false;
      serial++;
      try { if (track != null) track.pause(); } catch (Exception ignored) {}
    }
    updateUi();
  }

  private void stopFromUser() {
    boolean notify = remoteCancelable && streamOpen;
    synchronized (lock) {
      playing = false;
      serial++;
      positionBytes = 0;
      stopTrackLocked();
    }
    if (notify && remoteStopListener != null) remoteStopListener.run();
    updateUi();
  }

  private void jump(int deltaMs) { seekToMs(currentMs() + deltaMs); }

  private void seekToMs(int value) {
    int target = Math.max(0, Math.min(value, availableMs()));
    synchronized (lock) {
      positionBytes = aligned(msToBytes(target));
      if (playing) {
        serial++;
        stopTrackLocked();
        initTrackLocked();
        track.play();
        long mine = serial;
        thread = new Thread(() -> playbackLoop(mine), "voice-pcm-player");
        thread.start();
      }
    }
    updateUi();
  }

  private void playbackLoop(long mine) {
    byte[] chunk = new byte[Math.max(960, sampleRate * SAMPLE_WIDTH / 20)];
    try {
      while (playing && serial == mine) {
        int got;
        synchronized (lock) { got = buffer.copyFrom(positionBytes, chunk); }
        if (got > 0) {
          int written;
          synchronized (lock) {
            if (!playing || serial != mine || track == null) break;
            written = track.write(chunk, 0, got, AudioTrack.WRITE_BLOCKING);
          }
          if (written > 0) positionBytes += written;
        } else if (streamOpen) {
          Thread.sleep(15L);
        } else {
          playing = false;
          break;
        }
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    } finally {
      post(this::updateUi);
    }
  }

  private void initTrackLocked() {
    int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
    track = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(Math.max(min, sampleRate * SAMPLE_WIDTH / 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build();
  }

  private void stopTrackLocked() {
    try { if (track != null) track.pause(); } catch (Exception ignored) {}
    try { if (track != null) track.flush(); } catch (Exception ignored) {}
    try { if (track != null) track.release(); } catch (Exception ignored) {}
    track = null;
    Thread old = thread;
    thread = null;
    if (old != null && old != Thread.currentThread()) old.interrupt();
  }

  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      updateUi();
      handler.postDelayed(this, 250L);
    }
  };

  private void updateUi() {
    if (getVisibility() != VISIBLE) return;
    int available = availableMs();
    int current = Math.min(currentMs(), available);
    seek.setMax(Math.max(1, available));
    if (!userSeeking) seek.setProgress(current);
    playPause.setText(playing ? R.string.pause : R.string.play);
    int remaining = Math.max(0, available - current);
    if (streamOpen) {
      times.setText(getContext().getString(R.string.player_times_streaming, format(current), format(remaining), format(available)));
    } else {
      times.setText(getContext().getString(R.string.player_times, format(current), format(remaining), format(available)));
    }
    boolean have = available > 0;
    playPause.setEnabled(have || streamOpen);
    stop.setEnabled(have || streamOpen);
    back.setEnabled(have);
    forward.setEnabled(have);
    seek.setEnabled(have);
  }

  private int bytesToMs(int bytes) { return (int) Math.min(Integer.MAX_VALUE, (long) bytes * 1000L / (sampleRate * SAMPLE_WIDTH * CHANNELS)); }
  private int msToBytes(int ms) { return (int) Math.min(maxBytes, (long) ms * sampleRate * SAMPLE_WIDTH * CHANNELS / 1000L); }
  private int aligned(int value) { return value - Math.floorMod(value, SAMPLE_WIDTH * CHANNELS); }
  private static String format(int ms) {
    int total = Math.max(0, ms / 1000); int minutes = total / 60; int seconds = total % 60;
    return String.format(Locale.US, "%02d:%02d", minutes, seconds);
  }

  @Override protected void onDetachedFromWindow() {
    handler.removeCallbacks(ticker);
    synchronized (lock) { playing = false; serial++; stopTrackLocked(); }
    super.onDetachedFromWindow();
  }

  private MaterialButton button(Context context, int text) {
    MaterialButton b = new MaterialButton(context); b.setText(text); b.setMinHeight(dp(44)); return b;
  }
  private LayoutParams matchWrap() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); }
  private LayoutParams weighted() { return new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
