package myapp.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.io.ByteArrayOutputStream;

/** Bounded PCM player for a static recording or a growing server audio stream. */
@SuppressLint("ViewConstructor")
final class VoicePcmPlayerView extends LinearLayout {
  private static final int SAMPLE_WIDTH = 2;
  private static final int CHANNELS = 1;
  private static final int JUMP_MS = 5000;

  private final int sampleRate;
  private final int maxBytes;
  private VoicePcmPlayback playback;
  private boolean ownsPlayback=true;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final TextView title,currentTime,remainingTime,totalTime;
  private final MaterialButton playPause,stop,back,forward;
  private final SeekBar seek;
  private Runnable remoteStopListener;
  private boolean userSeeking;
  VoicePcmPlayerView(Context context, int sampleRate, int maxBytes, String label) {
    super(context);
    this.sampleRate = sampleRate;
    this.maxBytes = maxBytes;
    this.playback = new VoicePcmPlayback(sampleRate,maxBytes);
    setOrientation(VERTICAL);
    setPadding(dp(8), dp(6), dp(8), dp(6));
    setVisibility(GONE);

    title = new TextView(context);
    title.setText(label);
    title.setTextSize(12);
    addView(title, matchWrap());

    LinearLayout transportRow = new LinearLayout(context);
    transportRow.setOrientation(HORIZONTAL);
    playPause = button(context, R.string.play);
    playPause.setId(R.id.voice_player_play_pause);
    stop = button(context, R.string.stop_audio);
    stop.setId(R.id.voice_player_stop);
    back = button(context, R.string.jump_back);
    back.setId(R.id.voice_player_back);
    forward = button(context, R.string.jump_forward);
    forward.setId(R.id.voice_player_forward);
    // Keep all required transport actions in one touch-sized row. Two rows made
    // seek/timing controls fall below the live-player viewport on ordinary phones.
    transportRow.addView(back, weighted());
    transportRow.addView(playPause, weighted());
    transportRow.addView(stop, weighted());
    transportRow.addView(forward, weighted());
    addView(transportRow, matchWrap());

    seek = new SeekBar(context);
    seek.setId(R.id.voice_player_seek);
    seek.setContentDescription(context.getString(R.string.player_seek_description, label));
    seek.setMax(1);
    seek.setMinimumHeight(dp(48));
    addView(seek, matchWrap());
    LinearLayout timeRow = new LinearLayout(context);
    timeRow.setOrientation(HORIZONTAL);
    currentTime = timeValue(context, Gravity.START);
    currentTime.setId(R.id.voice_player_current);
    remainingTime = timeValue(context, Gravity.CENTER);
    remainingTime.setId(R.id.voice_player_remaining);
    totalTime = timeValue(context, Gravity.END);
    totalTime.setId(R.id.voice_player_total);
    timeRow.addView(currentTime, weighted());
    timeRow.addView(remainingTime, weighted());
    timeRow.addView(totalTime, weighted());
    addView(timeRow, matchWrap());

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

  void bindPlayback(VoicePcmPlayback value) {
    if(playback==value)return;
    if(ownsPlayback) {
      if(playback.hasAudio() && !playback.isStreamOpen() && !value.hasAudio())
        value.setStaticPcm(playback.snapshot());
      playback.cancelStream();
    }
    playback=value; ownsPlayback=false; updateUi();
  }
  void setRemoteStopListener(Runnable value) { remoteStopListener=value; }
  void setCommunicationPlayback(boolean value) { playback.setCommunicationPlayback(value); }
  boolean startStream(boolean autoPlay,boolean cancelable,boolean autoHide) {
    boolean result=playback.startStream(autoPlay,cancelable,autoHide); updateUi(); return result;
  }
  boolean append(byte[] pcm) { boolean result=playback.append(pcm); post(this::updateUi); return result; }
  void endStream() { playback.endStream(); post(this::updateUi); }
  void cancelStream() { playback.cancelStream(); post(this::updateUi); }
  void setStaticPcm(byte[] pcm) { playback.setStaticPcm(pcm); updateUi(); }
  byte[] snapshot() { return playback.snapshot(); }
  boolean hasAudio() { return playback.hasAudio(); }
  boolean isStreamOpen() { return playback.isStreamOpen(); }
  boolean isPlaying() { return playback.isPlaying(); }
  int availableMs() { return playback.availableMs(); }
  int currentMs() { return playback.currentMs(); }
  private void toggle() { playback.toggle(); updateUi(); }
  private void stopFromUser() {
    playback.stopFromUser();
    if(ownsPlayback && remoteStopListener!=null)remoteStopListener.run();
    updateUi();
  }
  private void jump(int deltaMs) { seekToMs(currentMs()+deltaMs); }
  private void seekToMs(int value) { playback.seekToMs(value); updateUi(); }

  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      updateUi();
      handler.postDelayed(this, 250L);
    }
  };

  private void updateUi() {
    setVisibility(playback.visible()?VISIBLE:GONE);
    if (getVisibility() != VISIBLE) return;
    int available = availableMs();
    int current = Math.min(currentMs(), available);
    // Live/growing audio is still a real exposed player. Keep the full transport,
    // timeline, and all three time values visible while new PCM is arriving.
    title.setVisibility(VISIBLE);
    back.setVisibility(VISIBLE);
    forward.setVisibility(VISIBLE);
    seek.setVisibility(VISIBLE);
    currentTime.setVisibility(VISIBLE);
    remainingTime.setVisibility(VISIBLE);
    totalTime.setVisibility(VISIBLE);
    seek.setMax(Math.max(1, available));
    if (!userSeeking) seek.setProgress(current);
    playPause.setText(playback.isPlaying() ? R.string.pause : R.string.play);
    int remaining = Math.max(0, available - current);
    currentTime.setText(getContext().getString(R.string.player_current_value, VoicePlayerMath.formatMs(current)));
    remainingTime.setText(getContext().getString(R.string.player_remaining_value, VoicePlayerMath.formatMs(remaining)));
    totalTime.setText(getContext().getString(
        playback.isStreamOpen() ? R.string.player_available_value : R.string.player_total_value,
        VoicePlayerMath.formatMs(available)));
    boolean have = available > 0;
    playPause.setEnabled(have || playback.isStreamOpen());
    stop.setEnabled(have || playback.isStreamOpen());
    back.setEnabled(have);
    forward.setEnabled(have);
    seek.setEnabled(have);
  }

  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow(); handler.removeCallbacks(ticker); handler.post(ticker);
  }
  @Override protected void onDetachedFromWindow() {
    handler.removeCallbacks(ticker);
    if(ownsPlayback)playback.cancelStream();
    super.onDetachedFromWindow();
  }

  private TextView timeValue(Context context, int gravity) {
    TextView value = new TextView(context);
    value.setTextSize(13);
    value.setTypeface(android.graphics.Typeface.MONOSPACE);
    value.setGravity(gravity);
    value.setMinHeight(dp(24));
    value.setGravity(gravity | Gravity.CENTER_VERTICAL);
    return value;
  }

  private MaterialButton button(Context context, int text) {
    MaterialButton b = new MaterialButton(context); b.setText(text); b.setMinHeight(dp(48)); return b;
  }
  private LayoutParams matchWrap() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); }
  private LayoutParams weighted() { return new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f); }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
