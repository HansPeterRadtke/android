package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroup;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
  private static final String BASE_URL = "https://jetson-fdx.jimmyandjonny.work";
  private static final String APP_BUILD_LABEL = "android03 cancellable-playback barge-in 2026-06-23 20:05";
  private static final int SAMPLE_RATE = 16000;
  private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
  private static final int CHUNK_MS = 1000;
  private static final int PLAYBACK_SLICE_BYTES = 3200;
  private static final double LOCAL_BARGE_RMS = 1800.0;
  private static final double LOCAL_BARGE_RATIO = 0.45;
  private static final long LOCAL_BARGE_IGNORE_MS = 450L;
  private static final long LOCAL_BARGE_DEBOUNCE_MS = 1200L;

  private ScrollView scrollView;
  private TextView textView;
  private TextView statusView;
  private Button startButton;
  private Button healthButton;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private volatile boolean stopRequested = false;
  private volatile long stopRequestedAtMs = 0L;
  private Thread uplinkThread;
  private Thread downlinkThread;
  private Thread playbackThread;
  private volatile String sid = null;
  private volatile int nextSeq = 0;
  private final Set<String> seenChunks = new HashSet<>();
  private final Set<String> seenEvents = new HashSet<>();
  private AudioTrack audioTrack;
  private final Object playbackLock = new Object();
  private volatile boolean playbackActive = false;
  private volatile boolean playbackCancelRequested = false;
  private volatile String activeChunkId = "";
  private volatile long playbackStartedAtMs = 0L;
  private volatile long lastLocalBargeMs = 0L;
  private volatile int playbackSerial = 0;
  private AcousticEchoCanceler aec;
  private NoiseSuppressor noiseSuppressor;
  private AutomaticGainControl agc;

  private static final class VoiceStats {
    final double rms;
    final double ratio;
    VoiceStats(double rms, double ratio) { this.rms = rms; this.ratio = ratio; }
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(18, 18, 18, 18);

    statusView = new TextView(this);
    statusView.setText("BUILD: " + APP_BUILD_LABEL + "\nBASE_URL: " + BASE_URL + "\nDedicated Jetson FDX endpoint. Local AudioTrack stop on mic barge-in + server cancel_audio.");
    statusView.setTextSize(15);
    layout.addView(statusView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    healthButton = new Button(this);
    healthButton.setText("Health check");
    layout.addView(healthButton, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    startButton = new Button(this);
    startButton.setText("Start full-duplex session");
    layout.addView(startButton, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    textView = new TextView(this);
    textView.setTextIsSelectable(true);
    textView.setSingleLine(false);
    textView.setMaxLines(Integer.MAX_VALUE);

    scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);
    scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    scrollView.addView(textView);
    layout.addView(scrollView);
    setContentView(layout);

    healthButton.setOnClickListener(v -> new Thread(this::healthCheck).start());
    startButton.setOnClickListener(v -> {
      if (running.get()) stopSession();
      else startSession();
    });
  }

  private void healthCheck() {
    try {
      JSONObject json = httpGetJson(BASE_URL + "/health");
      log("[APP BUILD] " + APP_BUILD_LABEL);
      log("[HEALTH] " + json.toString());
    } catch (Exception e) {
      log("[HEALTH ERROR] " + e);
    }
  }

  private void startSession() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
      log("[BLOCKED] Microphone permission missing.");
      return;
    }
    running.set(true);
    stopRequested = false;
    stopRequestedAtMs = 0L;
    seenChunks.clear();
    seenEvents.clear();
    nextSeq = 0;
    playbackActive = false;
    playbackCancelRequested = false;
    activeChunkId = "";
    runOnUiThread(() -> startButton.setText("Stop full-duplex session"));
    new Thread(() -> {
      try {
        JSONObject start = httpPostBytesJson(BASE_URL + "/fdx/start", new byte[0], "application/octet-stream");
        sid = start.getString("sid");
        log("[START] sid=" + sid);
        initPlayback();
        uplinkThread = new Thread(this::uplinkLoop, "fdx-uplink");
        downlinkThread = new Thread(this::downlinkLoop, "fdx-downlink");
        uplinkThread.start();
        downlinkThread.start();
      } catch (Exception e) {
        log("[START ERROR] " + e);
        running.set(false);
        runOnUiThread(() -> startButton.setText("Start full-duplex session"));
      }
    }, "fdx-start").start();
  }

  private void stopSession() {
    running.set(false);
    stopRequested = true;
    stopRequestedAtMs = System.currentTimeMillis();
    stopPlaybackLocal("stop_session", -1, 0.0, 0.0, true);
    runOnUiThread(() -> startButton.setText("Start full-duplex session"));
    log("[STOP] Stopping recording. Downlink will keep polling for late streaming audio.");
  }

  private void uplinkLoop() {
    AudioRecord recorder = null;
    try {
      int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
      int chunkBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000;
      int bufferSize = Math.max(min, chunkBytes * 2);
      recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize);
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord not initialized");
      initAudioEffects(recorder.getAudioSessionId());
      recorder.startRecording();
      log("[UPLINK] Recording VOICE_COMMUNICATION PCM16 mono 16k. Chunk bytes=" + chunkBytes + " local_barge_rms=" + LOCAL_BARGE_RMS + " ratio=" + LOCAL_BARGE_RATIO);
      byte[] pcm = new byte[chunkBytes];
      while (running.get()) {
        int got = readFully(recorder, pcm, chunkBytes);
        if (got > 0) {
          maybeLocalBargeIn(pcm, got);
          uploadPcmChunk(pcm, got, false);
        }
      }
      byte[] silence = new byte[SAMPLE_RATE / 10 * 2];
      uploadPcmChunk(silence, silence.length, true);
      log("[UPLINK] Final chunk sent. seq=" + (nextSeq - 1));
    } catch (Exception e) {
      log("[UPLINK ERROR] " + e);
    } finally {
      releaseAudioEffects();
      try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
      try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
    }
  }

  private void initAudioEffects(int sessionId) {
    try {
      if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sessionId); if (aec != null) aec.setEnabled(true); }
      if (NoiseSuppressor.isAvailable()) { noiseSuppressor = NoiseSuppressor.create(sessionId); if (noiseSuppressor != null) noiseSuppressor.setEnabled(true); }
      if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sessionId); if (agc != null) agc.setEnabled(true); }
      log("[AUDIO EFFECTS] aec=" + (aec != null && aec.getEnabled()) + " ns=" + (noiseSuppressor != null && noiseSuppressor.getEnabled()) + " agc=" + (agc != null && agc.getEnabled()));
    } catch (Exception e) {
      log("[AUDIO EFFECTS ERROR] " + e);
    }
  }

  private void releaseAudioEffects() {
    try { if (aec != null) aec.release(); } catch (Exception ignored) {}
    try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) {}
    try { if (agc != null) agc.release(); } catch (Exception ignored) {}
    aec = null; noiseSuppressor = null; agc = null;
  }

  private int readFully(AudioRecord recorder, byte[] target, int wanted) {
    int off = 0;
    while (running.get() && off < wanted) {
      int n = recorder.read(target, off, wanted - off);
      if (n > 0) off += n;
      else break;
    }
    return off;
  }

  private void maybeLocalBargeIn(byte[] pcm, int len) {
    String chunk = activeChunkId;
    if (!playbackActive || chunk == null || chunk.length() == 0) return;
    long now = System.currentTimeMillis();
    if (now - playbackStartedAtMs < LOCAL_BARGE_IGNORE_MS) return;
    if (now - lastLocalBargeMs < LOCAL_BARGE_DEBOUNCE_MS) return;
    VoiceStats st = voiceStats(pcm, len);
    if (st.rms >= LOCAL_BARGE_RMS && st.ratio >= LOCAL_BARGE_RATIO) {
      lastLocalBargeMs = now;
      stopPlaybackLocal("local_mic_barge_in", nextSeq, st.rms, st.ratio, true);
    }
  }

  private VoiceStats voiceStats(byte[] pcm, int len) {
    int samples = Math.max(0, len / 2);
    if (samples <= 0) return new VoiceStats(0.0, 0.0);
    double sum = 0.0;
    short[] vals = new short[samples];
    for (int i = 0; i < samples; i++) {
      int lo = pcm[i * 2] & 255;
      int hi = pcm[i * 2 + 1];
      short v = (short)((hi << 8) | lo);
      vals[i] = v;
      sum += (double)v * (double)v;
    }
    double rms = Math.sqrt(sum / samples);
    int frame = Math.max(1, SAMPLE_RATE / 50);
    int total = 0, active = 0;
    double threshold = Math.max(450.0, rms * 0.45);
    for (int start = 0; start < samples; start += frame) {
      int end = Math.min(samples, start + frame);
      double fs = 0.0;
      for (int i = start; i < end; i++) fs += (double)vals[i] * (double)vals[i];
      double fr = Math.sqrt(fs / Math.max(1, end - start));
      total++;
      if (fr > threshold) active++;
    }
    return new VoiceStats(rms, active / (double)Math.max(1, total));
  }

  private void uploadPcmChunk(byte[] pcm, int len, boolean finalChunk) throws Exception {
    if (sid == null || sid.length() == 0) {
      log("[UPLOAD SKIP] sid is empty final=" + finalChunk);
      return;
    }
    int seq = nextSeq++;
    byte[] wav = wavFromPcm16Mono16k(pcm, len);
    String url = BASE_URL + "/fdx/upload?sid=" + enc(sid) + "&seq=" + seq + "&final=" + (finalChunk ? "1" : "0");
    long startMs = System.currentTimeMillis();
    log("[UPLOAD SEND] seq=" + seq + " final=" + finalChunk + " wav=" + wav.length);
    JSONObject res = httpPostBytesJson(url, wav, "audio/wav");
    long doneMs = System.currentTimeMillis();
    String procStatus = res.optString("processing", "");
    log("[UPLOAD ACCEPTED] seq=" + seq + " final=" + finalChunk + " wav=" + wav.length + " http_ms=" + (doneMs - startMs) + " accepted=" + res.optBoolean("accepted", false) + " processing=" + procStatus + " queued_audio=" + res.optInt("queued_audio", -1));
  }

  private void downlinkLoop() {
    log("[DOWNLINK] Polling every 150 ms. Cancellable playback stays alive after Stop for late audio.");
    long lastAudioAt = System.currentTimeMillis();
    long idleAfterStopMs = 45000L;
    while (true) {
      String activeSid = sid;
      boolean shouldContinue = running.get() || (stopRequested && activeSid != null && System.currentTimeMillis() - lastAudioAt < idleAfterStopMs);
      if (!shouldContinue) break;
      if (activeSid == null || activeSid.length() == 0) {
        try { Thread.sleep(150); } catch (Exception ignored) {}
        continue;
      }
      try {
        JSONObject poll = httpGetJson(BASE_URL + "/fdx/poll?sid=" + enc(activeSid));
        handleControl(poll);
        JSONArray events = poll.optJSONArray("events");
        if (events != null) {
          int start = Math.max(0, events.length() - 12);
          for (int i = start; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            String kind = ev.optString("kind", "");
            String key = kind + ":" + ev.optInt("seq", -1) + ":" + ev.optString("chunk_id", "") + ":" + ev.optLong("t", 0);
            synchronized (seenEvents) {
              if (seenEvents.contains(key)) continue;
              seenEvents.add(key);
            }
            if ("upload_accepted".equals(kind)) log("[SERVER ACCEPTED] seq=" + ev.optInt("seq", -1) + " final=" + ev.optBoolean("final", false) + " bytes=" + ev.optInt("bytes", -1));
            else if ("playback_cancelled".equals(kind)) log("[SERVER CANCEL] chunk=" + ev.optString("chunk_id", "") + " reason=" + ev.optString("reason", "") + " epoch=" + ev.optInt("cancel_epoch", -1));
            else if ("playback_started".equals(kind)) log("[SERVER PLAYBACK START] chunk=" + ev.optString("chunk_id", "") + " duration=" + ev.optDouble("duration", -1));
            else if ("playback_done".equals(kind)) log("[SERVER PLAYBACK DONE] chunk=" + ev.optString("chunk_id", ""));
            else if ("utterance_committed".equals(kind)) log("[SERVER TURN] reply=" + ev.optString("reply_chunk_id", "") + " text=" + ev.optString("normalized_text", ""));
            else if ("utterance_ignored".equals(kind)) log("[SERVER IGNORED] reason=" + ev.optString("ignore_reason", "") + " text=" + ev.optString("normalized_text", ""));
            else if (kind.contains("error")) log("[SERVER EVENT] " + ev.toString());
          }
        }
        JSONArray q = poll.optJSONArray("audio_queue");
        if (q != null) {
          for (int i = 0; i < q.length(); i++) {
            JSONObject chunk = q.getJSONObject(i);
            String chunkId = chunk.getString("chunk_id");
            if (chunk.optBoolean("cancelled", false)) {
              log("[AUDIO SKIP CANCELLED] " + chunkId + " reason=" + chunk.optString("cancel_reason", ""));
              synchronized (seenChunks) { seenChunks.add(chunkId); }
              continue;
            }
            synchronized (seenChunks) {
              if (seenChunks.contains(chunkId)) continue;
              seenChunks.add(chunkId);
            }
            lastAudioAt = System.currentTimeMillis();
            log("[AUDIO QUEUED] " + chunkId + " bytes=" + chunk.optInt("bytes", -1) + " engine=" + chunk.optString("engine", "") + " text=" + chunk.optString("text", ""));
            byte[] wav = httpGetBytes(BASE_URL + "/fdx/audio?sid=" + enc(activeSid) + "&chunk=" + enc(chunkId));
            log("[AUDIO DOWNLOADED] " + chunkId + " wav=" + wav.length);
            playWavImmediately(wav, chunkId);
          }
        }
        Thread.sleep(150);
      } catch (Exception e) {
        log("[DOWNLINK ERROR] " + e);
        try { Thread.sleep(500); } catch (Exception ignored) {}
      }
    }
    sid = null;
    stopRequested = false;
    log("[DOWNLINK] stopped after streaming grace window.");
  }

  private void handleControl(JSONObject poll) {
    try {
      JSONObject control = poll.optJSONObject("control");
      if (control == null) return;
      JSONArray cancelled = control.optJSONArray("cancelled_chunks");
      if (cancelled == null || !playbackActive) return;
      String chunk = activeChunkId;
      for (int i = 0; i < cancelled.length(); i++) {
        if (chunk != null && chunk.equals(cancelled.optString(i, ""))) {
          stopPlaybackLocal("server_cancel", -1, 0.0, 0.0, false);
          break;
        }
      }
    } catch (Exception e) {
      log("[CONTROL ERROR] " + e);
    }
  }

  private void initPlayback() {
    int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
    if (android.os.Build.VERSION.SDK_INT >= 23) {
      audioTrack = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(AUDIO_FORMAT).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL_CONFIG_OUT).build())
        .setBufferSizeInBytes(Math.max(min, SAMPLE_RATE * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build();
    } else {
      audioTrack = new AudioTrack(AudioManager.STREAM_VOICE_CALL, SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, Math.max(min, SAMPLE_RATE * 2), AudioTrack.MODE_STREAM);
    }
    audioTrack.play();
  }

  private void playWavImmediately(byte[] wav, String chunkId) throws Exception {
    byte[] pcm = pcmFromWav(wav);
    int token;
    synchronized (playbackLock) {
      if (audioTrack == null) initPlayback();
      playbackSerial++;
      token = playbackSerial;
      playbackCancelRequested = false;
      playbackActive = true;
      activeChunkId = chunkId;
      playbackStartedAtMs = System.currentTimeMillis();
      try { audioTrack.flush(); } catch (Exception ignored) {}
      try { audioTrack.play(); } catch (Exception ignored) {}
    }
    playbackThread = new Thread(() -> playbackLoop(pcm, chunkId, token), "fdx-playback-" + chunkId);
    playbackThread.start();
  }

  private void playbackLoop(byte[] pcm, String chunkId, int token) {
    int off = 0;
    int totalWritten = 0;
    postPlaybackEvent("playback_start", chunkId);
    log("[PLAY START] chunk=" + chunkId + " pcm=" + pcm.length);
    try {
      while (off < pcm.length && token == playbackSerial && !playbackCancelRequested) {
        int n = Math.min(PLAYBACK_SLICE_BYTES, pcm.length - off);
        int written;
        synchronized (playbackLock) {
          if (audioTrack == null) break;
          written = audioTrack.write(pcm, off, n);
        }
        if (written > 0) { off += written; totalWritten += written; }
        else { try { Thread.sleep(10); } catch (Exception ignored) {} }
      }
    } catch (Exception e) {
      log("[PLAY ERROR] " + e);
    }
    boolean cancelled = token != playbackSerial || playbackCancelRequested || totalWritten < pcm.length;
    if (cancelled) log("[PLAY STOPPED] chunk=" + chunkId + " written=" + totalWritten + " pcm=" + pcm.length);
    else log("[PLAY DONE] chunk=" + chunkId + " written=" + totalWritten);
    synchronized (playbackLock) {
      if (token == playbackSerial) {
        playbackActive = false;
        playbackCancelRequested = false;
        activeChunkId = "";
      }
    }
    postPlaybackEvent("playback_done", chunkId);
  }

  private void stopPlaybackLocal(String reason, int seq, double rms, double ratio, boolean notifyServer) {
    String chunk = activeChunkId;
    if (!playbackActive && (chunk == null || chunk.length() == 0)) return;
    synchronized (playbackLock) {
      playbackCancelRequested = true;
      playbackSerial++;
      playbackActive = false;
      try { if (audioTrack != null) audioTrack.pause(); } catch (Exception ignored) {}
      try { if (audioTrack != null) audioTrack.flush(); } catch (Exception ignored) {}
      try { if (audioTrack != null) audioTrack.play(); } catch (Exception ignored) {}
    }
    log("[BARGE-IN LOCAL STOP] reason=" + reason + " chunk=" + chunk + " seq=" + seq + " rms=" + String.format(Locale.US, "%.1f", rms) + " ratio=" + String.format(Locale.US, "%.2f", ratio));
    if (notifyServer && sid != null && sid.length() > 0 && chunk != null && chunk.length() > 0) {
      final String sendSid = sid;
      final String sendChunk = chunk;
      new Thread(() -> {
        try {
          String url = BASE_URL + "/fdx/cancel_audio?sid=" + enc(sendSid) + "&chunk=" + enc(sendChunk) + "&reason=" + enc(reason) + "&seq=" + seq;
          JSONObject res = httpPostBytesJson(url, new byte[0], "application/octet-stream");
          log("[CANCEL SENT] chunk=" + sendChunk + " response=" + res.toString());
        } catch (Exception e) {
          log("[CANCEL ERROR] " + e);
        }
      }, "fdx-cancel").start();
    }
    activeChunkId = "";
  }

  private void postPlaybackEvent(String kind, String chunkId) {
    if (sid == null || sid.length() == 0 || chunkId == null || chunkId.length() == 0) return;
    new Thread(() -> {
      try {
        httpPostBytesJson(BASE_URL + "/fdx/" + kind + "?sid=" + enc(sid) + "&chunk=" + enc(chunkId), new byte[0], "application/octet-stream");
      } catch (Exception e) {
        log("[PLAYBACK EVENT ERROR] " + kind + " " + e);
      }
    }, "fdx-" + kind).start();
  }

  private static byte[] wavFromPcm16Mono16k(byte[] pcm, int len) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream(44 + len);
    writeAscii(out, "RIFF"); writeLe32(out, 36 + len); writeAscii(out, "WAVE");
    writeAscii(out, "fmt "); writeLe32(out, 16); writeLe16(out, 1); writeLe16(out, 1);
    writeLe32(out, SAMPLE_RATE); writeLe32(out, SAMPLE_RATE * 2); writeLe16(out, 2); writeLe16(out, 16);
    writeAscii(out, "data"); writeLe32(out, len); out.write(pcm, 0, len);
    return out.toByteArray();
  }

  private static byte[] pcmFromWav(byte[] wav) throws Exception {
    if (wav.length < 44) throw new IllegalArgumentException("WAV too short");
    int pos = 12;
    while (pos + 8 <= wav.length) {
      String id = new String(wav, pos, 4, "US-ASCII");
      int size = ByteBuffer.wrap(wav, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
      pos += 8;
      if ("data".equals(id)) {
        byte[] pcm = new byte[Math.max(0, Math.min(size, wav.length - pos))];
        System.arraycopy(wav, pos, pcm, 0, pcm.length);
        return pcm;
      }
      pos += size;
      if ((size & 1) == 1) pos += 1;
    }
    throw new IllegalArgumentException("No WAV data chunk");
  }

  private JSONObject httpGetJson(String url) throws Exception { return new JSONObject(new String(httpGetBytes(url), "UTF-8")); }

  private byte[] httpGetBytes(String urlText) throws Exception {
    HttpURLConnection c = (HttpURLConnection)new URL(urlText).openConnection();
    c.setConnectTimeout(5000); c.setReadTimeout(30000); c.setRequestMethod("GET");
    int code = c.getResponseCode();
    byte[] body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
    if (code < 200 || code >= 300) throw new RuntimeException("GET " + code + " " + urlText + " " + new String(body, "UTF-8"));
    return body;
  }

  private JSONObject httpPostBytesJson(String urlText, byte[] body, String contentType) throws Exception {
    HttpURLConnection c = (HttpURLConnection)new URL(urlText).openConnection();
    c.setConnectTimeout(5000); c.setReadTimeout(60000); c.setRequestMethod("POST"); c.setDoOutput(true);
    c.setRequestProperty("Content-Type", contentType);
    c.setRequestProperty("Content-Length", String.valueOf(body.length));
    OutputStream out = c.getOutputStream(); out.write(body); out.close();
    int code = c.getResponseCode();
    byte[] resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
    if (code < 200 || code >= 300) throw new RuntimeException("POST " + code + " " + urlText + " " + new String(resp, "UTF-8"));
    return new JSONObject(new String(resp, "UTF-8"));
  }

  private static byte[] readAll(InputStream in) throws Exception {
    if (in == null) return new byte[0];
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192]; int n;
    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    in.close(); return out.toByteArray();
  }

  private static void writeAscii(ByteArrayOutputStream out, String s) throws Exception { out.write(s.getBytes("US-ASCII")); }
  private static void writeLe16(ByteArrayOutputStream out, int v) { out.write(v & 255); out.write((v >> 8) & 255); }
  private static void writeLe32(ByteArrayOutputStream out, int v) { out.write(v & 255); out.write((v >> 8) & 255); out.write((v >> 16) & 255); out.write((v >> 24) & 255); }
  private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

  private void log(String msg) {
    runOnUiThread(() -> {
      textView.append("[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + msg + "\n");
      scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    });
  }

  @Override protected void onDestroy() {
    running.set(false);
    stopPlaybackLocal("destroy", -1, 0.0, 0.0, true);
    releaseAudioEffects();
    try { if (audioTrack != null) audioTrack.release(); } catch (Exception ignored) {}
    super.onDestroy();
  }
}
