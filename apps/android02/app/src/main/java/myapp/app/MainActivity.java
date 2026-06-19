package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.method.KeyListener;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import myapp.app.utils.ModelDownloader;

public class MainActivity extends Activity {

  private Button     talkButton    ;
  private Button     sendButton    ;
  private Button     resendButton  ;
  private CheckBox   ttsReplyCheckBox;
  private ProgressBar replyReceiveProgress;
  private SeekBar    replySeekBar  ;
  private TextView   convoText     ;
  private ScrollView convoScroll   ;
  private EditText   liveText      ;
  private ScrollView liveScroll    ;
  private TextView   statusText    ;
  private TextView   replyTimeText ;
  private ScrollView statusScroll  ;

  private STT stt;
  private TextToSpeech systemTts;
  private MediaPlayer remoteTtsPlayer;
  private File remoteTtsFile;
  private volatile boolean systemTtsReady = false;
  private final java.util.concurrent.atomic.AtomicInteger systemTtsGen = new java.util.concurrent.atomic.AtomicInteger(0);

  private KeyListener liveTextKeyListener;

  private volatile boolean speakReplies = true;
  private volatile boolean chatInFlight = false;
  private volatile boolean talkCaptureActive = false;
  private volatile String lastAssistantReplyText = null;
  private volatile String pendingResendText = null;
  private volatile boolean replySeekDragging = false;

  private final Object ttsGateLock = new Object();
  private volatile int ttsGateActiveGen = 0;
  private volatile boolean resumeLiveAfterTts = false;

  private final Object chatHistoryLock = new Object();
  private final java.util.ArrayList<ChatMessage> chatHistory = new java.util.ArrayList<>();
  private static final int CHAT_HISTORY_MAX_MESSAGES = 40;
  private final Object gooseSessionLock = new Object();
  private final Object remoteTtsLock = new Object();
  private final Handler playerUiHandler = new Handler(Looper.getMainLooper());
  private volatile String gooseSessionId = null;
  private volatile int remoteTtsGen = 0;
  private volatile String remoteTtsText = null;
  private volatile boolean remoteTtsPaused = false;
  private volatile int remoteTtsDurationMs = 0;

  private static final int PERMISSION_REQUEST_CODE = 200;
  private static final String GOOSE_BASE_URL = "http://nitro.home.arpa:13101";
  private static final String GOOSE_SECRET = "test";
  private static final String GOOSE_PROVIDER = "openai";
  private static final String GOOSE_MODEL = "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf";
  private static final String GOOSE_WORKING_DIR = "/tmp";
  private static final String NITRO_TTS_BASE_URL = "http://nitro.home.arpa:15101";
  private static final String NITRO_TTS_SECRET = "test";
  private static final String NITRO_TTS_ENGINE = "piper";
  private static final String NITRO_TTS_MODEL_EN = "/data/models/piper/en_US-ryan-high/en_US-ryan-high.onnx";
  private static final String NITRO_TTS_MODEL_DE = "/data/models/piper/de_DE-thorsten-high/de_DE-thorsten-high.onnx";
  private static final int HTTP_CONNECT_TIMEOUT_MS = 5_000;
  private static final int HTTP_READ_TIMEOUT_MS = 30_000;
  private static final int TTS_READ_TIMEOUT_MS = 45_000;

  private static final class ChatMessage {
    final String role;
    final String content;
    ChatMessage(String role, String content) {
      this.role = role;
      this.content = content;
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);

    talkButton = new Button(this);
    talkButton.setText("REC");

    sendButton = new Button(this);
    sendButton.setText("Play");

    resendButton = new Button(this);
    resendButton.setText("Resend");

    styleCompactButton(talkButton);
    styleCompactButton(sendButton);
    styleCompactButton(resendButton);

    ttsReplyCheckBox = new CheckBox(this);
    ttsReplyCheckBox.setChecked(speakReplies);
    ttsReplyCheckBox.setText("Auto");
    ttsReplyCheckBox.setTextSize(12f);
    ttsReplyCheckBox.setPadding(dp(4), dp(0), dp(4), dp(0));

    replyReceiveProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    replyReceiveProgress.setMax(1000);
    replyReceiveProgress.setProgress(0);
    replyReceiveProgress.setVisibility(View.GONE);

    replyTimeText = new TextView(this);
    replyTimeText.setText("00:00 / 00:00");

    replySeekBar = new SeekBar(this);
    replySeekBar.setMax(1000);
    replySeekBar.setProgress(0);

    LinearLayout controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);
    controls.setPadding(dp(6), dp(6), dp(6), dp(2));

    LinearLayout metaRow = new LinearLayout(this);
    metaRow.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    timeParams.setMargins(dp(4), 0, dp(8), 0);
    metaRow.addView(replyTimeText, timeParams);
    LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    checkParams.setMargins(dp(4), 0, dp(4), 0);
    metaRow.addView(ttsReplyCheckBox, checkParams);

    LinearLayout timelineRow = new LinearLayout(this);
    timelineRow.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    seekParams.setMargins(dp(3), 0, dp(3), dp(3));
    timelineRow.addView(replySeekBar, seekParams);

    LinearLayout receiveRow = new LinearLayout(this);
    receiveRow.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams receiveParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    receiveParams.setMargins(dp(3), 0, dp(3), dp(2));
    receiveRow.addView(replyReceiveProgress, receiveParams);

    LinearLayout buttonRow = new LinearLayout(this);
    buttonRow.setOrientation(LinearLayout.HORIZONTAL);
    buttonRow.addView(sendButton, compactButtonParams(1f));
    buttonRow.addView(resendButton, compactButtonParams(1f));
    buttonRow.addView(talkButton, compactButtonParams(1f));

    controls.addView(metaRow);
    controls.addView(receiveRow);
    controls.addView(timelineRow);
    controls.addView(buttonRow);

    // ===== Conversation (scrollable) =====
    TextView convoLabel = new TextView(this);
    convoLabel.setText("Chat");
    convoLabel.setPadding(dp(8), dp(4), dp(8), dp(2));
    layout.addView(convoLabel);

    convoText = new TextView       (this);
    convoText  .setLayoutParams    (new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    convoText  .setTextIsSelectable(true);
    convoText  .setSingleLine      (false);
    convoText  .setMaxLines        (Integer.MAX_VALUE);
    convoText  .setText            ("");
    convoScroll = new ScrollView   (this);
    convoScroll.setFillViewport    (true);
    convoScroll.setLayoutParams    (new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3.2f));
    convoScroll.addView            (convoText);
    layout.addView(convoScroll);

    liveText = new EditText       (this);
    liveText  .setLayoutParams    (new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    liveText  .setTextIsSelectable(true);
    liveText  .setSingleLine      (false);
    liveText  .setHorizontallyScrolling(false);
    liveText  .setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    liveText  .setMinLines        (1);
    liveText  .setMaxLines        (Integer.MAX_VALUE);
    liveText  .setText            ("");
    liveTextKeyListener = liveText.getKeyListener();
    liveScroll = new ScrollView   (this);
    liveScroll.setFillViewport    (true);
    liveScroll.setLayoutParams    (new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
    liveScroll.addView            (liveText);
    layout.addView(liveScroll);

    statusText = new TextView       (this);
    statusText  .setLayoutParams    (new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    statusText  .setTextIsSelectable(true);
    statusText  .setSingleLine      (false);
    statusText  .setMaxLines        (Integer.MAX_VALUE);
    statusText  .setText            ("");
    statusScroll = new ScrollView   (this);
    statusScroll.setFillViewport    (true);
    statusScroll.setLayoutParams    (new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.65f));
    statusScroll.addView            (statusText);
    layout.addView(statusScroll);

    layout.addView(controls);

    setContentView(layout);

    // ===== System TTS fallback (Nitro TTS is preferred for actual replies) =====
    systemTts = new TextToSpeech(this, status -> {
      if (status == TextToSpeech.SUCCESS) {
        systemTtsReady = true;
        try {
          systemTts.setLanguage(Locale.US);
        } catch (Throwable ignored) {}
        try {
          systemTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
              int gen = parseUtteranceGen(utteranceId);
              onTtsPlaybackStart(gen);
            }
            @Override public void onDone(String utteranceId) {
              int gen = parseUtteranceGen(utteranceId);
              onTtsPlaybackEnd(gen);
            }
            @Override public void onError(String utteranceId) {
              int gen = parseUtteranceGen(utteranceId);
              onTtsPlaybackEnd(gen);
            }
          });
        } catch (Throwable t) {
          print("ERROR(SystemTTS listener): " + t);
        }
        print("(SystemTTS) ready");
      } else {
        systemTtsReady = false;
        print("ERROR(SystemTTS): init failed status=" + status);
      }
    });


    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
      }
    }

    talkButton.setOnClickListener(v -> {
      if (chatInFlight) return;
      if (!talkCaptureActive) {
        startTalkCapture();
      } else {
        stopTalkCaptureAndSend();
      }
    });

    sendButton.setOnClickListener(v -> {
      if (chatInFlight) return;
      if (talkCaptureActive) {
        cancelTalkCapture();
      } else {
        toggleReplyPlayback();
      }
    });
    resendButton.setOnClickListener(v -> {
      if (chatInFlight) return;
      final String resend = pendingResendText;
      if (resend == null || resend.trim().isEmpty()) return;
      chatInFlight = true;
      updateActionButtons();
      new Thread(() -> {
        try {
          sendLiveText(resend);
        } finally {
          chatInFlight = false;
          updateActionButtons();
        }
      }, "GooseResendThread").start();
    });
    replySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (!fromUser) return;
        replySeekDragging = true;
        updateReplyTimeLabel(progress, Math.max(progress, remoteTtsDurationMs));
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
        replySeekDragging = true;
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        seekReplyPlayback(seekBar.getProgress());
        replySeekDragging = false;
      }
    });

    ttsReplyCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> speakReplies = isChecked);

    talkButton.setEnabled(false);
    sendButton.setEnabled(false);
    resendButton.setVisibility(View.GONE);

    new Thread(() -> {
      print("(onCreateThread) Creating ModelDownloader");
      ModelDownloader md = new ModelDownloader(this);
      print("(onCreateThread) Starting ModelDownloader");
      md.start();
      while (!md.done) { try { Thread.sleep(200); } catch (InterruptedException ignore) {} }
      print("(onCreateThread) Model download complete, creating Model and STT");
      try {
        java.io.File   modelDir = new java.io  .File (getFilesDir(), myapp.app.utils.ModelDownloader.VOSK_MODEL_NAME);
        print("(onCreateThread) modelDir created");
        org.vosk.Model model    = new org .vosk.Model(modelDir.getAbsolutePath());
        print("(onCreateThread) model created");
        stt = new STT(this);
        print("(onCreateThread) STT created");
        stt.setModel(model);
        print("(onCreateThread) STT.Model set");
        updateActionButtons();
        new Thread(() -> {
          try {
            String sessionId = ensureGooseSessionBlocking();
            print("(Goose) session ready: " + sessionId);
          } catch (Exception e) {
            print("ERROR(Goose init): " + e.getMessage());
          }
        }, "GooseWarmupThread").start();
        print("(onCreateThread) DONE");
      } catch (Exception e) {
        print("EXCEPTION(onCreateThread) (Model load): " + e);
      }
    }).start();
    print("(onCreate) Thread started and DONE");
  }

  public void print(String msg) {
      runOnUiThread(() -> {
          statusText.append(msg + "\n");
          statusScroll.post(() -> statusScroll.fullScroll(ScrollView.FOCUS_DOWN));
          Log.d("main", msg);
      });
  }


  public void setLiveText(String text) {
    runOnUiThread(() -> {
      liveText.setTextKeepState(text);
      int len = liveText.getText().length();
      if (len > 0) liveText.setSelection(len);
      liveScroll.post(() -> liveScroll.fullScroll(ScrollView.FOCUS_DOWN));
    });
  }

  private void appendConversation(String who, String text) {
    if (text == null) return;
    final String w = (who == null || who.trim().isEmpty()) ? "?" : who.trim();
    final String t = text.trim();
    if (t.isEmpty()) return;
    final String block = w + ":\n" + t + "\n\n";
    runOnUiThread(() -> {
      convoText.append(block);
      convoScroll.post(() -> convoScroll.fullScroll(ScrollView.FOCUS_DOWN));
    });
  }

  public void setLiveButtonText(String text) {
    runOnUiThread(this::updateActionButtons);
  }

  private int dp(int value) {
    return Math.round(getResources().getDisplayMetrics().density * value);
  }

  private LinearLayout.LayoutParams compactButtonParams(float weight) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    params.setMargins(dp(3), dp(3), dp(3), dp(3));
    return params;
  }

  private void styleCompactButton(Button button) {
    button.setTextSize(14f);
    button.setMinHeight(0);
    button.setMinimumHeight(0);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    int padH = dp(14);
    int padV = dp(10);
    button.setPadding(padH, padV, padH, padV);
  }

  private void setReplyReceiveWaiting() {
    runOnUiThread(() -> {
      replyReceiveProgress.setVisibility(View.VISIBLE);
      replyReceiveProgress.setIndeterminate(true);
      replyReceiveProgress.setProgress(0);
    });
  }

  private void setReplyReceiveProgress(long received, long total) {
    runOnUiThread(() -> {
      replyReceiveProgress.setVisibility(View.VISIBLE);
      if (total > 0) {
        int progress = (int) Math.max(0L, Math.min(1000L, (received * 1000L) / total));
        replyReceiveProgress.setIndeterminate(false);
        replyReceiveProgress.setProgress(progress);
      } else {
        replyReceiveProgress.setIndeterminate(true);
      }
    });
  }

  private void clearReplyReceiveProgress() {
    runOnUiThread(() -> {
      replyReceiveProgress.setIndeterminate(false);
      replyReceiveProgress.setProgress(0);
      replyReceiveProgress.setVisibility(View.GONE);
    });
  }

  private void updateActionButtons() {
    runOnUiThread(() -> {
      boolean sttReady = stt != null;
      if (talkCaptureActive) {
        talkButton.setVisibility(View.VISIBLE);
        talkButton.setText("Send");
        talkButton.setEnabled(!chatInFlight);
        sendButton.setVisibility(View.VISIBLE);
        sendButton.setText("Cancel");
        sendButton.setEnabled(!chatInFlight);
        resendButton.setVisibility(View.GONE);
        resendButton.setEnabled(false);
        ttsReplyCheckBox.setEnabled(false);
      } else {
        talkButton.setVisibility(View.VISIBLE);
        talkButton.setText("REC");
        talkButton.setEnabled(sttReady && !chatInFlight);
        sendButton.setVisibility(View.VISIBLE);
        sendButton.setText(isReplyPlayingNow() ? "Pause" : "Play");
        sendButton.setEnabled(!chatInFlight && hasReplyToPlay());
        boolean showResend = pendingResendText != null && !pendingResendText.trim().isEmpty();
        resendButton.setVisibility(showResend ? View.VISIBLE : View.GONE);
        resendButton.setEnabled(showResend && !chatInFlight);
        ttsReplyCheckBox.setEnabled(true);
      }
    });
  }

  private boolean hasReplyToPlay() {
    synchronized (remoteTtsLock) {
      if (remoteTtsFile != null && remoteTtsFile.exists()) {
        return true;
      }
    }
    return lastAssistantReplyText != null && !lastAssistantReplyText.trim().isEmpty();
  }

  private boolean isReplyPlayingNow() {
    synchronized (remoteTtsLock) {
      if (remoteTtsPlayer == null) {
        return false;
      }
      try {
        return remoteTtsPlayer.isPlaying();
      } catch (Throwable ignored) {
        return false;
      }
    }
  }

  private String snapshotLiveText() {
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    final String[] holder = new String[]{""};
    runOnUiThread(() -> {
      try {
        holder[0] = liveText.getText().toString();
      } finally {
        latch.countDown();
      }
    });
    try {
      latch.await();
    } catch (InterruptedException ignored) {}
    return holder[0];
  }

  private void stopSystemTtsPlayback() {
    try {
      if (systemTts != null) {
        systemTts.stop();
      }
    } catch (Throwable ignored) {}
    stopRemoteTtsPlayback();
  }

  private void startTalkCapture() {
    if (stt == null) {
      print("ERROR(STT): not initialized");
      return;
    }
    if (stt.isLive()) {
      print("ERROR(Talk): stop live transcription first");
      return;
    }
    if (chatInFlight) {
      return;
    }
    stopSystemTtsPlayback();
    talkCaptureActive = true;
    setLiveText("");
    updateActionButtons();
    try {
      stt.startLiveTranscription("");
    } catch (Throwable t) {
      talkCaptureActive = false;
      updateActionButtons();
      print("ERROR(Talk start): " + t);
    }
  }

  private void cancelTalkCapture() {
    if (!talkCaptureActive) {
      return;
    }
    talkCaptureActive = false;
    updateActionButtons();
    new Thread(() -> {
      try {
        if (stt != null && stt.isLive()) {
          stt.stopLiveTranscriptionBlocking();
        }
      } catch (Throwable t) {
        print("ERROR(Talk cancel): " + t);
      } finally {
        setLiveText("");
        updateActionButtons();
      }
    }, "GooseCancelThread").start();
  }

  private void stopTalkCaptureAndSend() {
    if (!talkCaptureActive) {
      return;
    }
    talkCaptureActive = false;
    chatInFlight = true;
    updateActionButtons();
    new Thread(() -> {
      try {
        if (stt != null && stt.isLive()) {
          stt.stopLiveTranscriptionBlocking();
        }
        String text = snapshotLiveText();
        if (text == null || text.trim().isEmpty()) {
          print("GOOSE: no speech recognized");
          return;
        }
        appendConversation("You", text);
        setLiveText("");
        sendLiveText(text);
      } catch (Throwable t) {
        print("ERROR(Talk send): " + t);
      } finally {
        chatInFlight = false;
        updateActionButtons();
      }
    }, "GooseTalkThread").start();
  }

  public void setLiveEditable(boolean editable) {
    runOnUiThread(() -> {
      if (editable) {
        if (liveText.getKeyListener() == null) {
          liveText.setKeyListener(liveTextKeyListener);
        }
        liveText.setCursorVisible(true);
        liveText.setFocusable(true);
        liveText.setFocusableInTouchMode(true);
      } else {
        liveText.setKeyListener(null);
        liveText.setCursorVisible(false);
        liveText.clearFocus();
      }
    });
  }

  private void sendLiveText(String text) {
    if (text == null) return;
    print("GOOSE: sending...");
    try {
      String reply = gooseReplyBlockingWithRetry(text);
      if (reply == null) {
        pendingResendText = text;
        updateActionButtons();
        print("ERROR(goose): empty reply");
        return;
      }

      String replyTrim = reply.trim();
      if (replyTrim.isEmpty()) {
        pendingResendText = text;
        updateActionButtons();
        print("ERROR(goose): empty reply");
        return;
      }
      pendingResendText = null;
      lastAssistantReplyText = replyTrim;
      updateActionButtons();
      synchronized (chatHistoryLock) {
        chatHistory.add(new ChatMessage("user", text));
        chatHistory.add(new ChatMessage("assistant", replyTrim));
        trimChatHistoryLocked();
      }

      appendConversation("Goose", replyTrim);
      print("GOOSE: reply received");

      if (speakReplies) speakReplyText(replyTrim);
    } catch (Exception e) {
      pendingResendText = text;
      updateActionButtons();
      print("ERROR(goose): " + e);
    } finally {
      print("GOOSE: done");
    }
  }

  private String gooseReplyBlockingWithRetry(String userText) throws Exception {
    try {
      return gooseReplyBlocking(userText);
    } catch (Exception first) {
      if (!looksLikeInvalidGooseSession(first)) {
        throw first;
      }
      invalidateGooseSession();
      print("(Goose) session reset; retrying once");
      return gooseReplyBlocking(userText);
    }
  }

  private boolean looksLikeInvalidGooseSession(Exception e) {
    String msg = (e == null || e.getMessage() == null) ? "" : e.getMessage().toLowerCase(Locale.US);
    return msg.contains("no agent for session id")
            || msg.contains("session")
            || msg.contains("http 404")
            || msg.contains("http 424");
  }

  private void invalidateGooseSession() {
    synchronized (gooseSessionLock) {
      gooseSessionId = null;
    }
  }

  private String ensureGooseSessionBlocking() throws Exception {
    synchronized (gooseSessionLock) {
      if (gooseSessionId != null && !gooseSessionId.trim().isEmpty()) {
        return gooseSessionId;
      }

      HttpURLConnection conn = null;
      try {
        URL url = new URL(GOOSE_BASE_URL + "/agent/start");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Secret-Key", GOOSE_SECRET);

        JSONObject payload = new JSONObject();
        payload.put("working_dir", GOOSE_WORKING_DIR);

        byte[] body = payload.toString().getBytes("UTF-8");
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = conn.getOutputStream()) {
          os.write(body);
        }

        int code = conn.getResponseCode();
        String responseBody;
        try (InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
          responseBody = readAll(is);
        }

        if (code < 200 || code >= 300) {
          throw new Exception("HTTP " + code + " " + conn.getResponseMessage() + ": " + truncate(responseBody, 500));
        }

        JSONObject json = new JSONObject(responseBody);
        String sessionId = json.optString("id", "").trim();
        if (sessionId.isEmpty()) {
          throw new Exception("Missing Goose session id: " + truncate(responseBody, 500));
        }

        gooseSessionId = sessionId;
      } finally {
        if (conn != null) conn.disconnect();
      }

      updateGooseProviderBlocking(gooseSessionId);
      resumeGooseSessionBlocking(gooseSessionId);
      return gooseSessionId;
    }
  }

  private void updateGooseProviderBlocking(String sessionId) throws Exception {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(GOOSE_BASE_URL + "/agent/update_provider");
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("X-Secret-Key", GOOSE_SECRET);

      JSONObject payload = new JSONObject();
      payload.put("provider", GOOSE_PROVIDER);
      payload.put("model", GOOSE_MODEL);
      payload.put("session_id", sessionId);

      byte[] body = payload.toString().getBytes("UTF-8");
      conn.setFixedLengthStreamingMode(body.length);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int code = conn.getResponseCode();
      String responseBody;
      try (InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
        responseBody = readAll(is);
      }

      if (code < 200 || code >= 300) {
        throw new Exception("HTTP " + code + " " + conn.getResponseMessage() + ": " + truncate(responseBody, 500));
      }
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private void resumeGooseSessionBlocking(String sessionId) throws Exception {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(GOOSE_BASE_URL + "/agent/resume");
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("X-Secret-Key", GOOSE_SECRET);

      JSONObject payload = new JSONObject();
      payload.put("session_id", sessionId);
      payload.put("load_model_and_extensions", true);

      byte[] body = payload.toString().getBytes("UTF-8");
      conn.setFixedLengthStreamingMode(body.length);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int code = conn.getResponseCode();
      String responseBody;
      try (InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
        responseBody = readAll(is);
      }

      if (code < 200 || code >= 300) {
        throw new Exception("HTTP " + code + " " + conn.getResponseMessage() + ": " + truncate(responseBody, 500));
      }
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private String gooseReplyBlocking(String userText) throws Exception {
    String sessionId = ensureGooseSessionBlocking();
    HttpURLConnection conn = null;
    try {
      URL url = new URL(GOOSE_BASE_URL + "/reply");
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "text/event-stream");
      conn.setRequestProperty("X-Secret-Key", GOOSE_SECRET);

      JSONObject textPart = new JSONObject();
      textPart.put("type", "text");
      textPart.put("text", userText);

      JSONArray content = new JSONArray();
      content.put(textPart);

      JSONObject metadata = new JSONObject();
      metadata.put("userVisible", true);
      metadata.put("agentVisible", true);

      JSONObject userMessage = new JSONObject();
      userMessage.put("role", "user");
      userMessage.put("created", System.currentTimeMillis() / 1000L);
      userMessage.put("metadata", metadata);
      userMessage.put("content", content);

      JSONObject payload = new JSONObject();
      payload.put("session_id", sessionId);
      payload.put("user_message", userMessage);

      byte[] body = payload.toString().getBytes("UTF-8");
      conn.setFixedLengthStreamingMode(body.length);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int code = conn.getResponseCode();
      if (code < 200 || code >= 300) {
        String responseBody;
        try (InputStream is = conn.getErrorStream()) {
          responseBody = readAll(is);
        }
        throw new Exception("HTTP " + code + " " + conn.getResponseMessage() + ": " + truncate(responseBody, 500));
      }

      StringBuilder reply = new StringBuilder();
      try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.isEmpty() || !line.startsWith("data: ")) {
            continue;
          }

          JSONObject event = new JSONObject(line.substring(6));
          String type = event.optString("type", "");
          if ("Message".equals(type)) {
            JSONObject message = event.optJSONObject("message");
            if (message == null) continue;
            JSONArray messageContent = message.optJSONArray("content");
            if (messageContent == null) continue;
            for (int i = 0; i < messageContent.length(); i++) {
              JSONObject part = messageContent.optJSONObject(i);
              if (part == null) continue;
              if ("text".equals(part.optString("type", ""))) {
                String piece = part.optString("text", "");
                if (!piece.isEmpty()) reply.append(piece);
              }
            }
          } else if ("Error".equals(type)) {
            throw new Exception(event.optString("error", "Unknown Goose error"));
          } else if ("Finish".equals(type)) {
            break;
          }
        }
      }

      return reply.toString();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static String readAll(InputStream is) throws Exception {
    if (is == null) return "";
    StringBuilder sb = new StringBuilder();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
      String line;
      while ((line = br.readLine()) != null) sb.append(line).append('\n');
    }
    return sb.toString();
  }

  private String readAllWithProgress(HttpURLConnection conn, InputStream is) throws Exception {
    if (is == null) return "";
    long total = -1L;
    try {
      total = conn.getContentLengthLong();
    } catch (Throwable ignored) {}
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    long received = 0L;
    int n;
    while ((n = is.read(buf)) >= 0) {
      if (n == 0) continue;
      out.write(buf, 0, n);
      received += n;
      setReplyReceiveProgress(received, total);
    }
    return out.toString("UTF-8");
  }

  private static String truncate(String s, int maxLen) {
    if (s == null) return "";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "...";
  }

  private void trimChatHistoryLocked() {
    while (chatHistory.size() > CHAT_HISTORY_MAX_MESSAGES) {
      chatHistory.remove(0);
    }
  }

  private void toggleReplyPlayback() {
    synchronized (remoteTtsLock) {
      if (remoteTtsPlayer != null && remoteTtsPlayer.isPlaying()) {
        pauseReplyPlayback();
        return;
      }
    }
    playLastReply(false);
  }

  private void speakReplyText(String text) {
    if (text == null) return;
    final String t = text.trim();
    if (t.isEmpty()) return;
    new Thread(() -> {
      try {
        ensureReplyAudioReadyBlocking(t);
        playLastReply(true);
      } catch (Throwable nitroErr) {
        print("ERROR(NitroTTS): " + nitroErr);
        speakSystemTts(t);
      }
    }, "NitroTtsThread").start();
  }

  private void ensureReplyAudioReadyBlocking(String text) throws Exception {
    synchronized (remoteTtsLock) {
      if (remoteTtsFile != null && remoteTtsFile.exists() && text.equals(remoteTtsText)) {
        return;
      }
    }

    HttpURLConnection conn = null;
    try {
      setReplyReceiveWaiting();
      URL url = new URL(NITRO_TTS_BASE_URL + "/synthesize");
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("POST");
      conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(TTS_READ_TIMEOUT_MS);
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setRequestProperty("X-Secret-Key", NITRO_TTS_SECRET);

      JSONObject payload = new JSONObject();
      payload.put("engine", NITRO_TTS_ENGINE);
      payload.put("text", text);
      payload.put("return_audio", "base64");
      payload.put("model", chooseNitroTtsModel(text));
      payload.put("speaker_id", 0);
      payload.put("sentence_silence", 0.18);

      byte[] body = payload.toString().getBytes("UTF-8");
      conn.setFixedLengthStreamingMode(body.length);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int code = conn.getResponseCode();
      String responseBody;
      try (InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
        responseBody = readAllWithProgress(conn, is);
      }
      if (code < 200 || code >= 300) {
        throw new Exception("HTTP " + code + " " + conn.getResponseMessage() + ": " + truncate(responseBody, 500));
      }

      JSONObject json = new JSONObject(responseBody);
      String audioB64 = json.optString("audio_b64", "");
      if (audioB64.isEmpty()) {
        throw new Exception("Nitro TTS returned no audio");
      }
      byte[] wavBytes = Base64.decode(audioB64, Base64.DEFAULT);
      cacheRemoteWav(text, wavBytes);
    } finally {
      clearReplyReceiveProgress();
      if (conn != null) conn.disconnect();
    }
  }

  private String chooseNitroTtsModel(String text) {
    String s = (text == null ? "" : text).toLowerCase(Locale.US);
    if (s.indexOf('ä') >= 0 || s.indexOf('ö') >= 0 || s.indexOf('ü') >= 0 || s.indexOf('ß') >= 0) {
      return NITRO_TTS_MODEL_DE;
    }
    int germanHits = 0;
    String[] markers = new String[]{" der ", " die ", " das ", " und ", " ist ", " nicht ", " ich ", " du ", " wir "};
    String padded = " " + s + " ";
    for (String marker : markers) {
      if (padded.contains(marker)) germanHits++;
    }
    return germanHits >= 2 ? NITRO_TTS_MODEL_DE : NITRO_TTS_MODEL_EN;
  }

  private void cacheRemoteWav(String text, byte[] wavBytes) throws Exception {
    if (wavBytes == null || wavBytes.length == 0) {
      throw new Exception("empty Nitro TTS audio");
    }

    final File wavFile = new File(getCacheDir(), "last_reply.wav");
    try (FileOutputStream fos = new FileOutputStream(wavFile)) {
      fos.write(wavBytes);
    }
    synchronized (remoteTtsLock) {
      remoteTtsFile = wavFile;
      remoteTtsText = text;
      remoteTtsDurationMs = 0;
      remoteTtsPaused = false;
    }
    runOnUiThread(() -> {
      replySeekBar.setProgress(0);
      updateReplyTimeLabel(0, 0);
    });
    updateActionButtons();
  }

  private void playLastReply(boolean restartFromStart) {
    new Thread(() -> {
      try {
        File wavFile;
        synchronized (remoteTtsLock) {
          wavFile = remoteTtsFile;
        }
        if (wavFile == null || !wavFile.exists()) {
          String last = lastAssistantReplyText;
          if (last == null || last.trim().isEmpty()) {
            print("No reply audio cached yet");
            return;
          }
          ensureReplyAudioReadyBlocking(last.trim());
          synchronized (remoteTtsLock) {
            wavFile = remoteTtsFile;
          }
        }
        startCachedReplyPlayback(wavFile, restartFromStart);
      } catch (Throwable t) {
        print("ERROR(Reply playback): " + t);
      }
    }, "ReplyPlayThread").start();
  }

  private void startCachedReplyPlayback(File wavFile, boolean restartFromStart) throws Exception {
    if (wavFile == null || !wavFile.exists()) {
      throw new Exception("cached reply audio missing");
    }
    synchronized (remoteTtsLock) {
      if (remoteTtsPlayer != null && remoteTtsPaused && !restartFromStart) {
        remoteTtsPlayer.start();
        remoteTtsPaused = false;
        onTtsPlaybackStart(remoteTtsGen);
        scheduleReplyProgressUpdate();
        updateActionButtons();
        return;
      }
      releaseRemoteTtsPlayerLocked(false, false);
    }

    final int gen = systemTtsGen.incrementAndGet();
    final MediaPlayer mp = new MediaPlayer();
    mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
    mp.setDataSource(wavFile.getAbsolutePath());
    mp.prepare();
    mp.setOnCompletionListener(done -> {
      synchronized (remoteTtsLock) {
        releaseRemoteTtsPlayerLocked(false, true);
      }
      onTtsPlaybackEnd(gen);
      updateActionButtons();
      runOnUiThread(() -> {
        replySeekBar.setProgress(replySeekBar.getMax());
        updateReplyTimeLabel(remoteTtsDurationMs, remoteTtsDurationMs);
      });
    });
    mp.setOnErrorListener((player, what, extra) -> {
      print("ERROR(NitroTTS playback): what=" + what + " extra=" + extra);
      synchronized (remoteTtsLock) {
        releaseRemoteTtsPlayerLocked(false, false);
      }
      onTtsPlaybackEnd(gen);
      updateActionButtons();
      return true;
    });

    synchronized (remoteTtsLock) {
      remoteTtsPlayer = mp;
      remoteTtsGen = gen;
      remoteTtsPaused = false;
      remoteTtsDurationMs = Math.max(mp.getDuration(), 0);
    }

    runOnUiThread(() -> {
      replySeekBar.setMax(Math.max(mp.getDuration(), 1));
      replySeekBar.setProgress(0);
      updateReplyTimeLabel(0, mp.getDuration());
    });

    onTtsPlaybackStart(gen);
    mp.start();
    scheduleReplyProgressUpdate();
    updateActionButtons();
  }

  private void pauseReplyPlayback() {
    synchronized (remoteTtsLock) {
      if (remoteTtsPlayer == null || !remoteTtsPlayer.isPlaying()) {
        return;
      }
      try {
        remoteTtsPlayer.pause();
        remoteTtsPaused = true;
      } catch (Throwable ignored) {}
      onTtsPlaybackEnd(remoteTtsGen);
    }
    updateActionButtons();
  }

  private void stopReplyPlayback() {
    synchronized (remoteTtsLock) {
      int gen = remoteTtsGen;
      releaseRemoteTtsPlayerLocked(false, false);
      if (gen != 0) {
        onTtsPlaybackEnd(gen);
      }
    }
    runOnUiThread(() -> {
      replySeekBar.setProgress(0);
      updateReplyTimeLabel(0, remoteTtsDurationMs);
    });
    updateActionButtons();
  }

  private void seekReplyPlayback(int positionMs) {
    synchronized (remoteTtsLock) {
      if (remoteTtsPlayer == null) {
        return;
      }
      try {
        remoteTtsPlayer.seekTo(Math.max(positionMs, 0));
      } catch (Throwable ignored) {}
    }
  }

  private void stopRemoteTtsPlayback() {
    synchronized (remoteTtsLock) {
      int gen = remoteTtsGen;
      releaseRemoteTtsPlayerLocked(true, false);
      if (gen != 0) {
        onTtsPlaybackEnd(gen);
      }
    }
    updateActionButtons();
  }

  private void releaseRemoteTtsPlayerLocked(boolean dropCache, boolean keepProgress) {
    MediaPlayer mp = remoteTtsPlayer;
    remoteTtsPlayer = null;
    remoteTtsGen = 0;
    remoteTtsPaused = false;
    if (mp != null) {
      try { mp.stop(); } catch (Throwable ignored) {}
      try { mp.reset(); } catch (Throwable ignored) {}
      try { mp.release(); } catch (Throwable ignored) {}
    }
    if (dropCache && remoteTtsFile != null) {
      try { remoteTtsFile.delete(); } catch (Throwable ignored) {}
      remoteTtsFile = null;
      remoteTtsText = null;
      remoteTtsDurationMs = 0;
    }
    if (!keepProgress) {
      playerUiHandler.removeCallbacksAndMessages(null);
    }
  }

  private void scheduleReplyProgressUpdate() {
    playerUiHandler.removeCallbacksAndMessages(null);
    playerUiHandler.post(new Runnable() {
      @Override
      public void run() {
        MediaPlayer mp;
        int duration;
        synchronized (remoteTtsLock) {
          mp = remoteTtsPlayer;
          duration = remoteTtsDurationMs;
        }
        if (mp == null) {
          return;
        }
        int pos;
        boolean playing;
        try {
          pos = mp.getCurrentPosition();
          playing = mp.isPlaying();
        } catch (Throwable ignored) {
          return;
        }
        if (!replySeekDragging) {
          replySeekBar.setMax(Math.max(duration, 1));
          replySeekBar.setProgress(Math.max(pos, 0));
          updateReplyTimeLabel(pos, duration);
        }
        if (playing) {
          playerUiHandler.postDelayed(this, 200);
        }
      }
    });
  }

  private void updateReplyTimeLabel(int positionMs, int durationMs) {
    int pos = Math.max(positionMs, 0);
    int dur = Math.max(durationMs, 0);
    replyTimeText.setText(formatMs(pos) + " / " + formatMs(dur));
  }

  private String formatMs(int ms) {
    int total = Math.max(ms, 0) / 1000;
    int minutes = total / 60;
    int seconds = total % 60;
    return String.format(Locale.US, "%02d:%02d", minutes, seconds);
  }

  private void clearReplyCache() {
    synchronized (remoteTtsLock) {
      releaseRemoteTtsPlayerLocked(true, false);
    }
    runOnUiThread(() -> {
      replySeekBar.setMax(1000);
      replySeekBar.setProgress(0);
      updateReplyTimeLabel(0, 0);
    });
    updateActionButtons();
  }

  private int parseUtteranceGen(String utteranceId) {
    try {
      if (utteranceId == null) return 0;
      return Integer.parseInt(utteranceId);
    } catch (Throwable ignored) {
      return 0;
    }
  }

  private void speakSystemTts(String text) {
    if (text == null) return;
    final String t = text.trim();
    if (t.isEmpty()) return;
    if (systemTts == null || !systemTtsReady) {
      print("ERROR(SystemTTS): not ready");
      return;
    }
    int gen = systemTtsGen.incrementAndGet();
    try {
      systemTts.speak(t, TextToSpeech.QUEUE_FLUSH, null, String.valueOf(gen));
    } catch (Throwable e) {
      print("ERROR(SystemTTS speak): " + e);
      onTtsPlaybackEnd(gen);
    }
  }

  // Called from TTS playback thread(s) to avoid the mic transcribing the speaker output.
  public void onTtsPlaybackStart(int gen) {
    boolean stopLive = false;
    synchronized (ttsGateLock) {
      ttsGateActiveGen = gen;
      stopLive = (stt != null && stt.isLive());
      resumeLiveAfterTts = stopLive;
    }
    if (stopLive) {
      print("(TTS) pausing live transcription (avoid echo)");
      try {
        stt.stopLiveTranscriptionBlocking();
      } catch (Throwable t) {
        print("ERROR(STT pause): " + t);
      }
    }
  }

  public void onTtsPlaybackEnd(int gen) {
    boolean resume = false;
    synchronized (ttsGateLock) {
      if (gen != ttsGateActiveGen) {
        return; // stale end for an older playback
      }
      ttsGateActiveGen = 0;
      resume = resumeLiveAfterTts;
      resumeLiveAfterTts = false;
    }
    if (resume) {
      print("(TTS) resuming live transcription");
      try {
        if (stt != null && !stt.isLive()) {
          stt.startLiveTranscription(liveText.getText().toString());
        }
      } catch (Throwable t) {
        print("ERROR(STT resume): " + t);
      }
    }
  }

  @Override
  protected void onDestroy() {
    try {
      stopRemoteTtsPlayback();
    } catch (Throwable ignored) {}
    try {
      if (systemTts != null) {
        systemTts.shutdown();
      }
    } catch (Throwable ignored) {}
    systemTts = null;
    super.onDestroy();
  }
}
