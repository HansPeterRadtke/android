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
  private static final String BASE_URL = "https://jetsonsystem.jimmyandjonny.work";
  private static final int SAMPLE_RATE = 16000;
  private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
  private static final int CHUNK_MS = 1000;

  private ScrollView scrollView;
  private TextView textView;
  private TextView statusView;
  private Button startButton;
  private Button healthButton;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread uplinkThread;
  private Thread downlinkThread;
  private volatile String sid = null;
  private volatile int nextSeq = 0;
  private final Set<String> seenChunks = new HashSet<>();
  private AudioTrack audioTrack;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(18, 18, 18, 18);

    statusView = new TextView(this);
    statusView.setText("BASE_URL: " + BASE_URL + "\nJetson FDX endpoint through Cloudflare: /fdx/*");
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
      JSONObject json = httpGetJson(BASE_URL + "/fdx/health");
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
    seenChunks.clear();
    nextSeq = 0;
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
    runOnUiThread(() -> startButton.setText("Start full-duplex session"));
    log("[STOP] Stopping. Uplink will send final WAV chunk.");
  }

  private void uplinkLoop() {
    AudioRecord recorder = null;
    try {
      int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
      int chunkBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000;
      int bufferSize = Math.max(min, chunkBytes * 2);
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize);
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord not initialized");
      recorder.startRecording();
      log("[UPLINK] Recording PCM16 mono 16k. Chunk bytes=" + chunkBytes);
      byte[] pcm = new byte[chunkBytes];
      while (running.get()) {
        int got = readFully(recorder, pcm, chunkBytes);
        if (got > 0) uploadPcmChunk(pcm, got, false);
      }
      byte[] silence = new byte[SAMPLE_RATE / 10 * 2];
      uploadPcmChunk(silence, silence.length, true);
      log("[UPLINK] Final chunk sent. seq=" + (nextSeq - 1));
    } catch (Exception e) {
      log("[UPLINK ERROR] " + e);
    } finally {
      try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
      try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
    }
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

  private void uploadPcmChunk(byte[] pcm, int len, boolean finalChunk) throws Exception {
    int seq = nextSeq++;
    byte[] wav = wavFromPcm16Mono16k(pcm, len);
    String url = BASE_URL + "/fdx/upload?sid=" + enc(sid) + "&seq=" + seq + "&final=" + (finalChunk ? "1" : "0");
    long startMs = System.currentTimeMillis();
    JSONObject res = httpPostBytesJson(url, wav, "audio/wav");
    long doneMs = System.currentTimeMillis();
    log("[UPLOAD] seq=" + seq + " final=" + finalChunk + " wav=" + wav.length + " ms=" + (doneMs - startMs) + " text=" + res.optString("text", "") + " queued_audio=" + res.optInt("queued_audio", -1));
  }

  private void downlinkLoop() {
    log("[DOWNLINK] Polling every 150 ms.");
    while (running.get() || sid != null) {
      try {
        JSONObject poll = httpGetJson(BASE_URL + "/fdx/poll?sid=" + enc(sid));
        JSONArray q = poll.optJSONArray("audio_queue");
        if (q != null) {
          for (int i = 0; i < q.length(); i++) {
            JSONObject chunk = q.getJSONObject(i);
            String chunkId = chunk.getString("chunk_id");
            synchronized (seenChunks) {
              if (seenChunks.contains(chunkId)) continue;
              seenChunks.add(chunkId);
            }
            log("[AUDIO QUEUED] " + chunkId + " bytes=" + chunk.optInt("bytes", -1) + " text=" + chunk.optString("text", ""));
            byte[] wav = httpGetBytes(BASE_URL + "/fdx/audio?sid=" + enc(sid) + "&chunk=" + enc(chunkId));
            log("[AUDIO DOWNLOADED] " + chunkId + " wav=" + wav.length);
            playWavImmediately(wav);
          }
        }
        Thread.sleep(150);
      } catch (Exception e) {
        log("[DOWNLINK ERROR] " + e);
        try { Thread.sleep(500); } catch (Exception ignored) {}
      }
      if (!running.get() && sid != null) {
        try { Thread.sleep(1200); } catch (Exception ignored) {}
        sid = null;
      }
    }
    log("[DOWNLINK] stopped.");
  }

  private void initPlayback() {
    int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT);
    if (android.os.Build.VERSION.SDK_INT >= 23) {
      audioTrack = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(AUDIO_FORMAT).setSampleRate(SAMPLE_RATE).setChannelMask(CHANNEL_CONFIG_OUT).build())
        .setBufferSizeInBytes(Math.max(min, SAMPLE_RATE * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build();
    } else {
      audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT, Math.max(min, SAMPLE_RATE * 2), AudioTrack.MODE_STREAM);
    }
    audioTrack.play();
  }

  private void playWavImmediately(byte[] wav) throws Exception {
    byte[] pcm = pcmFromWav(wav);
    if (audioTrack == null) initPlayback();
    int written = audioTrack.write(pcm, 0, pcm.length);
    log("[PLAY] intent sent pcm=" + pcm.length + " written=" + written);
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
    try { if (audioTrack != null) audioTrack.release(); } catch (Exception ignored) {}
    super.onDestroy();
  }
}
