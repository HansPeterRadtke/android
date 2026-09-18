package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.AudioRouteController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends Activity {
  private static final String CONVERSATION_PREF_KEY = "conversation_id_v2";
  private static final String HISTORY_PREF_KEY = "history_text_v2";
  private static final String MIC_DEVICE_PREF_KEY = "microphone_device_id_v2";
  private static final String MIC_LABEL_PREF_KEY = "microphone_label_v2";
  private static final String MIC_USER_SELECTED_PREF_KEY = "microphone_user_selected_v2";
  private static final String AUTO_SEND_PREF_KEY = "send_automatically_v3";
  private static final String AUTO_TRANSCRIBE_PREF_KEY = "transcribe_automatically_v1";
  private static final String DRAFT_PREF_KEY = "current_message_v1";
  private static final String VOCAB_PREF_KEY = "vocabulary_v1";
  private static final String VOCAB_CONFIGURED_PREF_KEY = "vocabulary_configured_v1";
  private static final String MANUAL_RECORDING_FILE = "voice_unsent_manual.pcm";
  private static final int PERMISSION_REQUEST = 2;
  private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  private enum ForegroundMode {
    READY, CONNECTING, VERIFYING, LISTENING, RECORDING_LOCAL, TRANSCRIBING,
    THINKING, BUFFERING, SPEAKING, RECONNECTING, UNAVAILABLE, PERMISSION,
    MICROPHONE_ERROR, REVIEW, FINISHING
  }

  private VoiceAppConfig appConfig;
  private SharedPreferences historyPrefs;
  private Handler mainHandler;
  private OkHttpClient wsClient;
  private ConnectivityManager connectivityManager;
  private ConnectivityManager.NetworkCallback networkCallback;

  private TextView statusView;
  private TextView statusDetailView;
  private TextView componentHealthView;
  private TextView workerView;
  private TextView conversationView;
  private ScrollView conversationScrollView;
  private LinearLayout transcriptPanel;
  private LinearLayout replayRow;
  private TextInputEditText draftEdit;
  private MaterialButton sendDraftButton;
  private MaterialButton transcribeButton;
  private MaterialButton playUserButton;
  private MaterialButton playAnswerButton;
  private VoicePcmPlayerView userPlayer;
  private VoicePcmPlayerView assistantPlayer;
  private volatile VoicePcmPlayerView activePcmPlayer;
  private MaterialButton startButton;
  private MaterialButton settingsButton;
  private MaterialButton diagnosticsButton;

  private final List<AudioInputOption> microphoneInputs = new ArrayList<>();
  private volatile int selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
  private volatile String currentMicrophoneLabel = "";
  private volatile double currentInputDbfs = -120.0;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicBoolean connectionWanted = new AtomicBoolean(false);
  private volatile boolean captureStartPending = false;
  private volatile boolean manualTranscribePending = false;
  private volatile boolean manualTranscriptPendingSubmission = false;
  private volatile boolean typedSendPending = false;
  private volatile boolean stopRequested = false;
  private volatile int nextSeq = 0;
  private volatile long droppedLiveFrames = 0L;
  private volatile long droppedPlaybackChunks = 0L;
  private Thread uplinkThread;

  private volatile WebSocket webSocket;
  private final AtomicBoolean wsConnecting = new AtomicBoolean(false);
  private final VoiceConnectionTracker connectionTracker = new VoiceConnectionTracker();
  private volatile long reconnectScheduledGeneration = -1L;
  private Runnable reconnectRunnable;
  private Runnable stopTimeoutRunnable;
  private Runnable connectionMonitor;
  private volatile String diagnosticConnectionState = "not connected";

  private String conversationId;
  private volatile boolean autoTranscribe = true;
  private volatile boolean autoSend = true;
  private volatile boolean serverHelloReady = false;
  private volatile String serverVersion = "";
  private volatile String serverAsr = "";
  private volatile String serverTts = "";
  private volatile boolean serverReady = false;
  private volatile boolean serverSttReady = false;
  private volatile boolean serverAgentReady = false;
  private volatile boolean serverTtsReady = false;
  private final ArrayList<String> currentVocabulary = new ArrayList<>();
  private volatile boolean userVocabularyConfigured = false;
  private final Object manualRecordingLock = new Object();
  private final ByteArrayOutputStream manualRecordingBuffer = new ByteArrayOutputStream();
  private volatile boolean manualRecordingAvailable = false;
  private volatile long lastLevelUiMs = 0L;
  private volatile String pendingTurnId = "";
  private volatile String latestUserTurnId = "";
  private volatile String latestAssistantTurnId = "";
  private final ConversationTextModel conversationTextModel = new ConversationTextModel();
  private volatile boolean replayUserAvailable = false;
  private volatile boolean replayAssistantAvailable = false;
  private volatile int currentWorkerSequence = 0;
  private volatile ForegroundMode foregroundMode = ForegroundMode.READY;

  private AcousticEchoCanceler aec;
  private NoiseSuppressor noiseSuppressor;
  private AutomaticGainControl agc;
  private final Object diagnosticLock = new Object();
  private String diagnosticText = "";

  private final class WindowFractionScrollView extends ScrollView {
    WindowFractionScrollView(Context context) {
      super(context);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      if (useWideShortLayout()) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        return;
      }
      int available = MeasureSpec.getSize(heightMeasureSpec);
      int minimum = getResources().getDimensionPixelSize(R.dimen.voice_controls_min_height);
      float fraction = getResources().getFraction(R.fraction.voice_controls_max_fraction, 1, 1);
      int cap = available > 0
          ? Math.min(available, Math.max(minimum, Math.round(available * fraction)))
          : minimum;
      super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    appConfig = VoiceAppConfig.from(this);
    mainHandler = new Handler(Looper.getMainLooper());
    historyPrefs = getSharedPreferences("voice_agent_history", MODE_PRIVATE);
    selectedDeviceId = historyPrefs.getInt(MIC_DEVICE_PREF_KEY, AudioInputOption.DEFAULT_DEVICE_ID);
    autoTranscribe = historyPrefs.getBoolean(AUTO_TRANSCRIBE_PREF_KEY, true);
    autoSend = VoiceAutomationPolicy.effectiveAutoSend(autoTranscribe, historyPrefs.getBoolean(AUTO_SEND_PREF_KEY, true));
    userVocabularyConfigured = historyPrefs.getBoolean(VOCAB_CONFIGURED_PREF_KEY, false);
    loadLocalVocabulary();
    conversationId = historyPrefs.getString(CONVERSATION_PREF_KEY, "");
    if (conversationId == null || conversationId.isEmpty()) {
      conversationId = "android-v2-" + java.util.UUID.randomUUID();
      historyPrefs.edit().putString(CONVERSATION_PREF_KEY, conversationId).apply();
    }
    wsClient = new OkHttpClient.Builder()
        .connectTimeout(appConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build();
    connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    buildScreen();
    registerNetworkObserver();
    refreshPreferredMicrophonePreview();
    startConnectionMonitor();
    restoreManualRecording();
    connectionWanted.set(true);
    if (hasUsableNetwork()) connectWebSocket();
    else updateServiceHealth();
  }

  private boolean useWideShortLayout() {
    android.content.res.Configuration config = getResources().getConfiguration();
    return config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp;
  }

  private void buildScreen() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(18), dp(16), dp(18), dp(14));

    statusView = new TextView(this);
    statusView.setId(R.id.voice_status);
    statusView.setTextSize(22);
    statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    statusView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    root.addView(statusView, fullWrap());

    statusDetailView = new TextView(this);
    statusDetailView.setId(R.id.voice_status_detail);
    statusDetailView.setTextSize(13);
    statusDetailView.setPadding(0, dp(2), 0, dp(8));
    root.addView(statusDetailView, fullWrap());

    componentHealthView = new TextView(this);
    componentHealthView.setId(R.id.voice_component_health);
    componentHealthView.setTextSize(13);
    componentHealthView.setPadding(0, 0, 0, dp(8));
    root.addView(componentHealthView, fullWrap());

    LinearLayout secondaryActions = new LinearLayout(this);
    secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
    settingsButton = new MaterialButton(this);
    settingsButton.setId(R.id.voice_settings);
    settingsButton.setText(R.string.settings);
    settingsButton.setMinHeight(dp(48));
    diagnosticsButton = new MaterialButton(this);
    diagnosticsButton.setId(R.id.voice_diagnostics);
    diagnosticsButton.setText(R.string.diagnostics);
    diagnosticsButton.setMinHeight(dp(48));
    secondaryActions.addView(settingsButton, weightedWrap());
    secondaryActions.addView(diagnosticsButton, weightedWrap());
    root.addView(secondaryActions, fullWrap());

    workerView = new TextView(this);
    workerView.setId(R.id.voice_worker_status);
    workerView.setTextSize(14);
    workerView.setPadding(dp(10), dp(8), dp(10), dp(8));
    workerView.setVisibility(View.GONE);
    root.addView(workerView, fullWrap());

    conversationView = new TextView(this);
    conversationView.setId(R.id.voice_conversation);
    conversationView.setTextSize(18);
    conversationView.setTextIsSelectable(true);
    conversationView.setPadding(0, dp(8), 0, dp(8));
    conversationScrollView = new ScrollView(this);
    conversationScrollView.setFillViewport(true);
    conversationScrollView.addView(conversationView, fullWrap());
    loadConversationHistory();

    LinearLayout controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);

    transcriptPanel = new LinearLayout(this);
    transcriptPanel.setId(R.id.voice_transcript_panel);
    transcriptPanel.setOrientation(LinearLayout.VERTICAL);
    transcriptPanel.setVisibility(View.VISIBLE);
    TextInputLayout transcriptInput = new TextInputLayout(this);
    transcriptInput.setHint(R.string.recognized_text);
    draftEdit = new TextInputEditText(transcriptInput.getContext());
    draftEdit.setId(R.id.voice_draft);
    draftEdit.setHint(R.string.transcript_hint);
    draftEdit.setTextSize(17);
    draftEdit.setMinLines(1);
    draftEdit.setMaxLines(5);
    draftEdit.setInputType(InputType.TYPE_CLASS_TEXT
        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    transcriptInput.addView(draftEdit, fullWrap());
    draftEdit.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable editable) {
        historyPrefs.edit().putString(DRAFT_PREF_KEY, editable.toString()).apply();
        updateTranscriptPanel();
      }
    });
    transcriptPanel.addView(transcriptInput, fullWrap());
    LinearLayout messageActions = new LinearLayout(this);
    messageActions.setOrientation(LinearLayout.HORIZONTAL);
    transcribeButton = new MaterialButton(this);
    transcribeButton.setId(R.id.voice_transcribe);
    transcribeButton.setText(R.string.transcribe);
    transcribeButton.setMinHeight(dp(48));
    sendDraftButton = new MaterialButton(this);
    sendDraftButton.setId(R.id.voice_send);
    sendDraftButton.setText(R.string.send);
    sendDraftButton.setMinHeight(dp(48));
    messageActions.addView(transcribeButton, weightedWrap());
    messageActions.addView(sendDraftButton, weightedWrap());
    transcriptPanel.addView(messageActions, fullWrap());
    controls.addView(transcriptPanel, fullWrap());

    replayRow = new LinearLayout(this);
    replayRow.setId(R.id.voice_replay_row);
    replayRow.setOrientation(LinearLayout.HORIZONTAL);
    replayRow.setVisibility(View.GONE);
    playUserButton = new MaterialButton(this);
    playUserButton.setText(R.string.load_mine);
    playUserButton.setMinHeight(dp(48));
    playAnswerButton = new MaterialButton(this);
    playAnswerButton.setText(R.string.load_answer);
    playAnswerButton.setMinHeight(dp(48));
    replayRow.addView(playUserButton, weightedWrap());
    replayRow.addView(playAnswerButton, weightedWrap());
    startButton = new MaterialButton(this);
    startButton.setId(R.id.voice_start_stop);
    startButton.setText(R.string.start_conversation);
    startButton.setMinHeight(dp(56));
    controls.addView(startButton, fullWrap());
    controls.addView(replayRow, fullWrap());
    userPlayer = new VoicePcmPlayerView(this, appConfig.sampleRate, appConfig.playerMaxBytes, getString(R.string.user_audio));
    userPlayer.setId(R.id.voice_user_player);
    assistantPlayer = new VoicePcmPlayerView(this, appConfig.sampleRate, appConfig.playerMaxBytes, getString(R.string.assistant_audio));
    assistantPlayer.setId(R.id.voice_assistant_player);
    assistantPlayer.setRemoteStopListener(() -> sendControl("cancel", "user_stopped_playback", -1, 0.0, 0.0));
    controls.addView(userPlayer, fullWrap());
    controls.addView(assistantPlayer, fullWrap());

    WindowFractionScrollView controlsScroll = new WindowFractionScrollView(this);
    controlsScroll.setFillViewport(useWideShortLayout());
    controlsScroll.addView(controls, fullWrap());
    if (useWideShortLayout()) {
      LinearLayout body = new LinearLayout(this);
      body.setOrientation(LinearLayout.HORIZONTAL);
      body.setBaselineAligned(false);
      LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
      LinearLayout.LayoutParams column = new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
      body.addView(conversationScrollView, column);
      LinearLayout.LayoutParams controlColumn = new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
      controlColumn.setMarginStart(dp(14));
      body.addView(controlsScroll, controlColumn);
      root.addView(body, bodyParams);
    } else {
      root.addView(conversationScrollView, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
      root.addView(controlsScroll, fullWrap());
    }

    setContentView(root);
    setForegroundMode(ForegroundMode.READY, null);
    draftEdit.setText(historyPrefs.getString(DRAFT_PREF_KEY, ""));
    draftEdit.setSelection(draftEdit.length());
    updateServiceHealth();
    updateTranscriptPanel();
    updateReplayRow();

    startButton.setOnClickListener(v -> {
      if (running.get()) stopSession();
      else startSession();
    });
    settingsButton.setOnClickListener(v -> showSettingsDialog());
    diagnosticsButton.setOnClickListener(v -> showDiagnosticsDialog());
    sendDraftButton.setOnClickListener(v -> submitDraft());
    transcribeButton.setOnClickListener(v -> transcribeManualRecording());
    playUserButton.setOnClickListener(v -> requestReplay("user"));
    playAnswerButton.setOnClickListener(v -> requestReplay("assistant"));
  }

  private LinearLayout.LayoutParams fullWrap() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private LinearLayout.LayoutParams weightedWrap() {
    return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
  }

  private void startSession() {
    if (captureStartPending) {
      captureStartPending = false;
      setForegroundMode(ForegroundMode.READY, null);
      return;
    }
    if (!hasRequiredAudioPermissions()) {
      requestMissingAudioPermissions();
      setForegroundMode(ForegroundMode.PERMISSION, null);
      return;
    }
    if (running.get()) return;
    if (autoTranscribe && !connectionTracker.isOpen()) {
      captureStartPending = true;
      connectionWanted.set(true);
      setForegroundMode(ForegroundMode.CONNECTING, getString(R.string.detail_connecting));
      connectWebSocket();
      return;
    }
    beginCaptureNow();
  }

  private void beginCaptureNow() {
    if (!running.compareAndSet(false, true)) return;
    captureStartPending = false;
    stopRequested = false;
    nextSeq = 0;
    droppedLiveFrames = 0L;
    if (!autoTranscribe) {
      synchronized (manualRecordingLock) { manualRecordingBuffer.reset(); }
      manualRecordingAvailable = false;
      deleteManualRecordingFile();
      userPlayer.setStaticPcm(null);
      setForegroundMode(ForegroundMode.RECORDING_LOCAL, null);
    } else {
      setForegroundMode(connectionTracker.isOpen() ? ForegroundMode.VERIFYING : ForegroundMode.CONNECTING, null);
    }
    runOnUiThread(() -> startButton.setText(R.string.stop_recording));
    ensureUplinkThread();
    updateTranscriptPanel();
  }

  private void stopSession() {
    captureStartPending = false;
    if (!running.getAndSet(false)) return;
    runOnUiThread(() -> startButton.setText(R.string.start_recording));
    setForegroundMode(ForegroundMode.FINISHING, null);
  }

  private void connectWebSocket() {
    if (!connectionWanted.get() || wsClient == null || connectionTracker.isOpen() || wsConnecting.get()) return;
    long generation = connectionTracker.beginConnect();
    reconnectScheduledGeneration = -1L;
    wsConnecting.set(true);
    WebSocket previous = webSocket;
    webSocket = null;
    if (previous != null) previous.cancel();
    setForegroundMode(ForegroundMode.CONNECTING, null);
    try {
      String url = appConfig.websocketUrl
          + "?conversation=" + enc(conversationId)
          + "&generation=" + generation;
      WebSocket candidate = wsClient.newWebSocket(
          new Request.Builder().url(url).build(),
          new VoiceWebSocketListener(generation));
      if (connectionTracker.owns(generation)) webSocket = candidate;
      else candidate.cancel();
    } catch (Exception failure) {
      wsConnecting.set(false);
      connectionTracker.onClosed(generation);
      appendDiagnostic("Connect failed: " + failure.getClass().getSimpleName());
      scheduleReconnect(generation, false);
    }
  }

  private void scheduleReconnect(long failedGeneration, boolean immediate) {
    if (!connectionWanted.get() || !connectionTracker.owns(failedGeneration)) return;
    if (reconnectScheduledGeneration == failedGeneration && reconnectRunnable != null) {
      if (!immediate) return;
      mainHandler.removeCallbacks(reconnectRunnable);
      reconnectScheduledGeneration = -1L;
    }
    reconnectScheduledGeneration = failedGeneration;
    int attempt = connectionTracker.nextReconnectAttempt(appConfig.reconnectMaxExponent);
    long jitter = appConfig.reconnectJitterMs <= 0
        ? 0L
        : ThreadLocalRandom.current().nextLong(appConfig.reconnectJitterMs + 1L);
    long delay = immediate ? 0L : ConnectionPolicy.reconnectDelayMs(
        attempt,
        appConfig.reconnectBaseMs,
        appConfig.reconnectMaxMs,
        appConfig.reconnectMaxExponent,
        appConfig.reconnectJitterMs,
        jitter);
    setForegroundMode(
        ForegroundMode.RECONNECTING,
        hasUsableNetwork() ? reconnectDetail() : getString(R.string.detail_unavailable));
    if (reconnectRunnable != null) mainHandler.removeCallbacks(reconnectRunnable);
    reconnectRunnable = () -> {
      if (!connectionWanted.get() || !connectionTracker.owns(failedGeneration)) return;
      reconnectScheduledGeneration = -1L;
      connectWebSocket();
    };
    mainHandler.postDelayed(reconnectRunnable, delay);
  }

  private void invalidateCurrentConnection(String reason) {
    long generation = connectionTracker.currentGeneration();
    WebSocket socket = webSocket;
    webSocket = null;
    wsConnecting.set(false);
    connectionTracker.onClosed(generation);
    appendDiagnostic("Connection replaced: " + reason);
    if (socket != null) socket.cancel();
    if (connectionWanted.get()) scheduleReconnect(generation, false);
  }

  private void closeCurrentSocket(String reason, boolean finalClose) {
    long generation = connectionTracker.currentGeneration();
    WebSocket socket = webSocket;
    webSocket = null;
    wsConnecting.set(false);
    connectionTracker.onClosed(generation);
    if (reconnectRunnable != null) mainHandler.removeCallbacks(reconnectRunnable);
    reconnectScheduledGeneration = -1L;
    if (socket != null) {
      try {
        if (finalClose) socket.close(1000, reason);
        else socket.cancel();
      } catch (Exception ignored) {}
    }
    serverHelloReady = false;
    clearComponentHealth();
    updateServiceHealth();
    if (!connectionWanted.get()) setForegroundMode(ForegroundMode.READY, null);
  }

  private final class VoiceWebSocketListener extends WebSocketListener {
    private final long generation;

    VoiceWebSocketListener(long generation) {
      this.generation = generation;
    }

    private boolean current(WebSocket socket) {
      boolean owns = connectionTracker.owns(generation);
      if (!owns) socket.cancel();
      return owns;
    }

    @Override public void onOpen(WebSocket socket, Response response) {
      if (!current(socket) || !connectionTracker.onOpen(generation, System.currentTimeMillis())) return;
      webSocket = socket;
      wsConnecting.set(false);
      appendDiagnostic("WebSocket connected.");
      sendClientState();
      if (running.get()) {
        setForegroundMode(ForegroundMode.VERIFYING, null);
        ensureUplinkThread();
      } else {
        setForegroundMode(ForegroundMode.READY, getString(R.string.detail_ready_text));
      }
      if (captureStartPending) beginCaptureNow();
    }

    @Override public void onMessage(WebSocket socket, String text) {
      if (!current(socket)) return;
      handleWsJson(text, generation);
    }

    @Override public void onMessage(WebSocket socket, ByteString bytes) {
      if (!current(socket)) return;
      handleWsAudio(bytes.toByteArray());
    }

    @Override public void onClosing(WebSocket socket, int code, String reason) {
      if (current(socket)) socket.close(code, reason);
    }

    @Override public void onClosed(WebSocket socket, int code, String reason) {
      if (!connectionTracker.onClosed(generation)) return;
      if (webSocket == socket) webSocket = null;
      wsConnecting.set(false);
      appendDiagnostic("WebSocket closed: " + code + " " + reason);
      serverHelloReady = false;
      clearComponentHealth();
      updateServiceHealth();
      if (connectionWanted.get()) scheduleReconnect(generation, false);
    }

    @Override public void onFailure(WebSocket socket, Throwable failure, Response response) {
      if (!connectionTracker.onClosed(generation)) return;
      if (webSocket == socket) webSocket = null;
      wsConnecting.set(false);
      appendDiagnostic("WebSocket failure: " + failure.getClass().getSimpleName());
      serverHelloReady = false;
      clearComponentHealth();
      updateServiceHealth();
      if (connectionWanted.get()) scheduleReconnect(generation, false);
      else setForegroundMode(ForegroundMode.READY, null);
    }
  }

  private void handleWsJson(String text, long listenerGeneration) {
    try {
      JSONObject obj = new JSONObject(text);
      String type = obj.optString("type", "");
      String event = obj.optString("event", "");
      if ("heartbeat".equals(type)) {
        connectionTracker.onHeartbeat(listenerGeneration, System.currentTimeMillis());
        applyComponentHealth(obj.optJSONObject("components"));
        return;
      }
      if ("media_ack".equals(type)) {
        long acknowledgedGeneration = obj.optLong("connection_generation", -1L);
        if (acknowledgedGeneration != listenerGeneration) return;
        if (connectionTracker.onMediaAck(listenerGeneration, System.currentTimeMillis())) {
          diagnosticConnectionState = "live; media confirmed";
          if (running.get() && (foregroundMode == ForegroundMode.VERIFYING
              || foregroundMode == ForegroundMode.CONNECTING
              || foregroundMode == ForegroundMode.RECONNECTING)) {
            setForegroundMode(ForegroundMode.LISTENING, null);
          }
          if (autoSend && !pendingTurnId.isEmpty() && !draftEdit.getText().toString().trim().isEmpty()) {
            submitDraft();
          }
          updateTranscriptPanel();
        }
        return;
      }
      if ("hello".equals(type)) {
        serverHelloReady = true;
        serverVersion = obj.optString("version", "");
        serverAsr = obj.optString("asr", "");
        serverTts = obj.optString("tts", "");
        applyComponentHealth(obj.optJSONObject("components"));
        appendDiagnostic("Server ready: " + serverVersion);
        JSONArray vocab = obj.optJSONArray("vocabulary");
        if (!userVocabularyConfigured && vocab != null) {
          currentVocabulary.clear();
          for (int i = 0; i < vocab.length(); i++) {
            String term = vocab.optString(i, "").trim();
            if (!term.isEmpty()) currentVocabulary.add(term);
          }
          saveLocalVocabulary(false);
        } else if (userVocabularyConfigured) {
          sendVocabulary();
        }
        updateServiceHealth();
        if (typedSendPending && !draftEdit.getText().toString().trim().isEmpty()) submitDraft();
        if (manualTranscribePending) transcribeManualRecording();
        if (!running.get() && foregroundMode != ForegroundMode.THINKING && !manualTranscribePending) setForegroundMode(ForegroundMode.READY, getString(R.string.detail_ready_text));
      } else if ("history".equals(type)) {
        replaceConversationHistory(obj.optJSONArray("turns"));
      } else if ("state".equals(type)) {
        String state = obj.optString("state", "");
        if ("thinking".equals(state)) setForegroundMode(ForegroundMode.THINKING, null);
        else if ("awaiting_send".equals(state)) {
          pendingTurnId = obj.optString("turn_id", pendingTurnId);
          setForegroundMode(ForegroundMode.REVIEW, null);
          updateTranscriptPanel();
        } else if ("listening".equals(state)) {
          if (pendingTurnId.isEmpty()) showListeningState();
        }
      } else if ("asr".equals(type)) {
        connectionTracker.onMediaAck(listenerGeneration, System.currentTimeMillis());
        String heard = obj.optString("text", "").trim();
        if ("partial".equals(event)) {
          if (!heard.isEmpty() && pendingTurnId.isEmpty()) setDraftText(heard, true);
        } else if ("final".equals(event)) {
          pendingTurnId = obj.optString("turn_id", "");
          latestUserTurnId = pendingTurnId;
          replayUserAvailable = obj.optBoolean("has_user_audio", false);
          if (manualTranscribePending) {
            manualTranscriptPendingSubmission = true;
            manualTranscribePending = false;
          }
          setDraftText(heard, true);
          updateReplayRow();
          if (!autoSend) setForegroundMode(ForegroundMode.REVIEW, null);
          updateTranscriptPanel();
        }
      } else if ("turn".equals(type) && "submitted".equals(event)) {
        String submitted = obj.optString("text", "").trim();
        String turnId = obj.optString("turn_id", "");
        if (!submitted.isEmpty()) {
          conversationTextModel.confirmUser(turnId, submitted);
          persistAndRenderConversation();
        }
        pendingTurnId = "";
        clearDraft();
        manualTranscribePending = false;
        if (manualTranscriptPendingSubmission) {
          manualTranscriptPendingSubmission = false;
          clearManualRecording();
        }
        updateTranscriptPanel();
        setForegroundMode(ForegroundMode.THINKING, null);
      } else if ("assistant".equals(type)) {
        String reply = obj.optString("text", "").trim();
        String turnId = obj.optString("turn_id", "");
        if ("draft".equals(event) || "partial".equals(event)) {
          if (!reply.isEmpty()) {
            conversationTextModel.setLiveAssistant(turnId, reply);
            renderConversationText();
          }
        } else if ("final".equals(event)) {
          if (!turnId.isEmpty()) latestAssistantTurnId = turnId;
          replayAssistantAvailable = obj.optBoolean("has_assistant_audio", true);
          if (!reply.isEmpty()) {
            conversationTextModel.confirmAssistant(turnId, reply);
            persistAndRenderConversation();
          }
          updateReplayRow();
        }
      } else if ("audio".equals(type)) {
        if ("start".equals(event)) beginStreamingPlayback(obj);
        else if ("end".equals(event)) markPlaybackRemoteEnded(obj.optInt("generation", -1));
        else if ("cancel".equals(event)) {
          stopPlaybackLocal("server_cancel", false);
          conversationTextModel.clearLiveAssistant();
          renderConversationText();
        }
      } else if ("replay".equals(type)) {
        if ("start".equals(event)) beginStreamingPlayback(obj);
        else if ("end".equals(event)) markPlaybackRemoteEnded(obj.optInt("generation", -1));
      } else if ("vocabulary".equals(type)) {
        JSONArray terms = obj.optJSONArray("terms");
        if (terms != null) {
          currentVocabulary.clear();
          for (int i = 0; i < terms.length(); i++) {
            String term = terms.optString(i, "").trim();
            if (!term.isEmpty()) currentVocabulary.add(term);
          }
          saveLocalVocabulary(userVocabularyConfigured);
        }
      } else if ("worker".equals(type)) {
        handleWorkerEvent(obj);
      } else if ("error".equals(type)) {
        String stage = obj.optString("stage", "");
        if ("stt".equals(stage)) serverSttReady = false;
        else if ("tts".equals(stage)) serverTtsReady = false;
        else if ("llm".equals(stage) || "agent".equals(stage)) serverAgentReady = false;
        updateServiceHealth();
        if ("stt".equals(stage) || "tts".equals(stage)) {
          setForegroundMode(ForegroundMode.READY, getString(R.string.health_strip_text_only));
        } else {
          setForegroundMode(ForegroundMode.UNAVAILABLE, getString(R.string.server_error));
        }
        appendDiagnostic("Server error: " + obj.optString("message", "error"));
      }
    } catch (Exception failure) {
      appendDiagnostic("Invalid server message: " + failure.getClass().getSimpleName());
    }
  }

  private void sendClientState() {
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen()) return;
    try {
      JSONObject state = new JSONObject();
      state.put("type", "client_state");
      state.put("build", buildLabel());
      state.put("conversation", conversationId);
      state.put("auto_send", VoiceAutomationPolicy.effectiveAutoSend(autoTranscribe, autoSend));
      socket.send(state.toString());
    } catch (Exception failure) {
      appendDiagnostic("Client state failed: " + failure.getClass().getSimpleName());
    }
  }

  private void sendControl(String type, String reason, int seq, double rms, double ratio) {
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen()) return;
    try {
      JSONObject obj = new JSONObject();
      obj.put("type", type);
      if (reason != null) obj.put("reason", reason);
      if (seq >= 0) obj.put("seq", seq);
      if (rms > 0) obj.put("rms", rms);
      if (ratio > 0) obj.put("ratio", ratio);
      socket.send(obj.toString());
    } catch (Exception failure) {
      appendDiagnostic("Control send failed: " + failure.getClass().getSimpleName());
    }
  }

  private boolean sendAudioFrame(byte[] pcm, int len) {
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen() || len <= 0) return false;
    if (!ConnectionPolicy.shouldSendAudio(socket.queueSize(), appConfig.maxQueuedAudioBytes)) {
      droppedLiveFrames += 1L;
      return false;
    }
    boolean sent = socket.send(ByteString.of(pcm, 0, len));
    if (!sent) droppedLiveFrames += 1L;
    return sent;
  }

  private void ensureUplinkThread() {
    if (uplinkThread != null && uplinkThread.isAlive()) return;
    uplinkThread = new Thread(this::uplinkLoop, "voice-uplink");
    uplinkThread.start();
  }

  private void uplinkLoop() {
    AudioRecord recorder = null;
    AudioRouteController route = null;
    try {
      if (!hasRequiredAudioPermissions()) {
        running.set(false);
        setForegroundMode(ForegroundMode.PERMISSION, null);
        return;
      }
      AudioInputOption selectedInput = selectPreferredInput();
      currentMicrophoneLabel = selectedInput.getLabel();
      route = new AudioRouteController(this);
      AudioDeviceInfo requestedDevice = route.prepare(this, selectedInput);
      RecordSetup setup = createAudioRecord(selectedInput);
      recorder = setup.recorder;
      int captureRate = setup.sampleRate;
      boolean builtIn = selectedInput.getCategory() == AudioInputOption.Category.BUILT_IN;
      if (!builtIn && !route.applyPreferredDevice(recorder, requestedDevice, selectedInput)) {
        throw new IllegalStateException("Android rejected the selected external microphone input");
      }
      initAudioEffects(recorder.getAudioSessionId());
      recorder.startRecording();
      if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
        throw new IllegalStateException("Android did not enter recording state");
      }
      AudioDeviceInfo routedDevice = route.waitForRoutedInput(recorder, requestedDevice, selectedInput);
      currentMicrophoneLabel = AudioInputCatalog.describe(routedDevice);
      appendDiagnostic("Microphone active: " + currentMicrophoneLabel + " at " + captureRate + " Hz");
      int samplesPerChunk = Math.max(1, captureRate * appConfig.frameMs / 1000);
      short[] capture = new short[samplesPerChunk];
      while (running.get()) {
        int got = readFully(recorder, capture, samplesPerChunk);
        if (got <= 0) continue;
        byte[] pcm = resampleToConfiguredPcm(capture, got, captureRate);
        currentInputDbfs = pcmDbfs(pcm, pcm.length);
        long now = System.currentTimeMillis();
        if (now - lastLevelUiMs >= appConfig.levelUpdateMs) {
          lastLevelUiMs = now;
          updateServiceHealth();
        }
        if (autoTranscribe) {
          sendAudioFrame(pcm, pcm.length);
        } else {
          boolean accepted;
          synchronized (manualRecordingLock) {
            accepted = manualRecordingBuffer.size() + pcm.length <= appConfig.manualRecordingMaxBytes;
            if (accepted) manualRecordingBuffer.write(pcm, 0, pcm.length);
          }
          if (!accepted) {
            running.set(false);
            mainHandler.post(() -> setForegroundMode(ForegroundMode.REVIEW, getString(R.string.recording_limit)));
            break;
          }
        }
        nextSeq += 1;
      }
      if (autoTranscribe) sendControl("end_utterance", "capture_stopped", nextSeq, 0.0, 0.0);
    } catch (Exception failure) {
      running.set(false);
      setForegroundMode(ForegroundMode.MICROPHONE_ERROR, null);
      appendDiagnostic("Microphone failed: " + failure.getClass().getSimpleName() + " " + failure.getMessage());
      runOnUiThread(() -> startButton.setText(R.string.start_conversation));
    } finally {
      releaseAudioEffects();
      try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
      try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
      try { if (route != null) route.release(); } catch (Exception ignored) {}
      if (!autoTranscribe) mainHandler.post(this::finishManualRecordingCapture);
      updateServiceHealth();
    }
  }

  private static final class RecordSetup {
    final AudioRecord recorder;
    final int sampleRate;
    RecordSetup(AudioRecord recorder, int sampleRate) {
      this.recorder = recorder;
      this.sampleRate = sampleRate;
    }
  }

  private RecordSetup createAudioRecord(AudioInputOption input) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      throw new SecurityException("Microphone permission revoked");
    }
    int source = input != null && input.isBluetooth()
        ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
        : MediaRecorder.AudioSource.MIC;
    int[] rates = input != null && input.isBluetooth()
        ? new int[]{appConfig.sampleRate, 8000, 32000, 48000}
        : new int[]{appConfig.sampleRate, 48000, 44100, 32000};
    StringBuilder failures = new StringBuilder();
    for (int rate : rates) {
      int minimum = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG_IN, AUDIO_FORMAT);
      if (minimum <= 0) continue;
      int bufferSize = Math.max(minimum * 2, rate * 2 * 4);
      AudioRecord candidate = null;
      try {
        candidate = new AudioRecord(source, rate, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize);
        if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
          return new RecordSetup(candidate, rate);
        }
        failures.append(rate).append(" Hz state ").append(candidate.getState()).append("; ");
      } catch (RuntimeException failure) {
        failures.append(rate).append(" Hz ").append(failure.getClass().getSimpleName()).append("; ");
      }
      try { if (candidate != null) candidate.release(); } catch (Exception ignored) {}
    }
    throw new IllegalStateException("No supported microphone rate. " + failures);
  }

  private AudioInputOption selectPreferredInput() {
    List<AudioInputOption> current = AudioInputCatalog.list(this);
    synchronized (microphoneInputs) {
      microphoneInputs.clear();
      microphoneInputs.addAll(current);
    }
    if (current.isEmpty()) return AudioInputOption.systemDefault();
    for (AudioInputOption input : current) {
      if (input.getDeviceId() == selectedDeviceId) return input;
    }
    String savedLabel = historyPrefs.getString(MIC_LABEL_PREF_KEY, "");
    if (savedLabel != null && !savedLabel.isEmpty()) {
      for (AudioInputOption input : current) {
        if (savedLabel.equals(input.getLabel())) {
          selectedDeviceId = input.getDeviceId();
          return input;
        }
      }
    }
    for (AudioInputOption input : current) {
      if (input.isBluetooth()) {
        selectedDeviceId = input.getDeviceId();
        saveMicrophoneSelection(input, false);
        return input;
      }
    }
    for (AudioInputOption input : current) {
      if (input.getCategory() == AudioInputOption.Category.BUILT_IN) {
        selectedDeviceId = input.getDeviceId();
        saveMicrophoneSelection(input, false);
        return input;
      }
    }
    AudioInputOption fallback = current.get(0);
    selectedDeviceId = fallback.getDeviceId();
    saveMicrophoneSelection(fallback, false);
    return fallback;
  }

  private void saveMicrophoneSelection(AudioInputOption input, boolean userSelected) {
    if (input == null) return;
    currentMicrophoneLabel = input.getLabel();
    historyPrefs.edit()
        .putInt(MIC_DEVICE_PREF_KEY, input.getDeviceId())
        .putString(MIC_LABEL_PREF_KEY, input.getLabel())
        .putBoolean(MIC_USER_SELECTED_PREF_KEY,
            userSelected || historyPrefs.getBoolean(MIC_USER_SELECTED_PREF_KEY, false))
        .apply();
  }

  private void refreshPreferredMicrophonePreview() {
    if (!hasRequiredAudioPermissions()) {
      currentMicrophoneLabel = getString(R.string.microphone_permission_denied);
      return;
    }
    try {
      currentMicrophoneLabel = selectPreferredInput().getLabel();
    } catch (Exception failure) {
      currentMicrophoneLabel = getString(R.string.microphone_unknown);
      appendDiagnostic("Microphone list failed: " + failure.getClass().getSimpleName());
    }
  }

  private void showSettingsDialog() {
    refreshPreferredMicrophonePreview();
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(8), dp(20), 0);

    TextView version = new TextView(this);
    version.setId(R.id.voice_version);
    version.setText(getString(R.string.version_label, buildLabel()));
    version.setTextSize(14);
    version.setPadding(0, 0, 0, dp(12));
    content.addView(version, fullWrap());

    TextView microphone = new TextView(this);
    microphone.setText(getString(R.string.microphone_current,
        currentMicrophoneLabel.isEmpty() ? getString(R.string.microphone_unknown) : currentMicrophoneLabel));
    microphone.setTextSize(16);
    content.addView(microphone, fullWrap());
    MaterialButton change = new MaterialButton(this);
    change.setText(R.string.change_microphone);
    change.setMinHeight(dp(48));
    change.setEnabled(!running.get());
    content.addView(change, fullWrap());

    TextView automationTitle = new TextView(this);
    automationTitle.setText(R.string.automation_title);
    automationTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    automationTitle.setTextSize(16);
    automationTitle.setPadding(0, dp(12), 0, 0);
    content.addView(automationTitle, fullWrap());

    MaterialCheckBox transcription = new MaterialCheckBox(this);
    transcription.setId(R.id.voice_auto_transcribe);
    transcription.setText(R.string.automatic_transcription);
    transcription.setChecked(autoTranscribe);
    transcription.setEnabled(!running.get());
    transcription.setMinHeight(dp(48));
    content.addView(transcription, fullWrap());
    TextView transcriptionDetail = new TextView(this);
    transcriptionDetail.setText(R.string.automatic_transcription_detail);
    transcriptionDetail.setTextSize(13);
    content.addView(transcriptionDetail, fullWrap());

    MaterialCheckBox automatic = new MaterialCheckBox(this);
    automatic.setId(R.id.voice_auto_send);
    automatic.setText(R.string.send_automatically);
    automatic.setChecked(autoSend);
    automatic.setEnabled(autoTranscribe && !running.get());
    automatic.setMinHeight(dp(48));
    content.addView(automatic, fullWrap());

    TextInputLayout vocabLayout = new TextInputLayout(this);
    vocabLayout.setId(R.id.voice_vocabulary);
    vocabLayout.setHint(R.string.vocabulary_title);
    TextInputEditText vocabEdit = new TextInputEditText(vocabLayout.getContext());
    vocabEdit.setHint(R.string.vocabulary_hint);
    vocabEdit.setMinLines(4);
    vocabEdit.setMaxLines(8);
    vocabEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    StringBuilder vocabText = new StringBuilder();
    for (String term : currentVocabulary) {
      if (vocabText.length() > 0) vocabText.append('\n');
      vocabText.append(term);
    }
    vocabEdit.setText(vocabText.toString());
    vocabLayout.addView(vocabEdit, fullWrap());
    content.addView(vocabLayout, fullWrap());
    MaterialButton saveVocab = new MaterialButton(this);
    saveVocab.setId(R.id.voice_save_vocabulary);
    saveVocab.setText(R.string.save_vocabulary);
    saveVocab.setMinHeight(dp(48));
    content.addView(saveVocab, fullWrap());

    ScrollView settingsScroll = new ScrollView(this);
    settingsScroll.addView(content, fullWrap());
    AlertDialog dialog = new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.settings_title)
        .setView(settingsScroll)
        .setPositiveButton(R.string.done, null)
        .create();

    change.setOnClickListener(v -> {
      dialog.dismiss();
      showMicrophonePicker();
    });
    transcription.setOnCheckedChangeListener((button, checked) -> {
      if (running.get()) return;
      autoTranscribe = checked;
      if (!checked) autoSend = false;
      automatic.setChecked(autoSend);
      automatic.setEnabled(checked && !running.get());
      historyPrefs.edit()
          .putBoolean(AUTO_TRANSCRIBE_PREF_KEY, autoTranscribe)
          .putBoolean(AUTO_SEND_PREF_KEY, autoSend)
          .apply();
      sendClientState();
      updateTranscriptPanel();
    });
    automatic.setOnCheckedChangeListener((button, checked) -> {
      if (running.get()) return;
      autoSend = VoiceAutomationPolicy.effectiveAutoSend(autoTranscribe, checked);
      if (automatic.isChecked() != autoSend) automatic.setChecked(autoSend);
      historyPrefs.edit().putBoolean(AUTO_SEND_PREF_KEY, autoSend).apply();
      sendClientState();
      updateTranscriptPanel();
      if (autoSend && !pendingTurnId.isEmpty() && !draftEdit.getText().toString().trim().isEmpty()) submitDraft();
    });
    saveVocab.setOnClickListener(v -> {
      setVocabularyFromText(vocabEdit.getText() == null ? "" : vocabEdit.getText().toString(), true);
      sendVocabulary();
      saveVocab.setText(R.string.vocabulary_saved);
    });
    dialog.show();
  }

  private void showMicrophonePicker() {
    if (running.get()) {
      new MaterialAlertDialogBuilder(this)
          .setTitle(R.string.microphone_picker_title)
          .setMessage(R.string.microphone_locked)
          .setPositiveButton(R.string.back, null)
          .show();
      return;
    }
    if (!hasRequiredAudioPermissions()) {
      requestMissingAudioPermissions();
      return;
    }
    final List<AudioInputOption> current;
    try {
      current = AudioInputCatalog.list(this);
    } catch (Exception failure) {
      appendDiagnostic("Microphone picker failed: " + failure.getClass().getSimpleName());
      return;
    }
    if (current.isEmpty()) {
      new MaterialAlertDialogBuilder(this)
          .setTitle(R.string.microphone_picker_title)
          .setMessage(R.string.microphone_none)
          .setPositiveButton(R.string.back, null)
          .show();
      return;
    }
    String[] labels = new String[current.size() + 1];
    int checked = -1;
    for (int i = 0; i < current.size(); i++) {
      AudioInputOption option = current.get(i);
      labels[i] = option.getLabel() + (option.isBluetooth() ? getString(R.string.bluetooth_suffix) : "");
      if (option.getDeviceId() == selectedDeviceId) checked = i;
    }
    labels[current.size()] = getString(R.string.refresh_microphones);
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.microphone_picker_title)
        .setSingleChoiceItems(labels, checked, (dialog, which) -> {
          if (which == current.size()) {
            dialog.dismiss();
            showMicrophonePicker();
            return;
          }
          AudioInputOption selected = current.get(which);
          selectedDeviceId = selected.getDeviceId();
          saveMicrophoneSelection(selected, true);
          dialog.dismiss();
        })
        .setNegativeButton(R.string.back, null)
        .show();
  }

  private void showDiagnosticsDialog() {
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(18), dp(8), dp(18), 0);
    TextView state = new TextView(this);
    state.setTextSize(14);
    state.setText(diagnosticsStateText());
    content.addView(state, fullWrap());
    TextView logs = new TextView(this);
    logs.setTextSize(11);
    logs.setTextIsSelectable(true);
    synchronized (diagnosticLock) {
      logs.setText(diagnosticText);
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(logs, fullWrap());
    content.addView(scroll, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
    AlertDialog dialog = new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.diagnostics_title)
        .setView(content)
        .setNeutralButton(R.string.check_connection, null)
        .setPositiveButton(R.string.done, null)
        .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        .setOnClickListener(v -> healthCheck(state, logs)));
    dialog.show();
  }

  private void healthCheck(TextView stateView, TextView logsView) {
    Request request = new Request.Builder().url(appConfig.healthUrl).get().build();
    wsClient.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException failure) {
        appendDiagnostic("Health failed: " + failure.getClass().getSimpleName());
        runOnUiThread(() -> {
          stateView.setText(getString(R.string.diagnostics_with_result, diagnosticsStateText(), getString(R.string.health_failed)));
          synchronized (diagnosticLock) { logsView.setText(diagnosticText); }
        });
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        String body = response.body() == null ? "" : response.body().string();
        String result;
        if (!response.isSuccessful()) {
          result = getString(R.string.health_http, response.code());
        } else {
          try {
            JSONObject json = new JSONObject(body);
            result = json.optBoolean("ok", false)
                ? getString(R.string.health_ok)
                : getString(R.string.health_failed);
          } catch (Exception ignored) {
            result = getString(R.string.health_invalid);
          }
        }
        appendDiagnostic(result);
        String finalResult = result;
        runOnUiThread(() -> {
          stateView.setText(getString(R.string.diagnostics_with_result, diagnosticsStateText(), finalResult));
          synchronized (diagnosticLock) { logsView.setText(diagnosticText); }
        });
      }
    });
  }

  private void handleWorkerEvent(JSONObject obj) {
    String workerId = obj.optString("worker_id", "").trim();
    String status = obj.optString("status", "none").trim();
    String eventType = obj.optString("event_type", "").trim();
    String message = obj.optString("message", "").trim();
    int sequence = obj.optInt("sequence", 0);
    if (sequence > 0) {
      if (sequence <= currentWorkerSequence) return;
      currentWorkerSequence = sequence;
    }
    boolean visible = VoiceOverviewPolicy.showWorker(status);
    String label = workerLabel(status, message);
    runOnUiThread(() -> {
      workerView.setText(label);
      workerView.setVisibility(visible ? View.VISIBLE : View.GONE);
    });
  }

  private String workerLabel(String status, String message) {
    String base;
    switch (status) {
      case "working": base = getString(R.string.background_working); break;
      case "input_required": base = getString(R.string.background_input); break;
      case "completed": base = getString(R.string.background_completed); break;
      case "failed": base = getString(R.string.background_failed); break;
      case "canceled": base = getString(R.string.background_canceled); break;
      default: base = getString(R.string.background_generic, status); break;
    }
    String clean = message == null ? "" : message.trim();
    if (clean.length() > appConfig.workerPreviewMaxChars) {
      clean = clean.substring(0, appConfig.workerPreviewMaxChars - 1).trim() + "…";
    }
    return clean.isEmpty() ? base : base + "\n" + clean;
  }

  private void submitDraft() {
    String value = draftEdit.getText().toString().trim();
    if (value.isEmpty()) {
      setForegroundMode(ForegroundMode.REVIEW, getString(R.string.transcript_empty));
      return;
    }
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen() || !serverHelloReady) {
      typedSendPending = true;
      connectionWanted.set(true);
      setForegroundMode(ForegroundMode.CONNECTING, getString(R.string.detail_connecting));
      if (hasUsableNetwork()) connectWebSocket();
      else updateServiceHealth();
      return;
    }
    try {
      JSONObject obj = new JSONObject();
      if (!pendingTurnId.isEmpty()) {
        obj.put("type", "submit");
        obj.put("turn_id", pendingTurnId);
      } else {
        obj.put("type", "text_turn");
      }
      obj.put("text", value);
      typedSendPending = false;
      if (!socket.send(obj.toString())) throw new IOException("WebSocket send rejected");
      sendDraftButton.setEnabled(false);
      setPrimaryStatus(getString(R.string.sending_transcript), getString(R.string.detail_thinking));
    } catch (Exception failure) {
      typedSendPending = true;
      appendDiagnostic("Submit failed: " + failure.getClass().getSimpleName());
      setForegroundMode(ForegroundMode.RECONNECTING, getString(R.string.not_connected_action));
    }
  }

  private void loadLocalVocabulary() {
    currentVocabulary.clear();
    String raw = historyPrefs.getString(VOCAB_PREF_KEY, "");
    if (raw == null || raw.trim().isEmpty()) return;
    java.util.HashSet<String> seen = new java.util.HashSet<>();
    for (String line : raw.split("\n")) {
      String term = line.trim();
      if (term.isEmpty()) continue;
      String key = term.toLowerCase(Locale.ROOT);
      if (seen.add(key)) currentVocabulary.add(term);
    }
  }

  private void saveLocalVocabulary(boolean configured) {
    StringBuilder raw = new StringBuilder();
    for (String term : currentVocabulary) {
      if (raw.length() > 0) raw.append('\n');
      raw.append(term);
    }
    if (configured) userVocabularyConfigured = true;
    historyPrefs.edit()
        .putString(VOCAB_PREF_KEY, raw.toString())
        .putBoolean(VOCAB_CONFIGURED_PREF_KEY, userVocabularyConfigured)
        .apply();
  }

  private void setVocabularyFromText(String text, boolean configured) {
    currentVocabulary.clear();
    java.util.HashSet<String> seen = new java.util.HashSet<>();
    for (String line : (text == null ? "" : text).split("\n")) {
      String term = line.trim();
      if (term.isEmpty() || term.length() > 96) continue;
      String key = term.toLowerCase(Locale.ROOT);
      if (seen.add(key)) currentVocabulary.add(term);
      if (currentVocabulary.size() >= 500) break;
    }
    saveLocalVocabulary(configured);
  }

  private void sendVocabulary() {
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen() || !serverHelloReady) return;
    try {
      JSONObject obj = new JSONObject();
      obj.put("type", "vocabulary_update");
      JSONArray terms = new JSONArray();
      for (String term : currentVocabulary) terms.put(term);
      obj.put("terms", terms);
      if (!socket.send(obj.toString())) throw new IOException("WebSocket send rejected");
    } catch (Exception failure) {
      appendDiagnostic("Vocabulary sync failed: " + failure.getClass().getSimpleName());
    }
  }

  private File manualRecordingFile() {
    return new File(getFilesDir(), MANUAL_RECORDING_FILE);
  }

  private void persistManualRecording() {
    byte[] pcm;
    synchronized (manualRecordingLock) { pcm = manualRecordingBuffer.toByteArray(); }
    File target = manualRecordingFile();
    if (pcm.length == 0) {
      if (target.exists()) target.delete();
      return;
    }
    File temp = new File(getFilesDir(), MANUAL_RECORDING_FILE + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(pcm);
      out.getFD().sync();
      if (target.exists() && !target.delete()) throw new IOException("Could not replace saved recording");
      if (!temp.renameTo(target)) throw new IOException("Could not publish saved recording");
    } catch (Exception failure) {
      appendDiagnostic("Recording save failed: " + failure.getClass().getSimpleName());
      temp.delete();
    }
  }

  private void restoreManualRecording() {
    File file = manualRecordingFile();
    if (!file.isFile() || file.length() <= 0 || file.length() > appConfig.manualRecordingMaxBytes) {
      if (file.exists() && file.length() > appConfig.manualRecordingMaxBytes) file.delete();
      return;
    }
    try (FileInputStream in = new FileInputStream(file)) {
      byte[] data = new byte[(int) file.length()];
      int offset = 0;
      while (offset < data.length) {
        int got = in.read(data, offset, data.length - offset);
        if (got < 0) break;
        offset += got;
      }
      if (offset != data.length) throw new IOException("Short recording read");
      synchronized (manualRecordingLock) {
        manualRecordingBuffer.reset();
        manualRecordingBuffer.write(data, 0, data.length);
      }
      manualRecordingAvailable = true;
      if (userPlayer != null) userPlayer.setStaticPcm(data);
      updateTranscriptPanel();
    } catch (Exception failure) {
      appendDiagnostic("Recording restore failed: " + failure.getClass().getSimpleName());
      file.delete();
    }
  }

  private void deleteManualRecordingFile() {
    File file = manualRecordingFile();
    if (file.exists() && !file.delete()) appendDiagnostic("Could not remove saved recording file.");
  }

  private void clearManualRecording() {
    synchronized (manualRecordingLock) { manualRecordingBuffer.reset(); }
    manualRecordingAvailable = false;
    manualTranscribePending = false;
    deleteManualRecordingFile();
    if (userPlayer != null) userPlayer.setStaticPcm(null);
    updateTranscriptPanel();
  }

  private void finishManualRecordingCapture() {
    if (autoTranscribe) return;
    byte[] pcm;
    synchronized (manualRecordingLock) { pcm = manualRecordingBuffer.toByteArray(); }
    manualRecordingAvailable = pcm.length > 0;
    if (userPlayer != null) userPlayer.setStaticPcm(pcm);
    persistManualRecording();
    runOnUiThread(() -> startButton.setText(R.string.start_recording));
    setForegroundMode(
        manualRecordingAvailable ? ForegroundMode.REVIEW : ForegroundMode.READY,
        manualRecordingAvailable ? getString(R.string.recording_ready) : getString(R.string.detail_ready_text));
    updateTranscriptPanel();
  }

  private void transcribeManualRecording() {
    if (!manualRecordingAvailable || running.get()) return;
    byte[] pcm;
    synchronized (manualRecordingLock) { pcm = manualRecordingBuffer.toByteArray(); }
    if (pcm.length == 0) return;
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen() || !serverHelloReady) {
      manualTranscribePending = true;
      connectionWanted.set(true);
      setForegroundMode(ForegroundMode.CONNECTING, getString(R.string.transcribe_needs_connection));
      if (hasUsableNetwork()) connectWebSocket();
      return;
    }
    manualTranscribePending = true;
    setForegroundMode(ForegroundMode.TRANSCRIBING, null);
    updateTranscriptPanel();
    Thread uploader = new Thread(() -> {
      try {
        JSONObject begin = new JSONObject();
        begin.put("type", "begin_manual_utterance");
        begin.put("reason", "manual_transcribe");
        if (!socket.send(begin.toString())) throw new IOException("Manual upload start rejected");
        int chunk = Math.max(2, appConfig.sampleRate * 2 * appConfig.frameMs / 1000);
        for (int offset = 0; offset < pcm.length; offset += chunk) {
          if (socket != webSocket || !connectionTracker.isOpen()) throw new IOException("Connection changed during upload");
          long deadline = System.currentTimeMillis() + Math.max(appConfig.connectTimeoutMs, 15000L);
          while (socket.queueSize() > appConfig.maxQueuedAudioBytes / 2L) {
            if (System.currentTimeMillis() >= deadline) throw new IOException("Manual audio upload backpressure timeout");
            Thread.sleep(10L);
          }
          int length = Math.min(chunk, pcm.length - offset);
          if (!socket.send(ByteString.of(pcm, offset, length))) throw new IOException("Manual audio upload rejected");
        }
        JSONObject end = new JSONObject();
        end.put("type", "end_utterance");
        end.put("reason", "manual_transcribe");
        if (!socket.send(end.toString())) throw new IOException("Manual upload end rejected");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        mainHandler.post(() -> {
          manualTranscribePending = false;
          updateTranscriptPanel();
        });
      } catch (Exception failure) {
        appendDiagnostic("Manual transcription upload failed: " + failure.getClass().getSimpleName());
        mainHandler.post(() -> {
          setForegroundMode(ForegroundMode.RECONNECTING, getString(R.string.transcribe_needs_connection));
          updateTranscriptPanel();
        });
      }
    }, "manual-transcription-upload");
    uploader.start();
  }

  private void applyComponentHealth(JSONObject components) {
    if (components == null) return;
    serverReady = components.optBoolean("server", connectionTracker.isOpen());
    serverSttReady = components.optBoolean("stt", false);
    serverAgentReady = components.optBoolean("agent", false);
    serverTtsReady = components.optBoolean("tts", false);
    updateServiceHealth();
  }

  private void clearComponentHealth() {
    serverReady = false;
    serverSttReady = false;
    serverAgentReady = false;
    serverTtsReady = false;
  }

  private String healthWord(boolean ready) {
    return getString(ready ? R.string.health_ready_word : R.string.health_unavailable_word);
  }

  private void updateServiceHealth() {
    if (componentHealthView == null) return;
    final String value;
    boolean microphoneAvailable = hasRequiredAudioPermissions();
    if (connectionTracker.isOpen() && serverHelloReady) {
      String components = getString(
          R.string.health_components,
          healthWord(serverReady),
          healthWord(serverSttReady),
          healthWord(serverAgentReady),
          healthWord(serverTtsReady));
      if (!microphoneAvailable) {
        value = components + "\n" + getString(R.string.health_strip_microphone_blocked);
      } else if (running.get()) {
        value = components + "\n" + getString(
            R.string.health_strip_mic,
            currentMicrophoneLabel.isEmpty() ? getString(R.string.microphone_unknown) : currentMicrophoneLabel,
            currentInputDbfs);
      } else {
        value = components;
      }
    } else if (connectionWanted.get() && hasUsableNetwork()) {
      value = microphoneAvailable
          ? getString(R.string.health_strip_connecting)
          : getString(R.string.health_strip_microphone_blocked);
    } else {
      value = getString(R.string.health_strip_text_only);
    }
    runOnUiThread(() -> componentHealthView.setText(value));
  }


  private void requestReplay(String kind) {
    String turnId = "assistant".equals(kind) ? latestAssistantTurnId : latestUserTurnId;
    if (turnId.isEmpty()) {
      setPrimaryStatus(foregroundTitle(), getString(
          "assistant".equals(kind) ? R.string.no_answer_audio : R.string.no_user_audio));
      return;
    }
    WebSocket socket = webSocket;
    if (socket == null || !connectionTracker.isOpen()) {
      setForegroundMode(ForegroundMode.RECONNECTING, getString(R.string.not_connected_action));
      return;
    }
    try {
      JSONObject obj = new JSONObject();
      obj.put("type", "assistant".equals(kind) ? "replay_assistant" : "replay_user");
      obj.put("turn_id", turnId);
      socket.send(obj.toString());
      setPrimaryStatus(getString(R.string.loading_replay), "");
    } catch (Exception failure) {
      appendDiagnostic("Replay request failed: " + failure.getClass().getSimpleName());
    }
  }

  private void setDraftText(String text, boolean editable) {
    String value = text == null ? "" : text;
    runOnUiThread(() -> {
      draftEdit.setText(value);
      draftEdit.setSelection(draftEdit.length());
      draftEdit.setEnabled(true);
      updateTranscriptPanel();
    });
  }

  private void clearDraft() {
    runOnUiThread(() -> {
      draftEdit.setText("");
      draftEdit.setEnabled(true);
      historyPrefs.edit().remove(DRAFT_PREF_KEY).apply();
      updateTranscriptPanel();
    });
  }

  private void updateTranscriptPanel() {
    if (draftEdit == null || transcriptPanel == null || sendDraftButton == null || transcribeButton == null) return;
    runOnUiThread(() -> {
      String text = draftEdit.getText().toString().trim();
      transcriptPanel.setVisibility(View.VISIBLE);
      draftEdit.setEnabled(true);
      sendDraftButton.setVisibility(View.VISIBLE);
      sendDraftButton.setEnabled(!text.isEmpty() && !typedSendPending);
      boolean showTranscribe = !autoTranscribe && manualRecordingAvailable && !running.get();
      transcribeButton.setVisibility(showTranscribe ? View.VISIBLE : View.GONE);
      transcribeButton.setEnabled(showTranscribe && !manualTranscribePending);
    });
  }

  private void updateReplayRow() {
    if (replayRow == null) return;
    runOnUiThread(() -> {
      replayRow.setVisibility(VoiceOverviewPolicy.showReplay(
          replayUserAvailable, replayAssistantAvailable) ? View.VISIBLE : View.GONE);
      playUserButton.setEnabled(replayUserAvailable);
      playAnswerButton.setEnabled(replayAssistantAvailable);
    });
  }

  private void beginStreamingPlayback(JSONObject obj) {
    String type = obj.optString("type", "audio");
    String kind = obj.optString("kind", "assistant");
    boolean user = "replay".equals(type) && "user".equals(kind);
    VoicePcmPlayerView player = user ? userPlayer : assistantPlayer;
    activePcmPlayer = player;
    player.startStream(true, !user && "audio".equals(type));
    if (!user && "audio".equals(type)) setForegroundMode(ForegroundMode.BUFFERING, null);
    else setPrimaryStatus(getString(R.string.loading_replay), "");
  }

  private void handleWsAudio(byte[] pcm) {
    VoicePcmPlayerView player = activePcmPlayer;
    if (player == null || pcm == null || pcm.length == 0) return;
    if (!player.append(pcm)) {
      droppedPlaybackChunks += 1L;
      appendDiagnostic("Audio player safety buffer is full; additional audio was rejected.");
      if (player == assistantPlayer) sendControl("cancel", "player_buffer_limit", -1, 0.0, 0.0);
    } else if (player == assistantPlayer) {
      setForegroundMode(ForegroundMode.SPEAKING, null);
    }
  }

  private void markPlaybackRemoteEnded(int generation) {
    VoicePcmPlayerView player = activePcmPlayer;
    if (player != null) player.endStream();
    activePcmPlayer = null;
    showListeningState();
  }

  private void stopPlaybackLocal(String reason, boolean notifyServer) {
    if (assistantPlayer != null) assistantPlayer.cancelStream();
    if (userPlayer != null && activePcmPlayer == userPlayer) userPlayer.cancelStream();
    activePcmPlayer = null;
    if (notifyServer) sendControl("cancel", reason, -1, 0.0, 0.0);
  }

  private byte[] resampleToConfiguredPcm(short[] input, int count, int inputRate) {
    if (count <= 0) return new byte[0];
    int outputCount = inputRate == appConfig.sampleRate
        ? count
        : Math.max(1, (int) Math.round(count * (appConfig.sampleRate / (double) inputRate)));
    byte[] output = new byte[outputCount * 2];
    if (inputRate == appConfig.sampleRate) {
      for (int i = 0; i < count; i++) writePcm16(output, i, input[i]);
      return output;
    }
    double step = inputRate / (double) appConfig.sampleRate;
    for (int i = 0; i < outputCount; i++) {
      double source = i * step;
      int left = Math.min(count - 1, (int) Math.floor(source));
      int right = Math.min(count - 1, left + 1);
      double fraction = source - left;
      int value = (int) Math.round(input[left] * (1.0 - fraction) + input[right] * fraction);
      writePcm16(output, i, (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
    }
    return output;
  }

  private static void writePcm16(byte[] output, int sampleIndex, short value) {
    int offset = sampleIndex * 2;
    output[offset] = (byte) (value & 0xff);
    output[offset + 1] = (byte) ((value >> 8) & 0xff);
  }

  private double pcmDbfs(byte[] pcm, int len) {
    int samples = Math.max(0, len / 2);
    if (samples == 0) return -120.0;
    double sum = 0.0;
    for (int i = 0; i < samples; i++) {
      int lo = pcm[i * 2] & 255;
      int hi = pcm[i * 2 + 1];
      short value = (short) ((hi << 8) | lo);
      sum += (double) value * value;
    }
    double rms = Math.sqrt(sum / samples);
    return 20.0 * Math.log10(Math.max(1.0, rms) / 32768.0);
  }

  private int readFully(AudioRecord recorder, short[] target, int wanted) {
    int filled = 0;
    while (running.get() && filled < wanted) {
      int read = recorder.read(target, filled, wanted - filled, AudioRecord.READ_BLOCKING);
      if (read > 0) filled += read;
      else if (read < 0) return read;
    }
    return filled;
  }

  private void initAudioEffects(int sessionId) {
    releaseAudioEffects();
    try {
      if (AcousticEchoCanceler.isAvailable()) {
        aec = AcousticEchoCanceler.create(sessionId);
        if (aec != null) aec.setEnabled(true);
      }
    } catch (Exception ignored) {}
    try {
      if (NoiseSuppressor.isAvailable()) {
        noiseSuppressor = NoiseSuppressor.create(sessionId);
        if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
      }
    } catch (Exception ignored) {}
    try {
      if (AutomaticGainControl.isAvailable()) {
        agc = AutomaticGainControl.create(sessionId);
        if (agc != null) agc.setEnabled(true);
      }
    } catch (Exception ignored) {}
  }

  private void releaseAudioEffects() {
    try { if (aec != null) aec.release(); } catch (Exception ignored) {}
    try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) {}
    try { if (agc != null) agc.release(); } catch (Exception ignored) {}
    aec = null;
    noiseSuppressor = null;
    agc = null;
  }

  private boolean hasRequiredAudioPermissions() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) return false;
    return Build.VERSION.SDK_INT < 31
        || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void requestMissingAudioPermissions() {
    ArrayList<String> missing = new ArrayList<>();
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO);
    if (Build.VERSION.SDK_INT >= 31
        && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
    if (!missing.isEmpty()) {
      ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQUEST);
    }
  }

  @Override public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != PERMISSION_REQUEST) return;
    if (hasRequiredAudioPermissions()) {
      refreshPreferredMicrophonePreview();
      setForegroundMode(ForegroundMode.READY, null);
    } else {
      setForegroundMode(ForegroundMode.PERMISSION, null);
    }
  }

  private void registerNetworkObserver() {
    if (connectivityManager == null) return;
    networkCallback = new ConnectivityManager.NetworkCallback() {
      @Override public void onAvailable(Network network) {
        mainHandler.post(() -> {
          if (connectionWanted.get() && !connectionTracker.isOpen() && !wsConnecting.get()) {
            scheduleReconnect(connectionTracker.currentGeneration(), true);
          }
        });
      }

      @Override public void onLost(Network network) {
        mainHandler.post(() -> {
          if (connectionWanted.get() && !hasUsableNetwork()) {
            setForegroundMode(ForegroundMode.RECONNECTING, getString(R.string.detail_unavailable));
            invalidateCurrentConnection("network_lost");
          }
        });
      }
    };
    try {
      connectivityManager.registerDefaultNetworkCallback(networkCallback);
    } catch (Exception failure) {
      appendDiagnostic("Network observer unavailable: " + failure.getClass().getSimpleName());
    }
  }

  private boolean hasUsableNetwork() {
    if (connectivityManager == null) return true;
    try {
      Network network = connectivityManager.getActiveNetwork();
      NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
      return capabilities != null
          && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    } catch (Exception ignored) {
      return true;
    }
  }

  private void startConnectionMonitor() {
    connectionMonitor = new Runnable() {
      @Override public void run() {
        long now = System.currentTimeMillis();
        if (connectionWanted.get() && connectionTracker.isOpen()) {
          if (connectionTracker.heartbeatStale(now, appConfig.heartbeatStaleMs)) {
            invalidateCurrentConnection("heartbeat_stale");
          } else if (running.get() && autoTranscribe && connectionTracker.mediaPathStale(now, appConfig.mediaAckStaleMs)) {
            invalidateCurrentConnection("media_confirmation_stale");
          }
        }
        mainHandler.postDelayed(this, appConfig.heartbeatCheckMs);
      }
    };
    mainHandler.postDelayed(connectionMonitor, appConfig.heartbeatCheckMs);
  }

  private void setForegroundMode(ForegroundMode mode, String detailOverride) {
    if (mode == ForegroundMode.READY && !hasRequiredAudioPermissions()) {
      mode = ForegroundMode.PERMISSION;
      detailOverride = null;
    }
    foregroundMode = mode;
    int titleRes;
    int detailRes;
    switch (mode) {
      case CONNECTING:
        titleRes = R.string.status_connecting; detailRes = R.string.detail_connecting; diagnosticConnectionState = "connecting"; break;
      case VERIFYING:
        titleRes = R.string.status_checking_audio; detailRes = R.string.detail_checking_audio; diagnosticConnectionState = "connected; checking media"; break;
      case LISTENING:
        titleRes = R.string.status_listening; detailRes = R.string.detail_listening; diagnosticConnectionState = "live"; break;
      case RECORDING_LOCAL:
        titleRes = R.string.status_recording_local; detailRes = R.string.detail_recording_local; break;
      case TRANSCRIBING:
        titleRes = R.string.status_transcribing; detailRes = R.string.detail_transcribing; break;
      case THINKING:
        titleRes = R.string.status_thinking; detailRes = R.string.detail_thinking; break;
      case BUFFERING:
        titleRes = R.string.buffering_reply; detailRes = R.string.detail_buffering_reply; break;
      case SPEAKING:
        titleRes = R.string.status_speaking; detailRes = R.string.detail_speaking; break;
      case RECONNECTING:
        titleRes = R.string.status_reconnecting; detailRes = R.string.detail_reconnecting; diagnosticConnectionState = "reconnecting"; break;
      case UNAVAILABLE:
        titleRes = R.string.status_unavailable; detailRes = R.string.detail_unavailable; diagnosticConnectionState = "unavailable"; break;
      case PERMISSION:
        titleRes = R.string.status_permission; detailRes = R.string.detail_permission; break;
      case MICROPHONE_ERROR:
        titleRes = R.string.status_microphone_error; detailRes = R.string.detail_microphone_error; break;
      case REVIEW:
        titleRes = R.string.status_review; detailRes = R.string.detail_review; break;
      case FINISHING:
        titleRes = R.string.status_finishing; detailRes = R.string.detail_finishing; break;
      case READY:
      default:
        titleRes = R.string.status_ready; detailRes = R.string.detail_ready_text;
        diagnosticConnectionState = connectionTracker.isOpen() ? "connected" : "not connected"; break;
    }
    String title = getString(titleRes);
    String detail = detailOverride == null ? getString(detailRes) : detailOverride;
    setPrimaryStatus(title, detail);
  }

  private void showListeningState() {
    if (!running.get()) {
      setForegroundMode(ForegroundMode.READY, getString(R.string.detail_ready_text));
    } else if (!autoTranscribe) {
      setForegroundMode(ForegroundMode.RECORDING_LOCAL, null);
    } else if (connectionTracker.isMediaConfirmed()) {
      setForegroundMode(ForegroundMode.LISTENING, null);
    } else if (connectionTracker.isOpen()) {
      setForegroundMode(ForegroundMode.VERIFYING, null);
    } else {
      setForegroundMode(ForegroundMode.RECONNECTING, reconnectDetail());
    }
  }

  private String reconnectDetail() {
    long last = connectionTracker.lastMediaAckMs();
    if (last <= 0L) return getString(R.string.detail_reconnecting);
    long seconds = Math.max(0L, (System.currentTimeMillis() - last) / 1000L);
    return getResources().getQuantityString(R.plurals.reconnect_media_age, (int) Math.min(Integer.MAX_VALUE, seconds), seconds);
  }

  private void setPrimaryStatus(String title, String detail) {
    runOnUiThread(() -> {
      statusView.setText(title);
      statusDetailView.setText(detail == null ? "" : detail);
    });
  }

  private String foregroundTitle() {
    return statusView == null ? getString(R.string.status_ready) : statusView.getText().toString();
  }

  private String diagnosticsStateText() {
    long last = connectionTracker.lastMediaAckMs();
    String age = last <= 0L
        ? getString(R.string.diagnostics_never)
        : getResources().getQuantityString(
            R.plurals.diagnostics_media_age,
            (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (System.currentTimeMillis() - last) / 1000L)),
            Math.max(0L, (System.currentTimeMillis() - last) / 1000L));
    String mic = currentMicrophoneLabel.isEmpty()
        ? getString(R.string.microphone_unknown)
        : currentMicrophoneLabel;
    return getString(R.string.diagnostics_state,
        diagnosticConnectionState,
        mic,
        currentInputDbfs,
        droppedLiveFrames,
        droppedPlaybackChunks,
        age);
  }

  private void appendDiagnostic(String message) {
    String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
        + "] " + message + "\n";
    synchronized (diagnosticLock) {
      diagnosticText = ConnectionPolicy.boundedDiagnostics(
          diagnosticText, line, appConfig.diagnosticsMaxChars);
    }
  }

  private void loadConversationHistory() {
    String saved = historyPrefs.getString(HISTORY_PREF_KEY, "");
    if (saved == null || saved.trim().isEmpty()) {
      conversationTextModel.replaceHistory("", "", "");
    } else {
      conversationTextModel.replaceHistory(saved, "", "");
    }
    renderConversationText();
  }

  private void replaceConversationHistory(JSONArray turns) {
    StringBuilder rendered = new StringBuilder();
    String lastUser = "";
    String lastAssistant = "";
    String draftTurn = "";
    String draftText = "";
    boolean userAudio = false;
    boolean assistantAudio = false;
    if (turns != null) {
      for (int i = 0; i < turns.length(); i++) {
        JSONObject turn = turns.optJSONObject(i);
        if (turn == null) continue;
        String turnId = turn.optString("turn_id", "");
        String user = turn.optString("user", "").trim();
        String assistant = turn.optString("assistant", "").trim();
        String status = turn.optString("status", "");
        if ("draft".equals(status)) {
          draftTurn = turnId;
          draftText = turn.optString("recognized_text", user).trim();
          continue;
        }
        if (!user.isEmpty()) {
          rendered.append("You\n").append(user).append("\n\n");
          lastUser = turnId;
        }
        if (!assistant.isEmpty()) {
          rendered.append("Agent\n").append(assistant).append("\n\n");
          lastAssistant = turnId;
        }
        if (turn.optBoolean("has_user_audio", false)) {
          latestUserTurnId = turnId;
          userAudio = true;
        }
        if (turn.optBoolean("has_assistant_audio", false)) {
          latestAssistantTurnId = turnId;
          assistantAudio = true;
        }
      }
    }
    String finalRendered = rendered.length() == 0
        ? getString(R.string.empty_conversation)
        : rendered.toString();
    String finalLastUser = lastUser;
    String finalLastAssistant = lastAssistant;
    String finalDraftTurn = draftTurn;
    String finalDraftText = draftText;
    boolean finalUserAudio = userAudio;
    boolean finalAssistantAudio = assistantAudio;
    runOnUiThread(() -> {
      conversationTextModel.replaceHistory(finalRendered, finalLastUser, finalLastAssistant);
      pendingTurnId = finalDraftTurn;
      replayUserAvailable = finalUserAudio;
      replayAssistantAvailable = finalAssistantAudio;
      if (!finalDraftTurn.isEmpty()) {
        draftEdit.setText(finalDraftText);
        draftEdit.setSelection(draftEdit.length());
        conversationTextModel.setLiveUser(finalDraftTurn, finalDraftText);
      } else {
        draftEdit.setText("");
      }
      persistAndRenderConversation();
      updateTranscriptPanel();
      updateReplayRow();
      conversationScrollView.post(() -> conversationScrollView.fullScroll(View.FOCUS_DOWN));
      if (autoSend && !pendingTurnId.isEmpty() && !draftEdit.getText().toString().trim().isEmpty()) {
        submitDraft();
      }
    });
  }

  private void renderConversationText() {
    if (conversationView == null || conversationScrollView == null) return;
    runOnUiThread(() -> {
      String rendered = conversationTextModel.render(getString(R.string.empty_conversation));
      if (rendered.length() > appConfig.historyCacheMaxChars) {
        rendered = rendered.substring(rendered.length() - appConfig.historyCacheMaxChars);
      }
      conversationView.setText(rendered);
      conversationScrollView.post(() -> conversationScrollView.fullScroll(View.FOCUS_DOWN));
    });
  }

  private void persistAndRenderConversation() {
    historyPrefs.edit().putString(HISTORY_PREF_KEY, conversationTextModel.confirmedText()).apply();
    renderConversationText();
  }

  private String buildLabel() {
    try {
      String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
      return getString(R.string.app_name) + " " + (version == null ? "" : version);
    } catch (Exception ignored) {
      return getString(R.string.app_name);
    }
  }

  private static String enc(String value) throws Exception {
    return URLEncoder.encode(value == null ? "" : value, "UTF-8");
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override protected void onStart() {
    super.onStart();
    connectionWanted.set(true);
    if (hasUsableNetwork() && !connectionTracker.isOpen()) connectWebSocket();
  }

  @Override protected void onStop() {
    if (running.get()) stopSession();
    connectionWanted.set(false);
    mainHandler.postDelayed(() -> closeCurrentSocket("app_backgrounded", true), 300L);
    super.onStop();
  }

  @Override protected void onDestroy() {
    running.set(false);
    if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
    stopPlaybackLocal("activity_destroyed", false);
    closeCurrentSocket("activity_destroyed", true);
    if (connectivityManager != null && networkCallback != null) {
      try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
    }
    releaseAudioEffects();
    try { if (wsClient != null) wsClient.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
    super.onDestroy();
  }
}
