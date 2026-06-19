package myapp.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class STT {

  private final MainActivity main      ;
  private       Model        model     ;
  private       Recognizer   recognizer;

  private final int          sampleRate       = 16000;
  private final byte[]       recordedData     = new byte[sampleRate * 2 * 60 * 15];
  private       int          recordedBytes    = 0;
  private       int          playbackPosition = 0;

  private boolean isRecording = false;
  private boolean isPlaying   = false;
  private boolean isLive      = false;

  private       AudioRecord   recorder    ;
  private       AudioTrack    player      ;
  private       AudioRecord   liveRecorder;
  private       Thread        liveThread  ;
  private       AcousticEchoCanceler aec  ;
  private       NoiseSuppressor      ns   ;
  private       AutomaticGainControl agc  ;
  private final Object        lock       = new Object       ();
  private       StringBuilder liveBuffer = new StringBuilder();

  public STT(MainActivity main) {
    this.main = main;
    main.print("(STT) created");
  }

  public void setModel(Model model) {
    this.model = model;
    try {
      recognizer = new Recognizer(model, 16000.0f);
      main.print("(STT) Model and recognizer initialized");
    } catch (Exception e) {
      main.print("EXCEPTION(STT init): " + e);
    }
  }

  public boolean isRecording() { return isRecording; }
  public boolean isPlaying  () { return isPlaying  ; }
  public boolean isLive     () { return isLive     ; }

  public void startRecording() {
    main.print("(STT:startRecording) called");
    try {
      int min  = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
      recorder.startRecording();
      isRecording = true;
      new Thread(() -> {
        int   offset;
        synchronized (lock) { offset = recordedBytes; }
        int   chunkSize   = 8192;
        float seconds     =   -1;
        float lastSeconds =    0;
        int   read        =   -1;
        while (isRecording && (offset < recordedData.length)) {
          read = recorder.read(recordedData, offset, Math.min(chunkSize, recordedData.length - offset));
          if (read > 0) {
            offset += read;
            synchronized (lock) { recordedBytes = offset; }
            seconds = ((float) offset / (sampleRate * 2));
            if((seconds - lastSeconds) >= 2.0) {
//              main.print(String.format("RECORD: read=%d offset=%d dur=%.2fs rms=%.1f dBFS", read, offset, seconds, rmsDb(recordedData, offset - read, read)));
              main.print(String.format("RECORDED %.2f sec; ", seconds));
              lastSeconds = seconds;
            }
          }
        }
        main.print(String.format("RECORDED %.2f sec; DONE; ", seconds));
//        main.print(String.format("RECORD: read=%d offset=%d dur=%.2fs rms=%.1f dBFS", read, offset, seconds, rmsDb(recordedData, offset - read, read)));
      }).start();
    } catch (Exception e) {
      main.print("EXCEPTION(RECORD): " + e);
    }
  }

  public void stopRecording() {
    main.print("(STT:stopRecording) called");
    try {
      isRecording = false;
      if (recorder != null) {
        recorder.stop   ();
        recorder.release();
        recorder = null;
      }
    } catch (Exception e) {
      main.print("EXCEPTION(stopRecording): " + e);
    }
  }

  public void startPlayback() {
    main.print("(STT:startPlayback) called");
    try {
      synchronized (lock) { if (playbackPosition >= recordedBytes) playbackPosition = 0; }
      int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      player         = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
      player.play();
      isPlaying = true;
      new Thread(() -> {
        int   chunkSize     =  1024;
        int   localPosition = -   1;
        synchronized (lock) { localPosition = playbackPosition; }
        while (isPlaying) {
          int toWrite;
          synchronized (lock) { toWrite = Math.min(chunkSize, recordedBytes - localPosition); }
          if (toWrite <= 0) break;
          player.write(recordedData, localPosition, toWrite);
          localPosition += toWrite;
          synchronized (lock) { playbackPosition = localPosition; }
        }
        stopPlayback();
      }).start();
    } catch (Exception e) {
      main.print("EXCEPTION(PLAY): " + e);
    }
  }

  public void stopPlayback() {
    main.print("(STT:stopPlayback) called");
    try {
      isPlaying = false;
      if (player != null) {
        player.stop   ();
        player.release();
        player = null;
      }
    } catch (Exception e) {
      main.print("EXCEPTION(stopPlayback): " + e);
    }
  }

  public void startLiveTranscription(String existingText) {
    try {
      if (model == null || recognizer == null) {
        main.print("Cannot start live transcription: model not loaded");
        main.setLiveButtonText("Start Live Transcription");
        main.setLiveEditable(true);
        return;
      }
      try {
        recognizer.reset();
      } catch (Throwable t) {
        main.print("EXCEPTION(recognizer.reset): " + t);
      }
      int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      liveRecorder   = createLiveAudioRecord(bufferSize);
      if (liveRecorder == null) {
        main.print("Cannot start live transcription: failed to init AudioRecord");
        main.setLiveButtonText("Start Live Transcription");
        main.setLiveEditable(true);
        return;
      }
      liveRecorder.startRecording();
      isLive = true;
      main.setLiveButtonText("Stop Live Transcription");
      main.setLiveEditable(false);
      liveBuffer.setLength(0);
      if (existingText != null && !existingText.isEmpty()) {
        liveBuffer.append(existingText);
      }
      liveThread = new Thread(() -> {
        byte[] buf            = new byte[4096];
        float  lastPartialLog = nowSec();
        try {
          while (isLive) {
            int read = liveRecorder.read(buf, 0, buf.length);
            if (!isLive) break;
            if (read <= 0) {
              continue;
            }
            boolean hasFinal = recognizer.acceptWaveForm(buf, read);
            if (hasFinal) {
              String j   = recognizer.getResult();
              String fin = extractTextJson(j, false);
              if (!fin.isEmpty()) {
                if (liveBuffer.length() > 0 && !Character.isWhitespace(liveBuffer.charAt(liveBuffer.length() - 1))) {
                  liveBuffer.append(' ');
                }
                liveBuffer.append(fin);
                main.setLiveText(liveBuffer.toString());
              }
            } else {
              float now = nowSec();
              if ((now - lastPartialLog) >= 0.25f) {
                String pjson = recognizer.getPartialResult();
                String part  = extractTextJson(pjson, true);
                String sep = (liveBuffer.length() > 0 && !Character.isWhitespace(liveBuffer.charAt(liveBuffer.length() - 1))) ? " " : "";
                String shown = part.isEmpty() ? liveBuffer.toString() : (liveBuffer.toString() + sep + part);
                if (!shown.isEmpty()) main.setLiveText(shown);
                lastPartialLog = now;
              }
            }
          }
          String finJson = recognizer.getFinalResult();
          String fin     = extractTextJson(finJson, false);
          if (!fin.isEmpty()) {
            if (liveBuffer.length() > 0 && !Character.isWhitespace(liveBuffer.charAt(liveBuffer.length() - 1))) {
              liveBuffer.append(' ');
            }
            liveBuffer.append(fin);
            main.setLiveText(liveBuffer.toString());
          }
        } catch (Exception e) {
          main.print("EXCEPTION(LIVE loop): " + e);
        } finally {
          try {
            if (liveRecorder != null) {
              liveRecorder.stop   ();
              liveRecorder.release();
            }
          } catch (Exception ignore) {}
          try { if (aec != null) { aec.release(); } } catch (Throwable ignored) {}
          try { if (ns  != null) { ns .release(); } } catch (Throwable ignored) {}
          try { if (agc != null) { agc.release(); } } catch (Throwable ignored) {}
          aec = null;
          ns  = null;
          agc = null;
          isLive = false;
          liveRecorder = null;
          main.runOnUiThread(() -> main.setLiveButtonText("Start Live Transcription"));
          main.setLiveEditable(true);
        }
      });
      liveThread.start();
    } catch (Exception e) {
      main.print("EXCEPTION(startLive): " + e);
      isLive = false;
      main.setLiveButtonText("Start Live Transcription");
      main.setLiveEditable(true);
      try { if (liveRecorder != null) { liveRecorder.release(); liveRecorder = null; } } catch (Exception ignore) {}
    }
  }

  private AudioRecord createLiveAudioRecord(int minBufferSize) {
    int bufferSize = Math.max(minBufferSize, 8192);
    int[] sources;
    // Prefer VOICE_COMMUNICATION for echo cancellation when the phone is also playing TTS audio.
    sources = new int[]{
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
    };
    for (int source : sources) {
      AudioRecord r = null;
      try {
        r = new AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (r.getState() == AudioRecord.STATE_INITIALIZED) {
          main.print("(STT) live AudioRecord initialized source=" + source + " sr=" + r.getSampleRate() + " buf=" + bufferSize);
          enableLiveAudioEffects(r);
          return r;
        }
        try { r.release(); } catch (Throwable ignored) {}
      } catch (Throwable t) {
        main.print("(STT) live AudioRecord init failed source=" + source + ": " + t);
        try { if (r != null) r.release(); } catch (Throwable ignored) {}
      }
    }
    return null;
  }

  private void enableLiveAudioEffects(AudioRecord r) {
    if (r == null) return;
    final int sessionId;
    try {
      sessionId = r.getAudioSessionId();
    } catch (Throwable t) {
      main.print("(STT) audioSessionId failed: " + t);
      return;
    }

    try {
      if (AcousticEchoCanceler.isAvailable()) {
        aec = AcousticEchoCanceler.create(sessionId);
        if (aec != null) {
          aec.setEnabled(true);
          main.print("(STT) AEC enabled");
        }
      } else {
        main.print("(STT) AEC not available");
      }
    } catch (Throwable t) {
      main.print("(STT) AEC enable failed: " + t);
    }

    try {
      if (NoiseSuppressor.isAvailable()) {
        ns = NoiseSuppressor.create(sessionId);
        if (ns != null) {
          ns.setEnabled(true);
          main.print("(STT) NS enabled");
        }
      } else {
        main.print("(STT) NS not available");
      }
    } catch (Throwable t) {
      main.print("(STT) NS enable failed: " + t);
    }

    try {
      if (AutomaticGainControl.isAvailable()) {
        agc = AutomaticGainControl.create(sessionId);
        if (agc != null) {
          agc.setEnabled(false);
          main.print("(STT) AGC available (left disabled)");
        }
      }
    } catch (Throwable t) {
      main.print("(STT) AGC setup failed: " + t);
    }
  }

  public void stopLiveTranscription() {
    isLive = false;
    main.setLiveButtonText("Start Live Transcription");
    main.setLiveEditable(true);
    try {
      if (liveRecorder != null) {
        // Unblock a potentially blocking AudioRecord.read().
        liveRecorder.stop();
      }
    } catch (Exception ignore) {}
    Thread t = liveThread;
    if (t != null) {
      try { t.join(1000); } catch (InterruptedException ignored) {}
      if (!t.isAlive()) liveThread = null;
    }
  }

  public void stopLiveTranscriptionBlocking() {
    isLive = false;
    main.setLiveButtonText("Start Live Transcription");
    main.setLiveEditable(true);
    try {
      if (liveRecorder != null) {
        // Unblock a potentially blocking AudioRecord.read().
        liveRecorder.stop();
      }
    } catch (Exception ignore) {}
    Thread t = liveThread;
    if (t != null) {
      try { t.join(); } catch (InterruptedException ignored) {}
      liveThread = null;
    }
  }

  public void toText() {
    main.print("(STT:toText) called");
    try {
      if (model == null || recognizer == null) {
        main.print("Cannot convert to text: model not loaded");
        return;
      }
      long startTime = System.nanoTime();
      ByteArrayInputStream bais;
      synchronized (lock) {
        bais = new ByteArrayInputStream(recordedData, 0, recordedBytes);
      }
      byte[] buffer = new byte[4096];
      resetBuffer();
      while (true) {
        int read = bais.read(buffer);
        if (read == -1) break;
        if (recognizer.acceptWaveForm(buffer, read)) {
          main.print(extractTextJson(recognizer.getResult(), false));
        } else {
//          main.print(extractTextJson(recognizer.getPartialResult(), true));
        }
      }
      main.print(extractTextJson(recognizer.getFinalResult(), false));
      long endTime = System.nanoTime();
      double elapsedSec = (endTime - startTime) / 1_000_000_000.0;
      main.print(String.format("TO_TEXT: processing took %.3f seconds", elapsedSec));
    } catch (IOException e) {
      main.print("EXCEPTION(toText): " + e);
    }
  }

  private String extractTextJson(String json, boolean partial) {
    try {
      if (json == null || json.isEmpty()) return "";
      JSONObject o = new JSONObject(json);
      String key = partial ? "partial" : "text";
      if (!o.has(key)) return "";
      String s = o.optString(key, "");
      if (s == null) return "";
      s = s.replace("\n", " ").replace("\t", " ").trim();
      return s;
    } catch (Exception e) {
      return "";
    }
  }

  private String trimForLog(String s) {
    if (s == null) return "null";
    if (s.length() > 160) return s.substring(0, 160) + "...";
    return s;
  }

  private float rmsDb(byte[] data, int offset, int len) {
    if (len <= 1) return -120f;
    long sum = 0;
    int samples = 0;
    int end = offset + len;
    for (int i = offset; i + 1 < end; i += 2) {
      int lo = data[i] & 0xFF;
      int hi = data[i + 1];
      short s = (short)((hi << 8) | lo);
      int v = s;
      sum += (long)v * (long)v;
      samples++;
    }
    if (samples == 0) return -120f;
    double mean = sum / (double) samples;
    double rms = Math.sqrt(mean);
    double db = 20.0 * Math.log10(rms / 32768.0 + 1e-12);
    return (float) db;
  }

  private void resetBuffer() {
    synchronized (lock) {
      recordedBytes = 0;
      playbackPosition = 0;
    }
    main.print("BUFFER: Reset complete");
  }

  private float nowSec() {
    return (float) (System.nanoTime() / 1_000_000_000.0);
  }
}
