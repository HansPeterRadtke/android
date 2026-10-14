package myapp.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.Intent;
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
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
  private static final String VOICE_CUES_PREF_KEY = "voice_activity_cues_v1";
  private static final String LEGACY_DRAFT_PREF_KEY = "current_message_v1";
  private static final String LEGACY_DRAFT_BACKUP_PREF_KEY = "previous_message_backup_v1";
  private static final String USER_DRAFT_PREF_KEY = "user_entered_message_v2";
  private static final String VOCAB_PREF_KEY = "vocabulary_v1";
  private static final String VOCAB_CONFIGURED_PREF_KEY = "vocabulary_configured_v1";
  private static final String MANUAL_RECORDING_FILE = "voice_unsent_manual.pcm";
  private static final String MANUAL_ARCHIVE_PREF_KEY = "manual_archive_selection_v1";
  private static final long SEND_ACK_TIMEOUT_MS = 15000L;
  private static final int PERMISSION_REQUEST = 2;
  private static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
  private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

  private enum ForegroundMode {
    READY, TEXT_ONLY, CONNECTING, VERIFYING, LISTENING, RECORDING_LOCAL, TRANSCRIBING,
    THINKING, QUEUED, BUFFERING, SPEAKING, RECONNECTING, UNAVAILABLE, PERMISSION,
    MICROPHONE_ERROR, REVIEW, ANSWER_ERROR, FINISHING
  }

  private VoiceAppConfig appConfig;
  private SharedPreferences historyPrefs;
  private VoiceSubmissionPersistence.Attempt observedSubmissionWrite;
  private Handler mainHandler;
  private OkHttpClient wsClient;
  private volatile VoiceForegroundService voiceService;
  private volatile boolean voiceServiceBound = false;
  private volatile boolean voiceServiceBindingRequested = false;
  private volatile boolean activityVisible = false;
  private volatile VoiceWebSocketListener boundVoiceListener;
  private final ServiceConnection voiceServiceConnection = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
      voiceService = ((VoiceForegroundService.LocalBinder)binder).service();
      voiceServiceBound = true;
      if (manualRecordingAvailable && !voiceService.isCapturing()
          && !voiceService.hasFinishedManualCapture() && !voiceService.userPlayback().hasAudio()) {
        byte[] recovered;
        synchronized (manualRecordingLock) { recovered = manualRecordingBuffer.toByteArray(); }
        voiceService.retainRecoveredManualRecording(recovered);
      }
      assistantPlayer.bindPlayback(voiceService.assistantPlayback());
      userPlayer.bindPlayback(voiceService.userPlayback());
      voiceServiceBindingRequested = false;
      running.set(voiceService.isCapturing());
      if(voiceService.hasFinishedManualCapture())adoptManualCapture();
      currentMicrophoneLabel = voiceService.microphoneLabel();
      if (startButton != null) startButton.setText(
          running.get() ? R.string.stop_recording : R.string.start_recording);
      if (connectionWanted.get()) connectWebSocket();
      if (captureStartPending) beginCaptureNow();
      updateServiceHealth();
    }
    @Override public void onServiceDisconnected(ComponentName name) {
      voiceService = null;
      voiceServiceBound = false;
      voiceServiceBindingRequested = false;
      connectionTracker.onClosed(connectionTracker.currentGeneration());
      setForegroundMode(ForegroundMode.RECONNECTING,
          getString(R.string.detail_reconnecting));
    }
  };

  private final VoiceForegroundService.CaptureObserver voiceCaptureObserver =
      (active, microphone, levelDbfs, error) -> {
        this.running.set(active);
        currentMicrophoneLabel = microphone;
        currentInputDbfs = levelDbfs;
        if (this.startButton != null) this.startButton.setText(
            active ? R.string.stop_recording : R.string.start_recording);
        if(!active && this.voiceService!=null && this.voiceService.hasFinishedManualCapture())adoptManualCapture();
        if (error != null && !error.isEmpty()) {
          setForegroundMode(active?ForegroundMode.RECORDING_LOCAL:
              (this.autoTranscribe?ForegroundMode.MICROPHONE_ERROR:ForegroundMode.REVIEW), error);
          appendDiagnostic("Foreground capture failed: " + error);
        } else if (!active && this.voiceService!=null && this.voiceService.hasFinishedManualCapture()) {
          adoptManualCapture();
        } else if (active && this.autoTranscribe) {
          if (this.foregroundMode == ForegroundMode.READY
              || this.foregroundMode == ForegroundMode.CONNECTING) {
            setForegroundMode(ForegroundMode.VERIFYING, null);
          }
        }
        updateServiceHealth();
      };
  private ConnectivityManager connectivityManager;
  private ConnectivityManager.NetworkCallback networkCallback;

  private TextView statusView;
  private TextView statusDetailView;
  private ProgressBar statusProgressView;
  private TextView componentHealthView;
  private TextView workerView;
  private ProgressBar workerProgressView;
  private TextView questionView;
  private TextView conversationView;
  private ScrollView conversationScrollView;
  private MaterialButton latestButton;
  private volatile boolean conversationPinnedToLatest = true;
  private volatile boolean conversationScrollInitialized = false;
  private volatile boolean conversationProgrammaticScroll = false;
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
  // An accepted WebSocket frame is not a server acknowledgement.
  private volatile boolean manualSendAwaitingAck = false;
  private volatile String manualSendText = "";
  private volatile String manualRequestId = "";
  private volatile String manualRequestText = "";
  private volatile long sendAttemptSequence = 0L;
  private volatile boolean draftContainsUnstableAsrPartial = false;
  private volatile boolean userClaimedAsrPartial = false;
  private volatile boolean automaticLiveAsrPartial = false;
  private volatile String lastFinalAsrText = "";
  private volatile String lastFinalAsrTurnId = "";
  private boolean applyingAsrDraftText = false;
  private volatile boolean stopRequested = false;
  private volatile int nextSeq = 0;
  private volatile long droppedLiveFrames = 0L;
  private volatile boolean captureTransportFailed = false;
  private volatile long droppedPlaybackChunks = 0L;
  private Thread uplinkThread;
  private Thread uplinkSenderThread;
  private volatile VoicePcmFrameQueue pcmUplinkQueue;
  private static final int MIC_NO_DATA_GRACE_MS = 3000;
  private static final int PCM_BUFFER_SECONDS = 120;

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
  private volatile boolean voiceActivityCues = true;
  private volatile boolean serverHelloReady = false;
  private volatile boolean serverCanCommit;
  private volatile String serverVersion = "";
  private volatile String serverAsr = "";
  private volatile String serverTts = "";
  private volatile boolean serverReady = false;
  private volatile boolean serverSttReady = false;
  private volatile boolean serverAgentReady = false;
  private volatile boolean serverTtsReady = false;
  private volatile boolean serverTtsPrimaryReady = false;
  private volatile boolean serverTtsFallbackReady = false;
  private final ArrayList<String> currentVocabulary = new ArrayList<>();
  private volatile boolean userVocabularyConfigured = false;
  private final Object manualRecordingLock = new Object();
  private final ByteArrayOutputStream manualRecordingBuffer = new ByteArrayOutputStream();
  private volatile boolean manualRecordingAvailable = false;
  // Recovery may outlive this screen or a newer capture. Only its owner may
  // publish the bytes; file access itself must never delay the main looper.
  private int recordingRecoveryGeneration;

  private volatile long lastLevelUiMs = 0L;
  private volatile String pendingTurnId = "";
  private volatile String latestUserTurnId = "";
  private volatile String latestAssistantTurnId = "";
  private final ConversationTextModel conversationTextModel = new ConversationTextModel();
  private volatile boolean replayUserAvailable = false;
  private volatile boolean replayAssistantAvailable = false;
  private final WorkerEventSequencePolicy workerEventSequences = new WorkerEventSequencePolicy();
  private volatile String workerQuestionInventoryJson = "[]";
  private volatile boolean workerQuestionInventoryComplete = true;
  private volatile ForegroundMode foregroundMode = ForegroundMode.READY;

  private AcousticEchoCanceler aec;
  private NoiseSuppressor noiseSuppressor;
  private AutomaticGainControl agc;
  private final Object diagnosticLock = new Object();
  private String diagnosticText = "";

  private final class LowerControlsLayout extends LinearLayout {
    LowerControlsLayout(Context context) {
      super(context);
      setOrientation(VERTICAL);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      if (useWideShortLayout()) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        return;
      }
      // LinearLayout may hand wrap-content children an UNSPECIFIED height. Use the
      // real window height instead so the lower controls can never consume the
      // entire phone and erase the conversation viewport.
      // Configuration.screenHeightDp follows the current app window, including
      // multi-window/resizing, instead of treating the physical display as the UI.
      int windowHeight = Math.round(
          getResources().getConfiguration().screenHeightDp
              * getResources().getDisplayMetrics().density);
      int minimum = getResources().getDimensionPixelSize(R.dimen.voice_lower_controls_min_height);
      float fraction = getResources().getFraction(R.fraction.voice_lower_controls_max_fraction, 1, 1);
      int cap = Math.max(minimum, Math.round(windowHeight * fraction));
      super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
  }

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
    manualRequestText=historyPrefs.getString("send_text_v1","");
    typedSendPending=!historyPrefs.getString("send_payload_v1","").isEmpty();
    VoiceSubmissionPersistence.Attempt restoringSubmission = VoiceSubmissionPersistence.SHARED.current();
    if (restoringSubmission != null) {
      manualRequestText = restoringSubmission.text;
      typedSendPending = true;
    }
    migrateLegacyDraft();
    selectedDeviceId = historyPrefs.getInt(MIC_DEVICE_PREF_KEY, AudioInputOption.DEFAULT_DEVICE_ID);
    autoTranscribe = historyPrefs.getBoolean(AUTO_TRANSCRIBE_PREF_KEY, true);
    autoSend = VoiceAutomationPolicy.effectiveAutoSend(autoTranscribe, historyPrefs.getBoolean(AUTO_SEND_PREF_KEY, true));
    voiceActivityCues = historyPrefs.getBoolean(VOICE_CUES_PREF_KEY, true);
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
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();
    connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    buildScreen();
    restoreComposerState(savedInstanceState);
    registerNetworkObserver();
    refreshPreferredMicrophonePreviewAsync();
    startConnectionMonitor();
    restoreManualRecording();
    connectionWanted.set(true);
    bindVoiceService();
    if (!hasUsableNetwork()) updateServiceHealth();
  }

  private void migrateLegacyDraft() {
    // 1.7.12 persisted all ASR guesses in the same preference as real user
    // typing. Do not silently resurrect those guesses as a user-authored draft.
    // Keep the old bytes retrievable in Settings rather than destroying them.
    if (!historyPrefs.contains(LEGACY_DRAFT_PREF_KEY)) return;
    String older = historyPrefs.getString(LEGACY_DRAFT_PREF_KEY, "");
    SharedPreferences.Editor edit = historyPrefs.edit().remove(LEGACY_DRAFT_PREF_KEY);
    if (older != null && !older.isEmpty()
        && !historyPrefs.contains(LEGACY_DRAFT_BACKUP_PREF_KEY)) {
      edit.putString(LEGACY_DRAFT_BACKUP_PREF_KEY, older);
    }
    edit.apply();
  }

  private boolean useWideShortLayout() {
    android.content.res.Configuration config = getResources().getConfiguration();
    return config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp;
  }

  private void buildScreen() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(14), dp(10), dp(14), dp(10));

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout statusStack = new LinearLayout(this);
    statusStack.setOrientation(LinearLayout.VERTICAL);
    statusView = new TextView(this);
    statusView.setId(R.id.voice_status);
    statusView.setTextSize(18);
    statusView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    statusView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusStack.addView(statusView, fullWrap());
    statusDetailView = new TextView(this);
    statusDetailView.setId(R.id.voice_status_detail);
    statusDetailView.setTextSize(13);
    statusDetailView.setPadding(0, dp(1), 0, 0);
    statusStack.addView(statusDetailView, fullWrap());
    statusProgressView = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    statusProgressView.setId(R.id.voice_status_progress);
    statusProgressView.setIndeterminate(true);
    statusProgressView.setContentDescription(getString(R.string.status_progress_description));
    statusProgressView.setVisibility(View.GONE);
    statusStack.addView(statusProgressView, fullWrap());
    header.addView(statusStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    settingsButton = new MaterialButton(this);
    settingsButton.setId(R.id.voice_settings);
    settingsButton.setText("");
    settingsButton.setIconResource(android.R.drawable.ic_menu_preferences);
    settingsButton.setContentDescription(getString(R.string.settings));
    settingsButton.setMinHeight(dp(48));
    settingsButton.setMinimumWidth(dp(48));
    LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(56), dp(48));
    settingsParams.setMarginStart(dp(8));
    header.addView(settingsButton, settingsParams);
    root.addView(header, fullWrap());

    componentHealthView = new TextView(this);
    componentHealthView.setId(R.id.voice_component_health);
    componentHealthView.setTextSize(13);
    componentHealthView.setPadding(0, dp(3), 0, dp(5));
    componentHealthView.setVisibility(View.GONE);
    root.addView(componentHealthView, fullWrap());

    workerView = new TextView(this);
    workerView.setId(R.id.voice_worker_status);
    workerView.setTextSize(14);
    workerView.setPadding(dp(10), dp(6), dp(10), dp(6));
    workerView.setVisibility(View.GONE);
    root.addView(workerView, fullWrap());
    workerProgressView = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    workerProgressView.setId(R.id.voice_worker_progress);
    workerProgressView.setMax(100);
    workerProgressView.setContentDescription(getString(R.string.worker_progress_description));
    workerProgressView.setVisibility(View.GONE);
    root.addView(workerProgressView, fullWrap());

    questionView = new TextView(this);
    questionView.setId(R.id.voice_question_status);
    questionView.setTextSize(14);
    questionView.setPadding(dp(10), dp(6), dp(10), dp(6));
    questionView.setMinHeight(dp(48));
    questionView.setGravity(Gravity.CENTER_VERTICAL);
    questionView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    questionView.setClickable(true);
    questionView.setFocusable(true);
    questionView.setOnClickListener(v -> showWorkerQuestions());
    questionView.setVisibility(View.GONE);
    root.addView(questionView, fullWrap());

    conversationView = new TextView(this);
    conversationView.setId(R.id.voice_conversation);
    conversationView.setTextSize(18);
    conversationView.setTextIsSelectable(true);
    conversationView.setPadding(0, dp(8), 0, dp(8));
    conversationScrollView = new ScrollView(this);
    conversationScrollView.setFillViewport(true);
    conversationScrollView.addView(conversationView, fullWrap());
    conversationScrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
      if (!conversationProgrammaticScroll) conversationPinnedToLatest = isConversationAtLatest();
      updateLatestButtonVisibility();
    });
    latestButton = new MaterialButton(this);
    latestButton.setId(R.id.voice_latest);
    latestButton.setText(R.string.latest);
    latestButton.setMinHeight(dp(48));
    latestButton.setVisibility(View.GONE);
    latestButton.setOnClickListener(v -> scrollConversationToLatest());
    LinearLayout conversationPanel = new LinearLayout(this);
    conversationPanel.setOrientation(LinearLayout.VERTICAL);
    conversationPanel.addView(conversationScrollView, new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    conversationPanel.addView(latestButton, fullWrap());

    userPlayer = new VoicePcmPlayerView(this, appConfig.sampleRate, appConfig.playerMaxBytes, getString(R.string.user_audio));
    userPlayer.setId(R.id.voice_user_player);
    assistantPlayer = new VoicePcmPlayerView(this, appConfig.sampleRate, appConfig.playerMaxBytes, getString(R.string.assistant_audio));
    assistantPlayer.setId(R.id.voice_assistant_player);
    assistantPlayer.setRemoteStopListener(() -> {
      activePcmPlayer = null;
      sendControl("stop_audio", "user_stopped_playback", -1, 0.0, 0.0);
      showListeningState();
    });
    LinearLayout audioContext = new LinearLayout(this);
    audioContext.setOrientation(LinearLayout.VERTICAL);
    audioContext.addView(assistantPlayer, fullWrap());
    audioContext.addView(userPlayer, fullWrap());
    WindowFractionScrollView audioScroll = new WindowFractionScrollView(this);
    audioScroll.setFillViewport(false);
    audioScroll.addView(audioContext, fullWrap());

    transcriptPanel = new LinearLayout(this);
    transcriptPanel.setId(R.id.voice_transcript_panel);
    transcriptPanel.setOrientation(LinearLayout.VERTICAL);
    TextInputLayout transcriptInput = new TextInputLayout(this);
    transcriptInput.setHint(R.string.recognized_text);
    draftEdit = new TextInputEditText(transcriptInput.getContext());
    draftEdit.setId(R.id.voice_draft);
    // Save text together with its ASR/human provenance and turn identity below.
    // Framework-only restoration invokes TextWatcher as if a human had edited
    // the field and otherwise promotes provisional ASR into a retained draft.
    draftEdit.setSaveEnabled(false);
    draftEdit.setHint(R.string.transcript_hint);
    draftEdit.setTextSize(17);
    draftEdit.setInputType(InputType.TYPE_CLASS_TEXT
        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    // Keep the composer line-based instead of mixing line and pixel constraints.
    // Pixel min/max height switches TextView out of line-count mode and made the
    // short-screen editor report/render like a one-line field.
    draftEdit.setMinLines(2);
    draftEdit.setMaxLines(useWideShortLayout() ? 3 : 6);
    transcriptInput.addView(draftEdit, fullWrap());
    draftEdit.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable editable) {
        if (!applyingAsrDraftText) {
          // Only human modifications of the current message are persisted.
          draftContainsUnstableAsrPartial = false;
          userClaimedAsrPartial = editable.length()>0;
          historyPrefs.edit().putBoolean("draft_claimed_v1",userClaimedAsrPartial).apply();
          sendControl(userClaimedAsrPartial?"draft_claim":"draft_discard","editor_changed",-1,0.0,0.0);
          if (editable.length()>0) {
            historyPrefs.edit().putString(USER_DRAFT_PREF_KEY, editable.toString()).apply();
          } else {
            historyPrefs.edit().remove(USER_DRAFT_PREF_KEY).putBoolean("draft_claimed_v1",false).apply();
          }
        }
        updateTranscriptPanel();
      }
    });
    transcriptPanel.addView(transcriptInput, fullWrap());

    LinearLayout messageActions = new LinearLayout(this);
    messageActions.setOrientation(LinearLayout.HORIZONTAL);
    messageActions.setGravity(Gravity.CENTER_VERTICAL);
    transcribeButton = new MaterialButton(this);
    transcribeButton.setId(R.id.voice_transcribe);
    transcribeButton.setText(R.string.transcribe);
    transcribeButton.setMinHeight(dp(48));
    startButton = new MaterialButton(this);
    startButton.setId(R.id.voice_start_stop);
    startButton.setText(R.string.start_recording);
    startButton.setMinHeight(dp(48));
    sendDraftButton = new MaterialButton(this);
    sendDraftButton.setId(R.id.voice_send);
    sendDraftButton.setText(R.string.send);
    sendDraftButton.setMinHeight(dp(48));
    if (useWideShortLayout()) {
      transcribeButton.setText(R.string.transcribe_wide);
      for (MaterialButton button : new MaterialButton[] {transcribeButton, startButton, sendDraftButton}) {
        button.setTextSize(12);
        button.setSingleLine(false);
        button.setMaxLines(2);
      }
    }
    messageActions.addView(transcribeButton, weightedWrap());
    messageActions.addView(startButton, weightedWrap());
    messageActions.addView(sendDraftButton, weightedWrap());
    transcriptPanel.addView(messageActions, fullWrap());

    // Historical replay controls are secondary and live in Settings. Keep no permanent replay row.
    replayRow = null;
    playUserButton = null;
    playAnswerButton = null;

    if (useWideShortLayout()) {
      LinearLayout body = new LinearLayout(this);
      body.setOrientation(LinearLayout.HORIZONTAL);
      body.setBaselineAligned(false);
      LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
      body.addView(conversationPanel, new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));

      // Conversation-first landscape hierarchy: the editable current message
      // and its primary actions are pinned and never scroll away. Contextual
      // audio is secondary and scrolls only inside the remaining space above.
      LinearLayout right = new LinearLayout(this);
      right.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
      rightParams.setMarginStart(dp(14));
      right.addView(audioScroll, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
      right.addView(transcriptPanel, fullWrap());
      body.addView(right, rightParams);
      root.addView(body, bodyParams);
    } else {
      conversationPanel.setMinimumHeight(dp(96));
      root.addView(conversationPanel, new LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
      LowerControlsLayout lowerControls = new LowerControlsLayout(this);
      lowerControls.addView(audioScroll, fullWrap());
      lowerControls.addView(transcriptPanel, fullWrap());
      root.addView(lowerControls, fullWrap());
    }

    setContentView(root);
    loadConversationHistory();
    setForegroundMode(ForegroundMode.READY, null);
    applyingAsrDraftText=true;
    draftEdit.setText(historyPrefs.getString(USER_DRAFT_PREF_KEY, ""));
    userClaimedAsrPartial=draftEdit.length()>0;
    historyPrefs.edit().putBoolean("draft_claimed_v1",userClaimedAsrPartial).apply();
    applyingAsrDraftText=false;
    draftEdit.setSelection(draftEdit.length());
    updateServiceHealth();
    updateTranscriptPanel();

    startButton.setOnClickListener(v -> {
      if (running.get()) stopSession();
      else startSession();
    });
    settingsButton.setOnClickListener(v -> showSettingsDialog());
    sendDraftButton.setOnClickListener(v -> submitDraft());
    transcribeButton.setOnClickListener(v -> transcribeManualRecording());
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
    // Unlike the previous network-gated microphone, local PCM capture may
    // begin during a transient connection outage. Its bounded queue is kept
    // independently of WebSocket availability; a full queue is a real error.
    connectionWanted.set(true);
    if (autoTranscribe && !connectionTracker.isOpen()) connectWebSocket();
    beginCaptureNow();
  }

  private void beginCaptureNow() {
    VoiceForegroundService service=voiceService;
    if(service==null) {captureStartPending=true;bindVoiceService();return;}
    if(!running.compareAndSet(false,true)) {captureStartPending=false;return;}
    if(!startVoiceForegroundService()) {running.set(false);return;}
    captureStartPending=false;stopRequested=false;
    recordingRecoveryGeneration++;
    if(!autoTranscribe) {
      synchronized(manualRecordingLock) {manualRecordingBuffer.reset();}
      manualRecordingAvailable=false;
    }
    if(!service.startCapture(selectedDeviceId,voiceActivityCues,autoTranscribe)) {
      running.set(false);setForegroundMode(ForegroundMode.MICROPHONE_ERROR,service.captureError());return;
    }
    manualTranscriptPendingSubmission = false;
    manualTranscribePending = false;
    historyPrefs.edit().remove(MANUAL_ARCHIVE_PREF_KEY).apply();
    setForegroundMode(autoTranscribe?(connectionTracker.isOpen()?ForegroundMode.VERIFYING:ForegroundMode.CONNECTING)
        :ForegroundMode.RECORDING_LOCAL,null);
    startButton.setText(R.string.stop_recording);updateTranscriptPanel();
  }

  private void adoptManualCapture() {
    VoiceForegroundService service=voiceService;
    if(service==null || service.isCapturing() || !service.hasFinishedManualCapture())return;
    recordingRecoveryGeneration++;
    byte[] data=service.userPlayback().snapshot();
    synchronized(manualRecordingLock) {manualRecordingBuffer.reset();manualRecordingBuffer.write(data,0,data.length);}
    manualRecordingAvailable=data.length>0;
    updateTranscriptPanel();
    setForegroundMode(ForegroundMode.REVIEW,getString(R.string.recording_ready));
  }

  private void stopSession() {
    captureStartPending = false;
    if (!running.getAndSet(false)) return;
    VoiceForegroundService service=voiceService;
    if (service!=null) service.stopCapture();
    // The foreground service remains active until its own network sender has
    // transmitted queued microphone frames, independently of this Activity.
    runOnUiThread(() -> startButton.setText(R.string.start_recording));
    setForegroundMode(ForegroundMode.FINISHING, null);
  }

  private boolean startVoiceForegroundService() {
    Intent intent = new Intent(this, VoiceForegroundService.class).setAction(VoiceForegroundService.ACTION_START);
    try {
      ContextCompat.startForegroundService(this, intent);
      return true;
    } catch (RuntimeException failure) {
      running.set(false);
      appendDiagnostic("Foreground service failed: " + failure.getClass().getSimpleName());
      setForegroundMode(ForegroundMode.MICROPHONE_ERROR, null);
      return false;
    }
  }

  // Local VAD is only a hint. It must never decide that the user intended to
  // interrupt reply audio; Jetson's semantic interruption classifier owns that
  // decision and sends an explicit audio cancel when takeover is confirmed.
  void handleLocalVoiceActivityStarted(int sequence, boolean builtIn) {
    sendControl("voice_activity", "start", sequence, 0.0, 0.0);
    if (voiceActivityCues && !builtIn) VoiceActivityCue.start();
    appendDiagnostic("Local voice activity started");
  }

  private void stopVoiceForegroundService() {
    try {
      stopService(new Intent(this, VoiceForegroundService.class).setAction(VoiceForegroundService.ACTION_STOP));
    } catch (RuntimeException ignored) {}
  }

  private void bindVoiceService() {
    if (voiceServiceBound || voiceServiceBindingRequested) return;
    voiceServiceBindingRequested = bindService(
        new Intent(this, VoiceForegroundService.class),
        voiceServiceConnection, Context.BIND_AUTO_CREATE);
    if (!voiceServiceBindingRequested) {
      setForegroundMode(ForegroundMode.UNAVAILABLE,
          getString(R.string.detail_unavailable));
    }
  }

  private void connectWebSocket() {
    if (!connectionWanted.get() || connectionTracker.isOpen() || wsConnecting.get()) return;
    VoiceForegroundService service=voiceService;
    if (service==null) {
      bindVoiceService();
      return;
    }
    long generation=connectionTracker.beginConnect();
    reconnectScheduledGeneration=-1L;
    wsConnecting.set(true);
    setForegroundMode(ForegroundMode.CONNECTING,null);
    VoiceWebSocketListener listener=new VoiceWebSocketListener(generation);
    boundVoiceListener=listener;
    webSocket=service.facade();
    try {
      service.attach(listener,voiceCaptureObserver,generation,conversationId);
    } catch(Exception failure) {
      wsConnecting.set(false);
      connectionTracker.onClosed(generation);
      appendDiagnostic("Connection binding failed: "+failure.getClass().getSimpleName());
      scheduleReconnect(generation,false);
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
    manualSendAwaitingAck = false;
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
    manualSendAwaitingAck = false;
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
      // A stale Activity listener must never cancel the service-owned
      // transport: another Activity may already be bound to this socket.
      return activityVisible && connectionTracker.owns(generation);
    }

    @Override public void onOpen(WebSocket socket, Response response) {
      if (!current(socket) || !connectionTracker.onOpen(generation, System.currentTimeMillis())) return;
      webSocket = socket;
      wsConnecting.set(false);
      serverCanCommit=false;
      appendDiagnostic("WebSocket connected.");
      sendClientState();
      if (running.get()) {
        setForegroundMode(ForegroundMode.VERIFYING, null);
        // Automatic capture belongs exclusively to VoiceForegroundService.
        // Starting this Activity's old capture thread as well would compete
        // for the same microphone route after reconnect/rebind.
        // Both recording modes remain owned by the foreground service.
      } else {
        setForegroundMode(ForegroundMode.READY, getString(R.string.detail_ready_text));
      }
      if (captureStartPending) beginCaptureNow();
    }

    @Override public void onMessage(WebSocket socket, String text) {
      if (!current(socket)) return;
      // All text/UI state transitions must run in wire order on Android's main
      // thread; the previous background callback posted inconsistent UI updates.
      runOnUiThread(() -> {
        if (connectionTracker.owns(generation) && connectionTracker.isOpen()) {
          handleWsJson(text, generation);
        }
      });
    }

    @Override public void onMessage(WebSocket socket, ByteString bytes) {
      if (!current(socket)) return;
      runOnUiThread(() -> {
        if(connectionTracker.owns(generation) && connectionTracker.isOpen())
          handleWsAudio(bytes.toByteArray());
      });
    }

    @Override public void onClosing(WebSocket socket, int code, String reason) {
      if (current(socket)) socket.close(code, reason);
    }

    @Override public void onClosed(WebSocket socket, int code, String reason) {
      if (!connectionTracker.onClosed(generation)) return;
      if (webSocket == socket) webSocket = null;
      wsConnecting.set(false);
      // Transport loss cannot be treated as confirmed delivery. Retain text for
      // deliberate retry rather than silently discarding or duplicating a turn.
      manualSendAwaitingAck = false;
      appendDiagnostic("WebSocket closed: " + code + " " + reason);
      serverHelloReady = false;
      clearComponentHealth();
      updateServiceHealth();
      // Service reconnects its owned socket. The view remains attached to the
      // same logical session generation; rebinding here raced its reconnect.
    }

    @Override public void onFailure(WebSocket socket, Throwable failure, Response response) {
      if (!connectionTracker.onClosed(generation)) return;
      if (webSocket == socket) webSocket = null;
      wsConnecting.set(false);
      manualSendAwaitingAck = false;
      appendDiagnostic("WebSocket failure: " + failure.getClass().getSimpleName());
      serverHelloReady = false;
      clearComponentHealth();
      updateServiceHealth();
      // Reconnect is owned by VoiceForegroundService. Never create a second
      // socket or consume another Activity generation from this callback.
      if (!connectionWanted.get()) setForegroundMode(ForegroundMode.READY, null);
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
          updateTranscriptPanel();
        }
        return;
      }
      if ("hello".equals(type)) {
        serverHelloReady = true;
        serverCanCommit=obj.optInt("commit_protocol",0)==1;
        sendClientState();
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
        // OkHttp callbacks are NOT the UI thread. submitDraft reads/updates Views.
        if (typedSendPending) runOnUiThread(this::submitDraft);
        if (manualTranscribePending) transcribeManualRecording();
        if (!running.get() && foregroundMode != ForegroundMode.THINKING && !manualTranscribePending && !manualSendAwaitingAck) setForegroundMode(ForegroundMode.READY, getString(R.string.detail_ready_text));
      } else if ("history".equals(type)) {
        replaceConversationHistory(obj.optJSONArray("turns"));
      } else if ("state".equals(type)) {
        String state = obj.optString("state", "");
        if ("transcribing".equals(state))setForegroundMode(ForegroundMode.TRANSCRIBING,null);
        else if("checking_turn".equals(state))setForegroundMode(ForegroundMode.TRANSCRIBING,getString(R.string.checking_turn));
        else if("generating_speech".equals(state))setForegroundMode(ForegroundMode.BUFFERING,getString(R.string.generating_speech));
        else if ("thinking".equals(state)) setForegroundMode(ForegroundMode.THINKING, null);
        else if ("queued".equals(state)) {
          setForegroundMode(ForegroundMode.QUEUED, null);
        } else if ("awaiting_send".equals(state)) {
          pendingTurnId = obj.optString("turn_id", pendingTurnId);
          if (!userClaimedAsrPartial && pendingTurnId.equals(lastFinalAsrTurnId)) {
            setAsrDraftText(lastFinalAsrText, false);
          }
          setForegroundMode(ForegroundMode.REVIEW, null);
          updateTranscriptPanel();
        } else if ("playback_completed".equals(state)) {
          if(foregroundMode==ForegroundMode.SPEAKING || foregroundMode==ForegroundMode.BUFFERING)
            showListeningState();
        } else if ("listening".equals(state)) {
          if (pendingTurnId.isEmpty() && foregroundMode != ForegroundMode.ANSWER_ERROR)
            showListeningState();
        }
      } else if ("asr".equals(type)) {
        connectionTracker.onMediaAck(listenerGeneration, System.currentTimeMillis());
        String heard = obj.optString("text", "").trim();
        if ("artifact".equals(event)) {
          discardRejectedAsrPartial();
        } else if ("partial".equals(event)) {
          if (!heard.isEmpty() && pendingTurnId.isEmpty()) showSpeechPartial(heard);
        } else if ("final".equals(event)) {
          pendingTurnId = obj.optString("turn_id", "");
          latestUserTurnId = pendingTurnId;
          replayUserAvailable = obj.optBoolean("has_user_audio", false);
          if (manualTranscribePending) {
            manualTranscriptPendingSubmission = true;
            manualTranscribePending = false;
          }
          lastFinalAsrText = heard;
          lastFinalAsrTurnId = pendingTurnId;
          showSpeechFinal(heard, pendingTurnId);
          updateReplayRow();
          if (!autoSend) setForegroundMode(ForegroundMode.REVIEW, null);
          updateTranscriptPanel();
        }
      } else if ("turn".equals(type) && "submitted".equals(event)) {
        String submitted = obj.optString("text", "");
        String turnId = obj.optString("turn_id", "");
        boolean acknowledgesCurrentRecording = manualTranscriptPendingSubmission
            && !manualTranscribePending && !running.get()
            && !turnId.isEmpty() && turnId.equals(lastFinalAsrTurnId);
        if (!submitted.trim().isEmpty()) {
          conversationTextModel.confirmUser(turnId, submitted);
          automaticLiveAsrPartial = false;
          if (turnId.equals(lastFinalAsrTurnId)) {
            lastFinalAsrTurnId = "";
            lastFinalAsrText = "";
          }
          persistAndRenderConversation();
        }
        if (pendingTurnId.equals(turnId)) pendingTurnId = "";
        String expectedTurn="";
        try {
          JSONObject sent=new JSONObject(historyPrefs.getString("send_payload_v1","{}"));
          expectedTurn=sent.optString("turn_id", "turn-client-"+sent.optString("client_request_id"));
        } catch(Exception ignored) {}
        String acknowledgedPayload = historyPrefs.getString("send_payload_v1", "");
        boolean manualAcknowledged = turnId.equals(expectedTurn)
            && submitted.equals(historyPrefs.getString("send_text_v1",""))
            && VoiceSubmissionPersistence.SHARED.isDurable(acknowledgedPayload);
        if (manualAcknowledged) {
          historyPrefs.edit().remove("send_payload_v1").remove("send_text_v1").apply();
          VoiceSubmissionPersistence.SHARED.acknowledge(acknowledgedPayload);
          typedSendPending=false;
          manualSendAwaitingAck = false;
          manualSendText = "";
          manualRequestId = "";
          manualRequestText = "";
        }
        // Automatic speech never clears unrelated human text. A matching
        // manual acknowledgement may clear only its own unchanged text.
        clearSubmittedDraft(submitted, manualAcknowledged);
        // A late acknowledgement for another message cannot consume unsent
        // media or cancel a newer transcription. Clear only the recording
        // associated with this exact durable manual submission.
        if (manualAcknowledged && acknowledgesCurrentRecording) {
          clearManualRecording();
        }
        updateTranscriptPanel();
        if (!manualTranscribePending) setForegroundMode(ForegroundMode.THINKING, null);
      } else if ("assistant".equals(type)) {
        String reply = obj.optString("text", "");
        String turnId = obj.optString("turn_id", "");
        if ("draft".equals(event) || "partial".equals(event)) {
          if (!reply.trim().isEmpty()) {
            conversationTextModel.setLiveAssistant(turnId, reply);
            renderConversationText();
          }
        } else if ("final".equals(event)) {
          if (!turnId.isEmpty()) latestAssistantTurnId = turnId;
          replayAssistantAvailable = obj.optBoolean("has_assistant_audio", true);
          if (!reply.trim().isEmpty()) {
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
        if ("answer".equals(stage)) {
          setForegroundMode(ForegroundMode.ANSWER_ERROR,
              getString(R.string.detail_answer_failed));
          appendDiagnostic("SWAAG response failed for " + obj.optString("turn_id", ""));
          return;
        }
        if ("submit".equals(stage)) {
          manualSendAwaitingAck = false;
          manualSendText = "";
          typedSendPending = false;
          if ("draft_not_found".equals(obj.optString("message", ""))) pendingTurnId = "";
          // Retain the exact outbox identity and text even on rejection. A
          // conflicting identity cannot be replaced with a new execution key.
          setForegroundMode(ForegroundMode.REVIEW,
              getString(R.string.submit_rejected_retry));
          appendDiagnostic("Server rejected send: " + obj.optString("message", "unknown"));
          updateTranscriptPanel();
          return;
        }
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
      state.put("media_protocol",1);
      state.put("auto_commit_supported",true);
      state.put("build", buildLabel());
      state.put("conversation", conversationId);
      state.put("auto_transcribe", autoTranscribe);
      state.put("auto_send", VoiceAutomationPolicy.effectiveAutoSend(autoTranscribe, autoSend)
          && serverCanCommit
          && (voiceService==null || voiceService.captureError().isEmpty()));
      state.put("microphone_label", currentMicrophoneLabel);
      state.put("draft_claimed",userClaimedAsrPartial);
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
      obj.put("client_wall_time_ms", System.currentTimeMillis());
      obj.put("client_elapsed_ms", android.os.SystemClock.elapsedRealtime());
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
    VoicePcmFrameQueue queue = pcmUplinkQueue;
    if (queue == null || len <= 0) return false;
    if (queue.offer(pcm, len)) return true;

    // A full local PCM queue is not a healthy microphone stream. Unlike the
    // previous silently dropped frame, explicitly stop capture and report
    // the lost-data condition; do not pretend that the user turn was complete.
    droppedLiveFrames++;
    captureTransportFailed = true;
    running.set(false);
    queue.close();
    setForegroundMode(ForegroundMode.MICROPHONE_ERROR,
        getString(R.string.error_uplink_queue_full));
    appendDiagnostic("Audio input exceeds bounded uplink queue capacity; voice stopped");
    return false;
  }

  private void startPcmSender() {
    VoicePcmFrameQueue queue = pcmUplinkQueue;
    if (queue == null) return;
    Thread sender = new Thread(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          byte[] next = queue.awaitHead(100L);
          if (next == null) {
            if (queue.isClosed()) break;
            continue;
          }
          WebSocket socket = webSocket;
          if (socket == null || !connectionTracker.isOpen()
              || !ConnectionPolicy.shouldSendAudio(socket.queueSize(),
                  appConfig.maxQueuedAudioBytes)) {
            Thread.sleep(20L);
            continue;
          }
          if (!socket.send(ByteString.of(next))) {
            // Rejecting an enqueue is an exact transport failure. The frame
            // remains at the head of the local PCM queue for a reconnection.
            invalidateCurrentConnection("socket_rejected_audio_frame");
            Thread.sleep(20L);
            continue;
          }
          if (!queue.commitAcceptedHead(next)) {
            throw new IllegalStateException("Audio queue changed during transport");
          }
        }
        if (queue.isClosed() && queue.pendingFrames() == 0 && !captureTransportFailed) {
          // The end-of-turn marker cannot overtake microphone frames queued by
          // capture. Do not submit an incomplete command following a mic failure.
          sendControl("end_utterance", "capture_stopped", nextSeq, 0.0, 0.0);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (Exception failure) {
        captureTransportFailed = true;
        running.set(false);
        setForegroundMode(ForegroundMode.MICROPHONE_ERROR,
            getString(R.string.error_uplink_queue_failed));
        appendDiagnostic("Audio uplink worker failure: " + failure.getClass().getSimpleName());
      } finally {
        stopVoiceForegroundService();
      }
    }, "voice-pcm-network-sender");
    sender.setDaemon(false);
    uplinkSenderThread = sender;
    sender.start();
  }

  private void ensureUplinkThread() {
    if (uplinkThread != null && uplinkThread.isAlive()) return;
    uplinkThread = new Thread(this::uplinkLoop, "voice-microphone-capture");
    uplinkThread.setPriority(Thread.MAX_PRIORITY);
    uplinkThread.start();
  }

  private void uplinkLoop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
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
      boolean communicationPlayback = selectedInput.isBluetooth();
      assistantPlayer.setCommunicationPlayback(communicationPlayback);
      userPlayer.setCommunicationPlayback(communicationPlayback);
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
      LocalVadGate localVad = new LocalVadGate(appConfig.frameMs);
      while (running.get()) {
        int got = readFully(recorder, capture, samplesPerChunk);
        if (got <= 0) break;
        byte[] pcm = resampleToConfiguredPcm(capture, got, captureRate);
        currentInputDbfs = pcmDbfs(pcm, pcm.length);
        long now = System.currentTimeMillis();
        if (now - lastLevelUiMs >= appConfig.levelUpdateMs) {
          lastLevelUiMs = now;
          updateServiceHealth();
        }
        if (autoTranscribe) {
          LocalVadGate.Result vad = localVad.accept(pcm, currentInputDbfs);
          if (vad.started) {
            handleLocalVoiceActivityStarted(nextSeq, builtIn);
          }
          // Never let local VAD discard microphone audio. Jetson owns authoritative
          // VAD/endpointing; this gate is UI/diagnostic feedback only.
          sendAudioFrame(pcm, pcm.length);
          if (vad.stopped) {
            sendControl("voice_activity", "stop", nextSeq, 0.0, 0.0);
            if (voiceActivityCues && !builtIn) VoiceActivityCue.stop();
            appendDiagnostic("Local voice activity stopped");
          }
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
      // The dedicated sender emits end_utterance after queued PCM drains.
    } catch (Exception failure) {
      captureTransportFailed = true;
      running.set(false);
      setForegroundMode(ForegroundMode.MICROPHONE_ERROR,
          getString(R.string.error_microphone_capture_failed));
      appendDiagnostic("Microphone failed: " + failure.getClass().getSimpleName() + " " + failure.getMessage());
      runOnUiThread(() -> startButton.setText(R.string.start_conversation));
    } finally {
      releaseAudioEffects();
      try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
      try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
      try { if (route != null) route.release(); } catch (Exception ignored) {}
      if (!autoTranscribe) {
        mainHandler.post(this::finishManualRecordingCapture);
        stopVoiceForegroundService();
      } else {
        VoicePcmFrameQueue queue = pcmUplinkQueue;
        if (queue != null) queue.close();
      }
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

  private void refreshPreferredMicrophonePreviewAsync() {
    Thread preview = new Thread(() -> {
      refreshPreferredMicrophonePreview();
      if (mainHandler != null) mainHandler.post(this::updateServiceHealth);
    }, "voice-microphone-preview");
    preview.setDaemon(true);
    preview.start();
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

    MaterialButton recordings=new MaterialButton(this);
    recordings.setText(R.string.saved_recordings);recordings.setMinHeight(dp(48));
    recordings.setEnabled(!running.get());recordings.setOnClickListener(v->showSavedRecordings());
    content.addView(recordings,fullWrap());

    MaterialCheckBox cues = new MaterialCheckBox(this);
    cues.setText(R.string.voice_activity_sounds);
    cues.setChecked(voiceActivityCues);
    cues.setMinHeight(dp(48));
    content.addView(cues, fullWrap());

    TextInputLayout vocabLayout = new TextInputLayout(this);
    vocabLayout.setId(R.id.voice_vocabulary);
    vocabLayout.setHint(R.string.vocabulary_title);
    TextInputEditText vocabEdit = new TextInputEditText(vocabLayout.getContext());
    vocabEdit.setHint(R.string.vocabulary_hint);
    vocabEdit.setMinLines(useWideShortLayout() ? 2 : 4);
    vocabEdit.setMaxLines(useWideShortLayout() ? 4 : 8);
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

    if (replayUserAvailable || replayAssistantAvailable) {
      TextView audioTitle = new TextView(this);
      audioTitle.setText(R.string.recent_audio);
      audioTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      audioTitle.setTextSize(16);
      audioTitle.setPadding(0, dp(12), 0, 0);
      content.addView(audioTitle, fullWrap());
      LinearLayout recentAudio = new LinearLayout(this);
      recentAudio.setOrientation(LinearLayout.HORIZONTAL);
      MaterialButton loadUser = new MaterialButton(this);
      loadUser.setText(R.string.load_mine);
      loadUser.setMinHeight(dp(48));
      loadUser.setEnabled(replayUserAvailable);
      MaterialButton loadAssistant = new MaterialButton(this);
      loadAssistant.setText(R.string.load_answer);
      loadAssistant.setMinHeight(dp(48));
      loadAssistant.setEnabled(replayAssistantAvailable);
      recentAudio.addView(loadUser, weightedWrap());
      recentAudio.addView(loadAssistant, weightedWrap());
      content.addView(recentAudio, fullWrap());
      loadUser.setOnClickListener(v -> requestReplay("user"));
      loadAssistant.setOnClickListener(v -> requestReplay("assistant"));
    }

    String previousDraft = historyPrefs.getString(LEGACY_DRAFT_BACKUP_PREF_KEY, "");
    if (previousDraft != null && !previousDraft.isEmpty()) {
      MaterialButton restore = new MaterialButton(this);
      restore.setText(R.string.restore_previous_message);
      restore.setMinHeight(dp(48));
      content.addView(restore, fullWrap());
      restore.setOnClickListener(v -> {
        // This is an explicit user decision to reclaim legacy text.
        draftEdit.setText(previousDraft);
        draftEdit.setSelection(draftEdit.length());
        historyPrefs.edit().remove(LEGACY_DRAFT_BACKUP_PREF_KEY).apply();
        updateTranscriptPanel();
      });
    }

    diagnosticsButton = new MaterialButton(this);
    diagnosticsButton.setId(R.id.voice_diagnostics);
    diagnosticsButton.setText(R.string.diagnostics);
    diagnosticsButton.setMinHeight(dp(48));
    content.addView(diagnosticsButton, fullWrap());

    ScrollView settingsScroll = new ScrollView(this);
    settingsScroll.setFillViewport(true);
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
    });
    cues.setOnCheckedChangeListener((button, checked) -> {
      voiceActivityCues = checked;
      historyPrefs.edit().putBoolean(VOICE_CUES_PREF_KEY, checked).apply();
    });
    saveVocab.setOnClickListener(v -> {
      setVocabularyFromText(vocabEdit.getText() == null ? "" : vocabEdit.getText().toString(), true);
      sendVocabulary();
      saveVocab.setText(R.string.vocabulary_saved);
    });
    diagnosticsButton.setOnClickListener(v -> {
      dialog.dismiss();
      showDiagnosticsDialog();
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

  void handleWorkerEvent(JSONObject obj) {
    String workerId = obj.optString("worker_id", "").trim();
    String status = obj.optString("status", "none").trim();
    String eventType = obj.optString("event_type", "").trim();
    String message = obj.optString("message", "").trim();
    int sequence = obj.optInt("sequence", 0);
    if (!workerEventSequences.accept(workerId, sequence)) return;
    boolean visible = VoiceOverviewPolicy.showWorker(status);
    String label = workerLabel(status, message);
    JSONObject semantic = obj.optJSONObject("semantic_status");
    int openQuestions = semantic == null ? 0 : Math.max(0, semantic.optInt("open_questions", 0));
    int blockingQuestions = semantic == null ? 0 : Math.max(0, semantic.optInt("blocking_questions", 0));
    boolean questionsVisible = VoiceOverviewPolicy.showQuestions(openQuestions);
    boolean workerProgressVisible = VoiceOverviewPolicy.showWorkerProgress(status);
    String workerProgressKind = semantic == null ? "" : semantic.optString("progress_kind", "").trim();
    boolean intentionallyEndless = VoiceOverviewPolicy.isIntentionallyEndless(workerProgressKind);
    boolean hasWorkerPercent = semantic != null && !semantic.isNull("progress_percent");
    int workerPercent = hasWorkerPercent
        ? Math.max(0, Math.min(100, (int) Math.round(semantic.optDouble("progress_percent", 0.0))))
        : 0;
    if ("snapshot".equals(obj.optString("event", ""))) {
      workerQuestionInventoryComplete = obj.optBoolean("question_inventory_complete", false);
      JSONArray exact = obj.optJSONArray("questions");
      workerQuestionInventoryJson = exact == null ? "[]" : exact.toString();
    }
    String questionLabel = questionOverviewLabel(openQuestions, blockingQuestions);
    runOnUiThread(() -> {
      workerView.setText(label);
      workerView.setVisibility(visible ? View.VISIBLE : View.GONE);
      workerProgressView.setIndeterminate(workerProgressVisible && !hasWorkerPercent);
      if (hasWorkerPercent) workerProgressView.setProgress(workerPercent);
      workerProgressView.setContentDescription(getString(
          intentionallyEndless ? R.string.worker_progress_endless_description
              : R.string.worker_progress_description));
      workerProgressView.setVisibility(workerProgressVisible ? View.VISIBLE : View.GONE);
      questionView.setText(questionLabel);
      questionView.setContentDescription(questionLabel);
      questionView.setTypeface(Typeface.DEFAULT, blockingQuestions > 0 ? Typeface.BOLD : Typeface.NORMAL);
      questionView.setVisibility(questionsVisible ? View.VISIBLE : View.GONE);
    });
  }

  private String questionOverviewLabel(int openQuestions, int blockingQuestions) {
    String base = blockingQuestions > 0
        ? getString(R.string.background_questions_with_blocking, openQuestions, blockingQuestions)
        : getResources().getQuantityString(
            R.plurals.background_questions_available, openQuestions, openQuestions);
    if (!workerQuestionInventoryComplete) {
      return base + " " + getString(R.string.worker_questions_details_unavailable);
    }
    try {
      JSONArray questions = new JSONArray(workerQuestionInventoryJson);
      if (questions.length() > 0) {
        String exact = questions.optJSONObject(0) == null
            ? "" : questions.optJSONObject(0).optString("question", "").trim();
        if (!exact.isEmpty()) {
          int limit = getResources().getInteger(R.integer.worker_question_preview_chars);
          if (exact.length() > limit) exact = exact.substring(0, limit - 1).trim() + "…";
          return base + " " + getString(R.string.worker_questions_top_question, exact);
        }
      }
    } catch (Exception ignored) {}
    return base;
  }

  String workerQuestionDetailsText() {
    if (!workerQuestionInventoryComplete) return getString(R.string.worker_questions_details_unavailable);
    try {
      JSONArray questions = new JSONArray(workerQuestionInventoryJson);
      if (questions.length() == 0) return getString(R.string.worker_questions_none);
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < questions.length(); i++) {
        JSONObject question = questions.optJSONObject(i);
        if (question == null) continue;
        if (out.length() > 0) out.append("\n\n");
        String criticality = question.optString("criticality", "optional").trim();
        String importance = question.optString("importance", "normal").trim();
        String worker = question.optString("worker_id", "").trim();
        out.append(i + 1).append(". ")
            .append("blocking".equals(criticality)
                ? getString(R.string.worker_question_blocking)
                : getString(R.string.worker_question_optional));
        if (!importance.isEmpty()) out.append(" • ").append(importance);
        if (!worker.isEmpty()) out.append(" • ").append(worker);
        String exact = question.optString("question", "");
        if (!exact.isEmpty()) out.append("\n").append(exact);
        String reason = question.optString("reason", "").trim();
        if (!reason.isEmpty()) out.append("\n").append(getString(R.string.worker_question_reason, reason));
        String assumption = question.optString("assumption_if_unanswered", "").trim();
        if (!assumption.isEmpty()) {
          out.append("\n").append(getString(R.string.worker_question_assumption, assumption));
        }
      }
      return out.length() == 0 ? getString(R.string.worker_questions_none) : out.toString();
    } catch (Exception failure) {
      return getString(R.string.worker_questions_details_unavailable);
    }
  }

  private void showWorkerQuestions() {
    String details = workerQuestionDetailsText();
    TextView body = new TextView(this);
    body.setText(details);
    body.setTextSize(16);
    body.setTextIsSelectable(true);
    body.setPadding(dp(20), dp(12), dp(20), dp(12));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(body, fullWrap());
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.worker_questions_title)
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .show();
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
    boolean compactProgress = "working".equals(status)
        || "queued".equals(status)
        || "cancellation_requested".equals(status);
    if (compactProgress && clean.length() > appConfig.workerPreviewMaxChars) {
      clean = clean.substring(0, appConfig.workerPreviewMaxChars - 1).trim() + "…";
    }
    return clean.isEmpty() ? base : base + "\n" + clean;
  }

  private void submitDraft() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      runOnUiThread(this::submitDraft);
      return;
    }
    VoiceSubmissionPersistence.Attempt pendingWrite = VoiceSubmissionPersistence.SHARED.current();
    String queued = pendingWrite == null ? historyPrefs.getString("send_payload_v1", "") : pendingWrite.payload;
    String value = pendingWrite == null ? (queued.isEmpty() ? draftEdit.getText().toString()
        : historyPrefs.getString("send_text_v1", "")) : pendingWrite.text;
    if (value.trim().isEmpty()) {
      setForegroundMode(ForegroundMode.REVIEW, getString(R.string.transcript_empty));
      return;
    }
    // Tapping Send is an explicit human confirmation of the visible text, even
    // when automatic ASR has not emitted a final. Never lock Send on an ASR partial.
    if (draftContainsUnstableAsrPartial) {
      draftContainsUnstableAsrPartial = false;
      userClaimedAsrPartial = true;
    }
    if (manualSendAwaitingAck) {
      setForegroundMode(ForegroundMode.REVIEW,
          getString(R.string.submit_waiting_confirmation));
      return;
    }
    try {
      JSONObject obj;
      if(!queued.isEmpty()) {
        obj=new JSONObject(queued);
        value=obj.getString("text");
      } else {
        obj=new JSONObject();
        if(!pendingTurnId.isEmpty()) {
          obj.put("type","submit"); obj.put("turn_id",pendingTurnId);
        } else {
          obj.put("type","text_turn");
          manualRequestId=java.util.UUID.randomUUID().toString().replace("-","");
          obj.put("client_request_id",manualRequestId);
        }
        manualRequestText=value; obj.put("text",value);
      }
      // Protect the user-requested send/reply across Activity unbinding. This
      // starts media-playback ownership without requesting microphone capture.
      if (!startVoiceForegroundService()) return;
      if (queued.isEmpty() || (pendingWrite != null && !pendingWrite.durable())) {
        final SharedPreferences preferences = historyPrefs;
        VoiceSubmissionPersistence.Attempt attempt = VoiceSubmissionPersistence.SHARED.retain(
            queued.isEmpty() ? obj.toString() : queued, value,
            (payload, text) -> preferences.edit().putString("send_payload_v1", payload)
                .putString("send_text_v1", text).commit());
        typedSendPending = true;
        observeSubmissionWrite(attempt);
        return;
      }
      WebSocket socket=webSocket;
      if(socket==null || !connectionTracker.isOpen() || !serverHelloReady) {
        typedSendPending=true; connectionWanted.set(true);
        setForegroundMode(ForegroundMode.CONNECTING,getString(R.string.submit_queued));
        if(hasUsableNetwork())connectWebSocket(); else updateServiceHealth();
        updateTranscriptPanel(); return;
      }
      // Mark in-flight BEFORE queueing to avoid a fast ACK racing the flag.
      manualSendText = value;
      manualSendAwaitingAck = true;
      long attempt = ++sendAttemptSequence;
      if (!socket.send(obj.toString())) throw new IOException("WebSocket send rejected");
      typedSendPending = false;
      // Lost ACKs must not leave Send apparently working but permanently inert.
      // The same request id is reused for a retry, allowing server deduplication.
      mainHandler.postDelayed(() -> {
        if (isDestroyed() || !manualSendAwaitingAck || sendAttemptSequence != attempt) return;
        manualSendAwaitingAck = false;
        setForegroundMode(ForegroundMode.REVIEW, getString(R.string.submit_unconfirmed));
        appendDiagnostic("Send ACK timeout; retaining draft for retry");
        updateTranscriptPanel();
      }, SEND_ACK_TIMEOUT_MS);
      // A WebSocket queue accepting bytes is not proof of delivery. Do not clear
      // the text or disable Send; the server's 'turn/submitted' is the ACK.
      setPrimaryStatus(getString(R.string.sending_transcript), getString(R.string.detail_thinking));
      updateTranscriptPanel();
    } catch (Exception failure) {
      manualSendAwaitingAck = false;
      manualSendText = "";
      typedSendPending = true;
      appendDiagnostic("Submit failed: " + failure.getClass().getSimpleName());
      setForegroundMode(ForegroundMode.RECONNECTING, getString(R.string.submit_queued));
      updateTranscriptPanel();
    }
  }

  private void observeSubmissionWrite(VoiceSubmissionPersistence.Attempt attempt) {
    if (observedSubmissionWrite == attempt) return;
    observedSubmissionWrite = attempt;
    setForegroundMode(ForegroundMode.QUEUED, getString(R.string.submit_saving));
    updateTranscriptPanel();
    attempt.result.whenComplete((saved, failure) -> mainHandler.post(() -> {
      if (isDestroyed() || isFinishing() || observedSubmissionWrite != attempt) return;
      observedSubmissionWrite = null;
      if (!activityVisible || VoiceSubmissionPersistence.SHARED.current() != attempt) return;
      if (failure == null && Boolean.TRUE.equals(saved)) {
        submitDraft();
      } else {
        typedSendPending = true;
        setForegroundMode(ForegroundMode.REVIEW, getString(R.string.submit_storage_failed));
        appendDiagnostic("Outgoing message persistence failed; retaining exact text for retry");
        updateTranscriptPanel();
      }
    }));
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

  private void showSavedRecordings() {
    File directory=new File(getFilesDir(),"voice_recordings");
    File[] files=directory.listFiles((dir,name)->name.endsWith(".pcm"));
    if(files==null || files.length==0) {
      new MaterialAlertDialogBuilder(this).setTitle(R.string.saved_recordings)
          .setMessage(R.string.no_saved_recordings).setPositiveButton(android.R.string.ok,null).show();return;
    }
    java.util.Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
    String[] labels=new String[files.length];
    for(int i=0;i<files.length;i++)labels[i]=android.text.format.DateFormat.format("MMM d, HH:mm",files[i].lastModified())
        +" · "+VoicePlayerMath.formatMs((int)(files[i].length()*1000/(appConfig.sampleRate*2L)));
    new MaterialAlertDialogBuilder(this).setTitle(R.string.saved_recordings).setItems(labels,(dialog,index)-> {
      File selected=files[index];
      new MaterialAlertDialogBuilder(this).setTitle(labels[index]).setMessage(R.string.saved_recording_actions)
          .setPositiveButton(R.string.open_recording,(d,w)->openSavedRecording(selected))
          .setNegativeButton(android.R.string.cancel,null)
          .setNeutralButton(R.string.delete_recording,(d,w)-> {
            if(!selected.delete())setForegroundMode(ForegroundMode.REVIEW,getString(R.string.saved_recording_unavailable));
            else if(selected.getName().equals(historyPrefs.getString(MANUAL_ARCHIVE_PREF_KEY,"")))
              clearManualRecording();
          }).show();
    }).show();
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
      android.system.Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());
    } catch (Exception failure) {
      appendDiagnostic("Recording save failed: " + failure.getClass().getSimpleName());
      temp.delete();
    }
  }

  private void restoreManualRecording() {
    String selected = historyPrefs.getString(MANUAL_ARCHIVE_PREF_KEY, "");
    File recording = selected != null && selected.endsWith(".pcm")
        && selected.equals(new File(selected).getName())
        ? new File(new File(getFilesDir(), "voice_recordings"), selected) : manualRecordingFile();
    restoreManualRecording(recording);
  }

  void restoreManualRecording(File file) {
    recoverManualRecording(file, false);
  }

  void openSavedRecording(File file) {
    recoverManualRecording(file, true);
  }

  private void recoverManualRecording(File file, boolean selectedArchive) {
    final int generation = ++recordingRecoveryGeneration;
    if (selectedArchive) {
      // Selection changes ownership immediately, before the asynchronous read.
      // An old ACK must not clear media and invalidate this pending selection.
      manualTranscriptPendingSubmission = false;
      manualTranscribePending = false;
    }
    final int maxBytes = appConfig.manualRecordingMaxBytes;
    new Thread(() -> {
      try {
        if (selectedArchive && !new File(getFilesDir(), "voice_recordings").getCanonicalFile()
            .equals(file.getCanonicalFile().getParentFile()))
          throw new IOException("Recording is outside the saved archive");
        long length = file.length();
        if (!file.isFile() || length <= 0) {
          if (selectedArchive) throw new IOException("Saved recording is unavailable");
          return;
        }
        if (length > maxBytes || (length & 1L) != 0)
          throw new IOException("Saved recording is outside the PCM playback limits");
        byte[] data = new byte[(int) length];
        try (java.io.DataInputStream in = new java.io.DataInputStream(new FileInputStream(file))) {
          in.readFully(data);
          if (in.read() != -1) throw new IOException("Recording changed during recovery");
        }
        mainHandler.post(() -> {
          if (isDestroyed() || isFinishing() || generation != recordingRecoveryGeneration
              || running.get()) return;
          VoiceForegroundService service = voiceService;
          if (service != null && (service.isCapturing() || (!selectedArchive
              && (service.hasFinishedManualCapture() || service.userPlayback().hasAudio())))) return;
          if (service != null && !service.retainRecoveredManualRecording(data)) return;
          if (selectedArchive) {
            // The archive already durably owns these bytes. Keep its identity
            // instead of copying/fsyncing it on the UI thread or overwriting a
            // different unsent recording just to make this selection recoverable.
            autoTranscribe = false;
            autoSend = false;
            historyPrefs.edit().putString(MANUAL_ARCHIVE_PREF_KEY, file.getName())
                .putBoolean(AUTO_TRANSCRIBE_PREF_KEY, false).apply();
          }
          synchronized (manualRecordingLock) {
            manualRecordingBuffer.reset();
            manualRecordingBuffer.write(data, 0, data.length);
          }
          manualRecordingAvailable = true;
          if (userPlayer != null) userPlayer.setStaticPcm(data);
          if (selectedArchive) {
            sendClientState();
            setForegroundMode(ForegroundMode.REVIEW, getString(R.string.recording_ready));
          }
          updateTranscriptPanel();
        });
      } catch (Exception failure) {
        mainHandler.post(() -> {
          if (!isDestroyed() && generation == recordingRecoveryGeneration) {
            appendDiagnostic("Recording restore failed: " + failure.getClass().getSimpleName());
            if (selectedArchive) setForegroundMode(ForegroundMode.REVIEW,
                getString(R.string.saved_recording_unavailable));
          }
        });
        // Keep the only recording intact after an incomplete or failed read.
      }
    }, "voice-recording-restore").start();
  }

  private void deleteManualRecordingFile() {
    File file = manualRecordingFile();
    if (file.exists() && !file.delete()) appendDiagnostic("Could not remove saved recording file.");
  }

  private void clearManualRecording() {
    recordingRecoveryGeneration++;
    String selected = historyPrefs.getString(MANUAL_ARCHIVE_PREF_KEY, "");
    historyPrefs.edit().remove(MANUAL_ARCHIVE_PREF_KEY).apply();
    synchronized (manualRecordingLock) { manualRecordingBuffer.reset(); }
    manualRecordingAvailable = false;
    manualTranscribePending = false;
    manualTranscriptPendingSubmission = false;
    if (selected == null || selected.isEmpty()) deleteManualRecordingFile();
    VoiceForegroundService service = voiceService;
    if (service != null) service.clearRecoveredManualRecording();
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
    serverReady = components.optBoolean("server", connectionTracker.isOpen())
        && components.optBoolean("storage",true);
    serverSttReady = components.optBoolean("stt", false)
        && components.optBoolean("stt_quality",true);
    serverAgentReady = components.optBoolean("agent", false);
    serverTtsReady = components.optBoolean("tts", false);
    serverTtsPrimaryReady = components.has("tts_primary")
        ? components.optBoolean("tts_primary", false)
        : serverTtsReady;
    serverTtsFallbackReady = components.has("tts_fallback")
        && components.optBoolean("tts_fallback", false);
    updateServiceHealth();
  }

  private void clearComponentHealth() {
    serverReady = false;
    serverSttReady = false;
    serverAgentReady = false;
    serverTtsReady = false;
    serverTtsPrimaryReady = false;
    serverTtsFallbackReady = false;
  }

  private String healthWord(boolean ready) {
    return getString(ready ? R.string.health_ready_word : R.string.health_unavailable_word);
  }

  private void updateServiceHealth() {
    if (componentHealthView == null) return;
    boolean microphoneAvailable = hasRequiredAudioPermissions();
    boolean connected = connectionTracker.isOpen() && serverHelloReady;
    final String value;
    final boolean visible;
    if (!microphoneAvailable) {
      // The primary status already presents the microphone failure and the typed
      // fallback. Avoid duplicating the same conspicuous error in a second large
      // strip, which can crowd conversation out at large font scales.
      value = "";
      visible = false;
    } else if (!connected) {
      value = connectionWanted.get() && hasUsableNetwork()
          ? getString(R.string.health_strip_connecting)
          : getString(R.string.health_strip_text_only);
      visible = true;
    } else if (!serverReady) {
      value = getString(R.string.health_strip_server_unavailable);
      visible = true;
    } else if (!serverSttReady) {
      value = getString(R.string.health_strip_stt_unavailable);
      visible = true;
    } else if (!serverAgentReady) {
      value = getString(R.string.health_strip_agent_unavailable);
      visible = true;
    } else if (!serverTtsReady) {
      value = getString(R.string.health_strip_tts_unavailable);
      visible = true;
    } else if(autoTranscribe && autoSend && !serverCanCommit) {
      value=getString(R.string.automatic_send_server_update);visible=true;
    } else if (VoiceOverviewPolicy.showTtsFallback(
        serverTtsReady, serverTtsPrimaryReady, serverTtsFallbackReady)) {
      value = getString(R.string.health_strip_tts_fallback);
      visible = true;
    } else if (running.get()) {
      value = getString(
          R.string.health_strip_mic,
          currentMicrophoneLabel.isEmpty() ? getString(R.string.microphone_unknown) : currentMicrophoneLabel,
          currentInputDbfs);
      visible = true;
    } else {
      value = "";
      visible = false;
    }
    runOnUiThread(() -> {
      componentHealthView.setText(value);
      componentHealthView.setVisibility(visible ? View.VISIBLE : View.GONE);
    });
    if (!running.get()) {
      if (foregroundMode == ForegroundMode.READY) {
        if (!microphoneAvailable) {
          setForegroundMode(ForegroundMode.PERMISSION, null);
        } else if (!connected) {
          setForegroundMode(
              connectionWanted.get() && hasUsableNetwork() ? ForegroundMode.CONNECTING : ForegroundMode.TEXT_ONLY,
              null);
        } else if (!serverReady || !serverAgentReady) {
          setForegroundMode(ForegroundMode.UNAVAILABLE, null);
        } else if (!serverSttReady || !serverTtsReady) {
          setForegroundMode(ForegroundMode.TEXT_ONLY, null);
        }
      } else if ((foregroundMode == ForegroundMode.TEXT_ONLY || foregroundMode == ForegroundMode.UNAVAILABLE)
          && connected && serverReady && serverAgentReady) {
        setForegroundMode(
            serverSttReady && serverTtsReady ? ForegroundMode.READY : ForegroundMode.TEXT_ONLY,
            null);
      }
    }
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
      setStatusProgressVisible(true);
      setPrimaryStatus(getString(R.string.loading_replay), "");
    } catch (Exception failure) {
      setStatusProgressVisible(false);
      appendDiagnostic("Replay request failed: " + failure.getClass().getSimpleName());
    }
  }

  private boolean automaticSpeechToAgent() {
    return autoTranscribe && autoSend && !manualTranscribePending;
  }

  private void showSpeechPartial(String heard) {
    // The current recognized utterance belongs in the editable Current
    // message field, regardless of automatic/manual sending mode. It is NOT
    // chat history until the server acknowledges a submitted turn.
    // ASR updates do not claim human ownership or persist an unsent draft.
    if (!userClaimedAsrPartial) setAsrDraftText(heard, true);
  }

  private void showSpeechFinal(String heard, String turnId) {
    // Keep the final visible in Current message until it is actually sent.
    // Existing user typing/corrections take precedence over ASR callbacks.
    if (!userClaimedAsrPartial) {
      setAsrDraftText(heard, false);
    } else {
      draftContainsUnstableAsrPartial = false;
    }
  }

  private void setAsrDraftText(String text, boolean partial) {
    String value = text == null ? "" : text;
    runOnUiThread(() -> {
      if (userClaimedAsrPartial) return;
      applyingAsrDraftText = true;
      try {
        draftContainsUnstableAsrPartial = partial;
        if (!partial) userClaimedAsrPartial = false;
        draftEdit.setText(value);
        draftEdit.setSelection(draftEdit.length());
        draftEdit.setEnabled(true);
      } finally {
        applyingAsrDraftText = false;
      }
      updateTranscriptPanel();
    });
  }

  private void discardRejectedAsrPartial() {
    runOnUiThread(() -> {
      if (!draftContainsUnstableAsrPartial || userClaimedAsrPartial) return;
      draftContainsUnstableAsrPartial = false;
      applyingAsrDraftText = true;
      try {
        draftEdit.setText("");
      } finally {
        applyingAsrDraftText = false;
      }
      updateTranscriptPanel();
    });
  }

  private void clearSubmittedDraft(String acknowledgedText, boolean manualAcknowledged) {
    runOnUiThread(() -> {
      if (userClaimedAsrPartial && !manualAcknowledged) return;
      if (acknowledgedText == null
          || !acknowledgedText.equals(draftEdit.getText().toString())) return;
      draftContainsUnstableAsrPartial = false;
      userClaimedAsrPartial = false;
      applyingAsrDraftText = true;
      try {
        draftEdit.setText("");
      } finally {
        applyingAsrDraftText = false;
      }
      if (manualAcknowledged) {
        historyPrefs.edit().remove(USER_DRAFT_PREF_KEY).putBoolean("draft_claimed_v1",false).apply();
      }
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
      sendDraftButton.setEnabled(VoiceDraftPolicy.canSubmit(text));
      boolean showTranscribe = !autoTranscribe && manualRecordingAvailable && !running.get();
      transcribeButton.setVisibility(showTranscribe ? View.VISIBLE : View.GONE);
      transcribeButton.setEnabled(showTranscribe && !manualTranscribePending);
    });
  }

  private void updateReplayRow() {
    if (replayRow == null || playUserButton == null || playAnswerButton == null) return;
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
    boolean liveAssistant = !user && "audio".equals(type);
    if(!voiceServiceBound)player.startStream(true, liveAssistant, liveAssistant);
    if (liveAssistant) setForegroundMode(ForegroundMode.BUFFERING, null);
    else {
      setStatusProgressVisible(false);
      setPrimaryStatus(getString(R.string.loading_replay), "");
    }
  }

  private void handleWsAudio(byte[] pcm) {
    VoicePcmPlayerView player = activePcmPlayer;
    if (player == null || pcm == null || pcm.length == 0) return;
    if (voiceServiceBound) {
      if(player==assistantPlayer)setForegroundMode(ForegroundMode.SPEAKING,null);
      return;
    }
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
    if (player != null && !voiceServiceBound) player.endStream();
    activePcmPlayer = null;
    // Rendering can outlast network transmission; the service owns completion.
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
    VoiceCaptureReadPolicy health = new VoiceCaptureReadPolicy(MIC_NO_DATA_GRACE_MS);
    while (running.get() && filled < wanted) {
      int read = recorder.read(target, filled, wanted - filled, AudioRecord.READ_BLOCKING);
      health.observe(read, SystemClock.elapsedRealtime());
      if (read > 0) filled += read;
      else if (read == 0) SystemClock.sleep(20L);
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
    // Bluetooth permission only enables Bluetooth inputs. The device microphone
    // remains usable when Nearby devices permission is declined.
    return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
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
          if (activityVisible && connectionWanted.get()
              && !connectionTracker.isOpen() && !wsConnecting.get()) {
            VoiceForegroundService service=voiceService;
            if (service!=null) service.requestReconnect("network_available");
            else bindVoiceService();
          }
        });
      }

      @Override public void onLost(Network network) {
        mainHandler.post(() -> {
          if (activityVisible && connectionWanted.get() && !hasUsableNetwork()) {
            setForegroundMode(ForegroundMode.RECONNECTING, getString(R.string.detail_unavailable));
            // The service observes its own WebSocket and retries. Do not
            // destroy an Activity generation or start a second connection.
            VoiceForegroundService service=voiceService;
            if (service!=null) service.requestReconnect("network_lost");
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
    if (mode == ForegroundMode.READY) {
      if (!hasRequiredAudioPermissions()) {
        mode = ForegroundMode.PERMISSION;
      } else if (!connectionTracker.isOpen() || !serverHelloReady) {
        mode = connectionWanted.get() && hasUsableNetwork()
            ? ForegroundMode.CONNECTING
            : ForegroundMode.TEXT_ONLY;
      } else if (!serverReady || !serverAgentReady) {
        mode = ForegroundMode.UNAVAILABLE;
      } else if (!serverSttReady || !serverTtsReady) {
        mode = ForegroundMode.TEXT_ONLY;
      }
      detailOverride = null;
    }
    foregroundMode = mode;
    int titleRes;
    int detailRes;
    switch (mode) {
      case TEXT_ONLY:
        titleRes = R.string.status_text_only; detailRes = R.string.detail_text_only; diagnosticConnectionState = "text only"; break;
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
      case QUEUED:
        titleRes = R.string.status_answer_queued; detailRes = R.string.detail_answer_queued; break;
      case ANSWER_ERROR:
        titleRes = R.string.status_answer_failed; detailRes = R.string.detail_answer_failed; break;
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
    setStatusProgressVisible(VoiceOverviewPolicy.showForegroundProgress(mode.name()));
    setPrimaryStatus(title, detail);
  }

  private void setStatusProgressVisible(boolean visible) {
    if (statusProgressView == null) return;
    runOnUiThread(() -> statusProgressView.setVisibility(visible ? View.VISIBLE : View.GONE));
  }

  private void showListeningState() {
    if(assistantPlayer!=null && assistantPlayer.isPlaying()) {
      setForegroundMode(ForegroundMode.SPEAKING,null);
    } else if (!running.get()) {
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
      String clean = detail == null ? "" : detail.trim();
      boolean actionableDetail = foregroundMode == ForegroundMode.TEXT_ONLY
          || foregroundMode == ForegroundMode.CONNECTING
          || foregroundMode == ForegroundMode.VERIFYING
          || foregroundMode == ForegroundMode.RECONNECTING
          || foregroundMode == ForegroundMode.UNAVAILABLE
          || foregroundMode == ForegroundMode.PERMISSION
          || foregroundMode == ForegroundMode.MICROPHONE_ERROR
          || foregroundMode == ForegroundMode.REVIEW
          || foregroundMode == ForegroundMode.ANSWER_ERROR
          || foregroundMode == ForegroundMode.QUEUED;
      statusView.setText(title);
      statusDetailView.setText(actionableDetail ? clean : "");
      statusDetailView.setVisibility(actionableDetail && !clean.isEmpty() ? View.VISIBLE : View.GONE);
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
        String user = turn.optString("user", "");
        String assistant = turn.optString("assistant", "");
        String status = turn.optString("status", "");
        if ("draft".equals(status)) {
          draftTurn = turnId;
          draftText = turn.optString("recognized_text", user);
          continue;
        }
        if (!user.trim().isEmpty()) {
          rendered.append("You\n").append(user).append("\n\n");
          lastUser = turnId;
        }
        if (!assistant.trim().isEmpty()) {
          rendered.append("Agent\n").append(assistant).append("\n\n");
          lastAssistant = turnId;
        } else if ("answer_error".equals(status) || turn.optBoolean("answer_failed", false)) {
          rendered.append("System\n")
              .append(getString(R.string.conversation_answer_failed))
              .append("\n\n");
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
      pendingTurnId = automaticSpeechToAgent() ? "" : finalDraftTurn;
      replayUserAvailable = finalUserAudio;
      replayAssistantAvailable = finalAssistantAudio;
      if (!finalDraftTurn.isEmpty() && !automaticSpeechToAgent()) {
        if (!userClaimedAsrPartial) {
          setAsrDraftText(finalDraftText, false);
        }
        // Drafts belong only in the composer until the server acknowledges Send.
      }
      // The transport's past ASR drafts are not owned user text.
      // Never put stale recognized speech back into the composer on reconnect.
      String localDraft = historyPrefs.getString(USER_DRAFT_PREF_KEY, "");
      if (localDraft != null && !localDraft.isEmpty()
          && !localDraft.equals(draftEdit.getText().toString())) {
        draftEdit.setText(localDraft);
        draftEdit.setSelection(draftEdit.length());
      }
      persistAndRenderConversation();
      updateTranscriptPanel();
      updateReplayRow();
    });
  }

  private void renderConversationText() {
    if (conversationView == null || conversationScrollView == null) return;
    runOnUiThread(() -> {
      boolean followLatest = !conversationScrollInitialized || conversationPinnedToLatest;
      String rendered = conversationTextModel.render(getString(R.string.empty_conversation));
      if (rendered.length() > appConfig.historyCacheMaxChars) {
        rendered = rendered.substring(rendered.length() - appConfig.historyCacheMaxChars);
      }
      conversationView.setText(rendered);
      if (followLatest) scrollConversationToLatest();
      else conversationScrollView.post(this::updateLatestButtonVisibility);
    });
  }

  private boolean isConversationAtLatest() {
    if (conversationScrollView == null || conversationScrollView.getChildCount() == 0) return true;
    View child = conversationScrollView.getChildAt(0);
    int remaining = child.getBottom() - (conversationScrollView.getScrollY() + conversationScrollView.getHeight());
    return remaining <= dp(48);
  }

  private void updateLatestButtonVisibility() {
    if (latestButton == null || conversationScrollView == null) return;
    boolean scrollable = conversationScrollView.canScrollVertically(-1)
        || conversationScrollView.canScrollVertically(1);
    latestButton.setVisibility(scrollable ? View.VISIBLE : View.GONE);
  }

  private void scrollConversationToLatest() {
    if (conversationScrollView == null || conversationView == null) return;
    conversationProgrammaticScroll = true;
    conversationPinnedToLatest = true;
    ViewTreeObserver observer = conversationScrollView.getViewTreeObserver();
    final ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
    holder[0] = () -> {
      ViewTreeObserver current = conversationScrollView.getViewTreeObserver();
      if (current.isAlive()) current.removeOnPreDrawListener(holder[0]);
      forceConversationBottom();
      conversationScrollView.post(() -> {
        forceConversationBottom();
        conversationScrollInitialized = true;
        conversationProgrammaticScroll = false;
        conversationPinnedToLatest = isConversationAtLatest();
        updateLatestButtonVisibility();
      });
      return true;
    };
    observer.addOnPreDrawListener(holder[0]);
    conversationScrollView.requestLayout();
    conversationScrollView.invalidate();
  }

  private void forceConversationBottom() {
    if (conversationScrollView == null || conversationScrollView.getChildCount() == 0) return;
    View child = conversationScrollView.getChildAt(0);
    int viewport = conversationScrollView.getHeight()
        - conversationScrollView.getPaddingTop() - conversationScrollView.getPaddingBottom();
    int target = Math.max(0, child.getHeight() - Math.max(0, viewport));
    conversationScrollView.scrollTo(0, target);
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

  @Override protected void onSaveInstanceState(Bundle state) {
    Bundle composer = new Bundle();
    composer.putString("text", draftEdit.getText().toString());
    composer.putInt("selection_start", draftEdit.getSelectionStart());
    composer.putInt("selection_end", draftEdit.getSelectionEnd());
    composer.putBoolean("human", userClaimedAsrPartial);
    composer.putBoolean("partial", draftContainsUnstableAsrPartial);
    composer.putString("turn", pendingTurnId);
    composer.putString("final_turn", lastFinalAsrTurnId);
    composer.putString("final_text", lastFinalAsrText);
    composer.putBoolean("manual_transcribing", manualTranscribePending);
    composer.putBoolean("manual_submission", manualTranscriptPendingSubmission);
    state.putBundle("voice_composer_v1", composer);
    super.onSaveInstanceState(state);
  }

  private void restoreComposerState(Bundle state) {
    Bundle composer = state == null ? null : state.getBundle("voice_composer_v1");
    if (composer == null) return;
    applyingAsrDraftText = true;
    try {
      userClaimedAsrPartial = composer.getBoolean("human");
      draftContainsUnstableAsrPartial = composer.getBoolean("partial");
      pendingTurnId = composer.getString("turn", "");
      lastFinalAsrTurnId = composer.getString("final_turn", "");
      lastFinalAsrText = composer.getString("final_text", "");
      manualTranscribePending = composer.getBoolean("manual_transcribing");
      manualTranscriptPendingSubmission = composer.getBoolean("manual_submission");
      draftEdit.setText(composer.getString("text", ""));
      int length = draftEdit.length();
      int start = Math.max(0, Math.min(length, composer.getInt("selection_start", length)));
      int end = Math.max(0, Math.min(length, composer.getInt("selection_end", length)));
      draftEdit.setSelection(start, end);
      historyPrefs.edit().putBoolean("draft_claimed_v1", userClaimedAsrPartial).apply();
    } finally {
      applyingAsrDraftText = false;
    }
    updateTranscriptPanel();
  }

  @Override protected void onStart() {
    super.onStart();
    activityVisible=true;
    connectionWanted.set(true);
    bindVoiceService();
    if (voiceServiceBound && !connectionTracker.isOpen()) connectWebSocket();
  }

  @Override protected void onStop() {
    // The working Voice Button pattern: detach only the UI. Foreground audio
    // capture, queued network frames and server replies belong to the Service.
    VoiceForegroundService service=voiceService;
    VoiceWebSocketListener listener=boundVoiceListener;
    if (service!=null && listener!=null) service.detach(listener);
    boundVoiceListener=null;
    connectionTracker.onClosed(connectionTracker.currentGeneration());
    wsConnecting.set(false);
    if (reconnectRunnable != null) mainHandler.removeCallbacks(reconnectRunnable);
    reconnectScheduledGeneration = -1L;
    webSocket=null;
    serverHelloReady=false;
    activityVisible=false;
    if (voiceServiceBound || voiceServiceBindingRequested) {
      try { unbindService(voiceServiceConnection); }
      catch(IllegalArgumentException ignored) {}
      voiceServiceBound=false;
      voiceService=null;
      voiceServiceBindingRequested=false;
    }
    super.onStop();
  }

  @Override protected void onDestroy() {
    recordingRecoveryGeneration++;
    // Never stop a service-owned live voice session as an Activity destructor
    // side effect. Manual local recording remains the legacy Activity mode.
    if (mainHandler != null) mainHandler.removeCallbacksAndMessages(null);
    // Service-owned playback continues across Activity destruction.
    if (connectivityManager != null && networkCallback != null) {
      try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
    }
    releaseAudioEffects();
    try { if (wsClient != null) wsClient.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
    super.onDestroy();
  }
}
