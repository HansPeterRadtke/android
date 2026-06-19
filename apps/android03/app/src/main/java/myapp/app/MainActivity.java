package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
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

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

public class MainActivity extends Activity {

  private ScrollView scrollView;
  private TextView textView;
  private final AtomicReference<WebSocketClient> wsClientRef = new AtomicReference<>(null);
  private boolean isStreaming = false;
  private Thread streamingThread;
  private Button streamButton;
  private AudioTrack audioTrack;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
    }

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);

    streamButton = new Button(this);
    streamButton.setText("Start Streaming");
    layout.addView(streamButton, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    textView = new TextView(this);
    textView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    textView.setTextIsSelectable(true);
    textView.setSingleLine(false);
    textView.setMaxLines(Integer.MAX_VALUE);

    scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);
    scrollView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    scrollView.addView(textView);
    layout.addView(scrollView);

    setContentView(layout);

    initWebSocket();

    streamButton.setOnClickListener(v -> {
      if (isStreaming) {
        isStreaming = false;
        streamButton.setText("Start Streaming");
        print("[INFO] Stopped streaming.");
        if (streamingThread != null && streamingThread.isAlive()) {
          try {
            streamingThread.join();
          } catch (InterruptedException e) {
            print("[ERROR] Thread join interrupted: " + e);
          }
        }
      } else {
        isStreaming = true;
        streamButton.setText("Stop Streaming");
        print("[INFO] Started streaming.");
        startStreaming();
      }
    });
  }

  private void startStreaming() {
    streamingThread = new Thread(() -> {
      try {
        WebSocketClient wsClient;
        int retries = 0;
        while (((wsClient = wsClientRef.get()) == null || !wsClient.isOpen()) && retries < 50) {
          print("[DEBUG] Waiting for WebSocket to open...");
          Thread.sleep(100);
          retries++;
        }
        if (wsClient == null || !wsClient.isOpen()) {
          print("[ERROR] WebSocket not ready. Reinitializing.");
          initWebSocket();
          return;
        }

        int sampleRate = 16000;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);

        VadWebRTC vad = Vad.builder()
            .setSampleRate(SampleRate.SAMPLE_RATE_16K)
            .setFrameSize(FrameSize.FRAME_SIZE_320)
            .setMode(Mode.NORMAL)
            .build();

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
          print("[ERROR] AudioRecorder not initialized.");
          return;
        }

        recorder.startRecording();
        audioTrack.play();

        byte[] buffer = new byte[bufferSize];

        while (isStreaming) {
          int read = recorder.read(buffer, 0, buffer.length);
          if (read > 0) {
            byte[] chunk = new byte[read];
            System.arraycopy(buffer, 0, chunk, 0, read);
            boolean isSpeech = vad.isSpeech(chunk);
            if (isSpeech) {
              try {
                wsClient.send(chunk);
              } catch (Exception e) {
                print("[ERROR] Failed to send chunk. Reinitializing. " + e);
                initWebSocket();
                break;
              }
            } else {
              print("[DEBUG] Silence skipped");
            }
          }
        }

        recorder.stop();
        recorder.release();
        audioTrack.stop();
        audioTrack.release();

      } catch (Exception e) {
        print("[ERROR] Streaming failed: " + e);
      }
    });
    streamingThread.start();
  }

  private void initWebSocket() {
    try {
      URI uri = new URI("ws://android.jonnyonthefly.org");
      WebSocketClient newClient = new WebSocketClient(uri) {
        @Override
        public void onOpen(ServerHandshake handshake) {
          print("[INFO] WebSocket Connected");
          runOnUiThread(() -> streamButton.setEnabled(true));
        }

        @Override
        public void onMessage(ByteBuffer bytes) {
          if (audioTrack != null) {
            byte[] data = new byte[bytes.remaining()];
            bytes.get(data);
            audioTrack.write(data, 0, data.length);
          }
        }

        @Override
        public void onMessage(String message) {
          print("[INFO] Received text message (ignored): " + message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
          print("[INFO] WebSocket Closed: " + reason);
          runOnUiThread(() -> streamButton.setEnabled(false));
        }

        @Override
        public void onError(Exception ex) {
          print("[ERROR] WebSocket Error: " + ex);
        }
      };
      wsClientRef.set(newClient);
      newClient.connect();
    } catch (Exception e) {
      print("[ERROR] WebSocket Init Failed: " + e);
    }
  }

  private void print(String msg) {
    runOnUiThread(() -> {
      textView.append("[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "] " + msg + "\n");
      scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    });
  }
}