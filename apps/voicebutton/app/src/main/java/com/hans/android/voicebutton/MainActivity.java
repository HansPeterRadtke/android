package com.hans.android.voicebutton;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.common_ui.AndroidUi;
import com.hans.android.network.reliable.ReliableUploadClient;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1001;
    private static final int DEBUG_EXPORT_REQUEST = 1002;
    private static final String PENDING_DIAGNOSTICS_EXPORT =
            "voicebutton-diagnostics-pending.txt";
    private static final String GUI_PREFS = "voicebutton_gui_state";
    private static final String PREF_FOLDER_ID = "main_folder_id";
    private static final String PREF_FOLDER_NAME = "main_folder_name";
    private static final String PREF_DEVICE_ID = "main_device_id";

    private final List<AudioInputOption> inputs = new ArrayList<>();
    private final List<ReliableSessionStore.Folder> folders = new ArrayList<>();
    private boolean inputManuallySelectedThisRun;
    private boolean audioDeviceCallbackRegistered;
    private final AudioDeviceCallback audioDeviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            boolean bluetoothAdded = containsBluetoothInput(addedDevices);
            if (bluetoothAdded) inputManuallySelectedThisRun = false;
            diag(PhoneDiagnostics.INFO, "microphone.device_added", snapshot.currentSessionId,
                    "Android reported an audio device connection",
                    PhoneDiagnostics.fields("bluetooth_added", bluetoothAdded,
                            "device_count", addedDevices == null ? 0 : addedDevices.length));
            refreshInputs();
        }

        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            boolean selectedRemoved = containsDeviceId(removedDevices, selectedDeviceId);
            boolean bluetoothRemoved = containsBluetoothInput(removedDevices);
            if (selectedRemoved || bluetoothRemoved) inputManuallySelectedThisRun = false;
            diag(PhoneDiagnostics.INFO, "microphone.device_removed", snapshot.currentSessionId,
                    "Android reported an audio device disconnection",
                    PhoneDiagnostics.fields("selected_removed", selectedRemoved,
                            "bluetooth_removed", bluetoothRemoved,
                            "device_count", removedDevices == null ? 0 : removedDevices.length));
            refreshInputs();
        }
    };
    private LinearLayout statusCard;
    private LinearLayout setupContainer;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView serverHealthText;
    private TextView primaryDisabledReasonText;
    private ProgressBar progressBar;
    private TextView transferText;
    private TextView uploadCurrentText;
    private ProgressBar uploadCurrentProgressBar;
    private TextView transcriptionSummaryText;
    private TextView transcriptionCurrentText;
    private ProgressBar transcriptionProgressBar;
    private ProgressBar transcriptionCurrentProgressBar;
    private TextView currentText;
    private TextView routedText;
    private TextView durationText;
    private TextView micLevelText;
    private ProgressBar micLevelBar;
    private Button primaryButton;
    private Button secondaryButton;
    private Button folderButton;
    private Button inputButton;
    private Button moreButton;
    private boolean compactHeight;

    private int selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
    private String selectedFolderId = "default";
    private String selectedFolderName = "Default";
    private boolean updatingFolders;
    private boolean inputsLoaded;
    private boolean foldersRefreshedAfterReady;
    private RecordingService service;
    private boolean bound;
    private RecordingService.Snapshot snapshot = RecordingService.Snapshot.initial();
    private PhoneDiagnostics diagnostics;
    private SharedPreferences guiPreferences;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService uiWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService inputWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voicebutton-input-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService transcriptionStatusWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "voicebutton-transcription-status");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean inputRefreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean folderRefreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean transcriptionStatusRunning = new AtomicBoolean(false);
    private boolean transcriptionStatusPolling;
    private final Runnable transcriptionStatusPoll = new Runnable() {
        @Override public void run() {
            if (!transcriptionStatusPolling) return;
            refreshTranscriptionStatusOnce();
            uiHandler.postDelayed(this, 5000L);
        }
    };
    private long lastTranscriptionStatusSuccessElapsedMs = -1L;
    private int lastReportedTranscriptionCompleteCount = -1;
    private int lastReportedTranscriptionPendingCount = -1;
    private ReliableUploadClient.TranscriptionStatus lastTranscriptionStatus;
    private ReliableUploadClient transcriptionClient;
    private volatile RecordingService.Snapshot pendingSnapshot;
    private boolean renderScheduled;
    private String lastStructureKey = "";

    private final Runnable renderPending = () -> {
        renderScheduled = false;
        RecordingService.Snapshot value = pendingSnapshot;
        if (value != null) render(value);
    };

    private final RecordingService.StatusListener statusListener = value -> {
        pendingSnapshot = value;
        uiHandler.post(() -> {
            if (!foldersRefreshedAfterReady
                    && value != null
                    && !"STARTING".equals(value.state)) {
                foldersRefreshedAfterReady = true;
                refreshFolders();
            }
            if (renderScheduled) return;
            renderScheduled = true;
            uiHandler.postDelayed(renderPending, 250L);
        });
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecordingService.LocalBinder) binder).getService();
            bound = true;
            diag(PhoneDiagnostics.INFO, "ui.main.service_connected", null,
                    "Main screen connected to RecordingService", PhoneDiagnostics.fields());
            service.addStatusListener(statusListener);
            refreshFolders();
            render(service.getSnapshot());
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.removeStatusListener(statusListener);
            service = null;
            bound = false;
            diag(PhoneDiagnostics.WARN, "ui.main.service_disconnected", null,
                    "Main screen disconnected from RecordingService", PhoneDiagnostics.fields());
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        guiPreferences = getSharedPreferences(GUI_PREFS, Context.MODE_PRIVATE);
        selectedFolderId = guiPreferences.getString(PREF_FOLDER_ID, "default");
        selectedFolderName = guiPreferences.getString(PREF_FOLDER_NAME, "Default");
        selectedDeviceId = guiPreferences.getInt(PREF_DEVICE_ID,
                AudioInputOption.DEFAULT_DEVICE_ID);
        diagnostics = PhoneDiagnostics.get();
        if (diagnostics == null) {
            PhoneDiagnostics.initializeAsync(this, BuildConfig.VOICE_BASE_URL,
                    BuildConfig.VERSION_NAME);
        }
        transcriptionClient = new ReliableUploadClient(
                BuildConfig.VOICE_BASE_URL,
                "VoiceButton/" + BuildConfig.VERSION_NAME + " Android");
        diag(PhoneDiagnostics.INFO, "ui.main.create", null,
                "MainActivity onCreate", PhoneDiagnostics.fields("has_saved_state", savedInstanceState != null));
        buildScreen();
        requestPermissionsIfNeeded();
    }

    @Override protected void onStart() {
        super.onStart();
        diag(PhoneDiagnostics.INFO, "ui.main.start", null,
                "MainActivity onStart", PhoneDiagnostics.fields());
        registerAudioDeviceCallback();
        if (!inputsLoaded) refreshInputs();
        refreshFolders();
        bindService(new Intent(this, RecordingService.class), connection,
                Context.BIND_AUTO_CREATE);
        transcriptionStatusPolling = true;
        uiHandler.removeCallbacks(transcriptionStatusPoll);
        uiHandler.post(transcriptionStatusPoll);
    }

    @Override protected void onStop() {
        transcriptionStatusPolling = false;
        uiHandler.removeCallbacks(transcriptionStatusPoll);
        unregisterAudioDeviceCallback();
        diag(PhoneDiagnostics.INFO, "ui.main.stop", snapshot.currentSessionId,
                "MainActivity onStop; service work continues independently",
                PhoneDiagnostics.fields("state", snapshot.state,
                        "recording", snapshot.recording,
                        "paused", snapshot.paused));
        if (bound) {
            if (service != null) service.removeStatusListener(statusListener);
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        uiWorker.shutdownNow();
        inputWorker.shutdownNow();
        transcriptionStatusWorker.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        compactHeight = getResources().getConfiguration().screenHeightDp < 520;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AndroidUi.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUi.dp(this, 10), AndroidUi.dp(this, compactHeight ? 2 : 8),
                AndroidUi.dp(this, 8), AndroidUi.dp(this, compactHeight ? 2 : 6));

        secondaryButton = materialDangerButton("Finish");
        secondaryButton.setId(R.id.voicebutton_finish);
        secondaryButton.setMinHeight(AndroidUi.dp(this, 48));
        secondaryButton.setContentDescription("Finish recording; confirmation required");
        secondaryButton.setVisibility(View.GONE);
        header.addView(secondaryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = AndroidUi.title(this, "Voice Button");
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setVisibility(compactHeight ? View.GONE : View.VISIBLE);
        AndroidUi.readableLine(this, title, 44, 2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMargins(AndroidUi.dp(this, 8), 0, AndroidUi.dp(this, 8), 0);
        header.addView(title, titleParams);

        moreButton = materialToolbarButton("More");
        moreButton.setId(R.id.voicebutton_more);
        moreButton.setMinWidth(AndroidUi.dp(this, 64));
        moreButton.setContentDescription("More options and diagnostics");
        moreButton.setOnClickListener(v -> showMoreMenu());
        header.addView(moreButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        transferText = AndroidUi.text(this, "", 14, false, AndroidUi.MUTED);
        transferText.setId(R.id.voicebutton_backup_text);
        AndroidUi.readableLine(this, transferText, 0, compactHeight ? 1 : 3);
        transferText.setTextSize(compactHeight ? 12 : 14);
        transferText.setGravity(Gravity.CENTER);
        transferText.setVisibility(View.GONE);
        LinearLayout.LayoutParams backupParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        backupParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 4));
        root.addView(transferText, backupParams);

        serverHealthText = AndroidUi.small(this, "");
        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setId(R.id.voicebutton_backup_progress);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        progressBar.setContentDescription("Backup progress");
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams uploadProgressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 10));
        uploadProgressParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 8));
        root.addView(progressBar, uploadProgressParams);
        uploadCurrentText = AndroidUi.small(this, "Upload current file: none");
        uploadCurrentText.setId(R.id.voicebutton_upload_current);
        AndroidUi.readableLine(this, uploadCurrentText, 0, 2);
        uploadCurrentText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams uploadCurrentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uploadCurrentParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 8));
        root.addView(uploadCurrentText, uploadCurrentParams);

        uploadCurrentProgressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        uploadCurrentProgressBar.setId(R.id.voicebutton_upload_current_progress);
        uploadCurrentProgressBar.setMax(1000);
        uploadCurrentProgressBar.setProgress(0);
        uploadCurrentProgressBar.setVisibility(View.GONE);
        uploadCurrentProgressBar.setContentDescription("Current upload file progress");
        LinearLayout.LayoutParams uploadCurrentProgressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 10));
        uploadCurrentProgressParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 8));
        root.addView(uploadCurrentProgressBar, uploadCurrentProgressParams);

        transcriptionSummaryText = AndroidUi.text(this,
                "Transcription overall: checking…", 14, false, AndroidUi.INK);
        transcriptionSummaryText.setId(R.id.voicebutton_transcription_summary);
        AndroidUi.readableLine(this, transcriptionSummaryText, 0, 2);
        transcriptionSummaryText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams transcriptionSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        transcriptionSummaryParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 4));
        root.addView(transcriptionSummaryText, transcriptionSummaryParams);

        transcriptionProgressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        transcriptionProgressBar.setId(R.id.voicebutton_transcription_progress);
        transcriptionProgressBar.setMax(1000);
        transcriptionProgressBar.setIndeterminate(true);
        transcriptionProgressBar.setContentDescription("Transcription overall progress");
        LinearLayout.LayoutParams transcriptionProgressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 10));
        transcriptionProgressParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 4));
        root.addView(transcriptionProgressBar, transcriptionProgressParams);

        transcriptionCurrentText = AndroidUi.small(this,
                "Transcription current file: checking…");
        transcriptionCurrentText.setId(R.id.voicebutton_transcription_current);
        AndroidUi.readableLine(this, transcriptionCurrentText, 0, 2);
        transcriptionCurrentText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams transcriptionCurrentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        transcriptionCurrentParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 8));
        root.addView(transcriptionCurrentText, transcriptionCurrentParams);

        transcriptionCurrentProgressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        transcriptionCurrentProgressBar.setId(R.id.voicebutton_transcription_current_progress);
        transcriptionCurrentProgressBar.setMax(1000);
        transcriptionCurrentProgressBar.setProgress(0);
        transcriptionCurrentProgressBar.setVisibility(View.GONE);
        transcriptionCurrentProgressBar.setContentDescription("Current transcription file progress");
        LinearLayout.LayoutParams transcriptionCurrentProgressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 10));
        transcriptionCurrentProgressParams.setMargins(AndroidUi.dp(this, 18), 0,
                AndroidUi.dp(this, 18), AndroidUi.dp(this, 8));
        root.addView(transcriptionCurrentProgressBar, transcriptionCurrentProgressParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUi.dp(this, 22), AndroidUi.dp(this, 14),
                AndroidUi.dp(this, 22), AndroidUi.dp(this, 24));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setGravity(Gravity.CENTER_HORIZONTAL);
        statusCard.setPadding(0, AndroidUi.dp(this, compactHeight ? 1 : 8),
                0, AndroidUi.dp(this, compactHeight ? 1 : 8));

        statusTitle = AndroidUi.text(this, "Ready to record",
                compactHeight ? 22 : 32, true, AndroidUi.GREEN);
        statusTitle.setId(R.id.voicebutton_status_title);
        statusTitle.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, statusTitle, compactHeight ? 44 : 56,
                compactHeight ? 2 : 3);
        statusCard.addView(statusTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        durationText = AndroidUi.text(this, "00:00:00",
                compactHeight ? 34 : 54, true, AndroidUi.INK);
        durationText.setId(R.id.voicebutton_duration);
        durationText.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        durationText.setGravity(Gravity.CENTER);
        durationText.setMinHeight(AndroidUi.dp(this, compactHeight ? 54 : 88));
        durationText.setVisibility(View.GONE);
        statusCard.addView(durationText);

        statusDetail = AndroidUi.body(this, "");
        statusDetail.setTextSize(compactHeight ? 12 : 16);
        statusDetail.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, statusDetail, 0, compactHeight ? 1 : 4);
        statusDetail.setVisibility(View.GONE);
        statusCard.addView(statusDetail);

        currentText = AndroidUi.text(this, "", compactHeight ? 12 : 14, true, AndroidUi.GREEN);
        currentText.setId(R.id.voicebutton_local_protection);
        currentText.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, currentText, 0, compactHeight ? 1 : 3);
        currentText.setVisibility(View.GONE);
        statusCard.addView(currentText);

        routedText = AndroidUi.small(this, "");
        routedText.setId(R.id.voicebutton_routed_microphone);
        routedText.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, routedText, 0, 2);
        routedText.setVisibility(View.GONE);
        statusCard.addView(routedText);

        micLevelText = AndroidUi.small(this, "");
        micLevelText.setId(R.id.voicebutton_mic_level_text);
        micLevelText.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, micLevelText, 0, 2);
        micLevelText.setVisibility(View.GONE);
        statusCard.addView(micLevelText);

        micLevelBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        micLevelBar.setId(R.id.voicebutton_mic_level_bar);
        micLevelBar.setMax(1000);
        micLevelBar.setProgress(0);
        micLevelBar.setVisibility(View.GONE);
        micLevelBar.setContentDescription("Live microphone input level");
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 10));
        micParams.setMargins(0, AndroidUi.dp(this, 6), 0, 0);
        statusCard.addView(micLevelBar, micParams);
        root.addView(statusCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setupContainer = new LinearLayout(this);
        setupContainer.setId(R.id.voicebutton_setup);
        setupContainer.setOrientation(LinearLayout.VERTICAL);
        setupContainer.setPadding(0, AndroidUi.dp(this, 32), 0, 0);
        TextView setupLabel = AndroidUi.small(this, "Next recording");
        setupLabel.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, setupLabel, 28, 2);
        setupContainer.addView(setupLabel);

        folderButton = materialSecondaryButton("Folder: Default");
        folderButton.setSingleLine(false);
        folderButton.setMaxLines(2);
        folderButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        folderButton.setContentDescription("Choose recording folder");
        folderButton.setOnClickListener(v -> showFolderPicker());

        inputButton = materialSecondaryButton("Microphone: checking…");
        inputButton.setSingleLine(false);
        inputButton.setMaxLines(2);
        inputButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        inputButton.setContentDescription("Choose recording microphone");
        inputButton.setOnClickListener(v -> showInputPicker());

        setupContainer.addView(folderButton, flexibleButtonParams(4));
        setupContainer.addView(inputButton, flexibleButtonParams(4));
        content.addView(setupContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actionArea = new LinearLayout(this);
        actionArea.setOrientation(LinearLayout.VERTICAL);
        actionArea.setPadding(AndroidUi.dp(this, 12), AndroidUi.dp(this, compactHeight ? 2 : 8),
                AndroidUi.dp(this, 12), AndroidUi.dp(this, compactHeight ? 4 : 12));
        actionArea.setBackgroundColor(AndroidUi.SURFACE);
        actionArea.setElevation(AndroidUi.dp(this, 8));

        primaryDisabledReasonText = AndroidUi.small(this, "");
        primaryDisabledReasonText.setId(R.id.voicebutton_primary_disabled_reason);
        primaryDisabledReasonText.setGravity(Gravity.CENTER);
        AndroidUi.readableLine(this, primaryDisabledReasonText, 0, 3);
        primaryDisabledReasonText.setVisibility(View.GONE);
        actionArea.addView(primaryDisabledReasonText);

        primaryButton = materialPrimaryButton("Start recording");
        primaryButton.setId(R.id.voicebutton_primary);
        primaryButton.setMinHeight(AndroidUi.dp(this, compactHeight ? 52 : 68));
        primaryButton.setTextSize(19);
        primaryButton.setContentDescription("Start recording");
        primaryButton.setOnClickListener(v -> primaryAction());
        actionArea.addView(primaryButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actionArea);

        setContentView(root);
    }

    private LinearLayout.LayoutParams flexibleButtonParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, AndroidUi.dp(this, topMarginDp), 0, AndroidUi.dp(this, 4));
        return params;
    }

    private void refreshFolders() {
        if (!folderRefreshRunning.compareAndSet(false, true)) return;
        uiWorker.execute(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            List<ReliableSessionStore.Folder> loaded = new ArrayList<>();
            String failureDetail = "";
            try {
                ReliableSessionStore browser =
                        ReliableSessionStore.openForBrowsing(this);
                loaded.addAll(browser.listFolders());
            } catch (Exception failure) {
                failureDetail = PhoneDiagnostics.exactFailure(
                        "Reading local recording folders", failure);
            }
            if (loaded.isEmpty()) {
                loaded.add(new ReliableSessionStore.Folder(
                        "default", "Default", 0L));
            }
            final String exactFailure = failureDetail;
            final long duration = Math.max(0L,
                    android.os.SystemClock.elapsedRealtime() - started);
            runOnUiThread(() -> {
                folderRefreshRunning.set(false);
                folders.clear();
                folders.addAll(loaded);
                boolean selectedExists = false;
                for (ReliableSessionStore.Folder folder : folders) {
                    if (folder.id.equals(selectedFolderId)) {
                        selectedFolderName = folder.name;
                        selectedExists = true;
                        break;
                    }
                }
                if (!selectedExists && !folders.isEmpty()) {
                    selectedFolderId = folders.get(0).id;
                    selectedFolderName = folders.get(0).name;
                    saveMainSelectionState();
                }
                updateSetupButtons();
                org.json.JSONArray names = new org.json.JSONArray();
                for (ReliableSessionStore.Folder folder : folders) {
                    names.put(folder.id + ":" + folder.name);
                }
                diag(exactFailure.isEmpty() ? PhoneDiagnostics.INFO
                                : PhoneDiagnostics.ERROR,
                        "folder.local_refresh", snapshot.currentSessionId,
                        exactFailure.isEmpty()
                                ? "Local recording folders were refreshed"
                                : exactFailure,
                        PhoneDiagnostics.fields("folder_count", folders.size(),
                                "folders", names,
                                "duration_ms", duration));
            });
        });
    }

    private void showFolderPicker() {
        if (snapshot.recording || snapshot.openSession != null) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Folder locked for this recording")
                    .setMessage("Finish the current recording before changing its folder. A paused recording keeps its original folder.")
                    .setPositiveButton("Back", null).show();
            return;
        }
        ReliableSessionStore.Folder selected = null;
        for (ReliableSessionStore.Folder folder : folders) {
            if (folder.id.equals(selectedFolderId)) {
                selected = folder;
                break;
            }
        }
        final ReliableSessionStore.Folder selectedFolder = selected;
        String[] labels = new String[folders.size() + 2];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).path;
        }
        labels[folders.size()] = "Create root folder…";
        labels[folders.size() + 1] = selectedFolder == null
                ? "Create root folder…"
                : "Create subfolder in " + selectedFolder.path + "…";
        new MaterialAlertDialogBuilder(this).setTitle("Recording folder")
                .setItems(labels, (dialog, which) -> {
                    if (which < folders.size()) {
                        ReliableSessionStore.Folder folder = folders.get(which);
                        selectedFolderId = folder.id;
                        selectedFolderName = folder.name;
                        saveMainSelectionState();
                        updateSetupButtons();
                        diag(PhoneDiagnostics.INFO, "folder.selected",
                                snapshot.currentSessionId,
                                "A recording folder was selected",
                                PhoneDiagnostics.fields("folder_id", folder.id,
                                        "folder_name", folder.name,
                                        "folder_path", folder.path,
                                        "parent_folder_id", folder.parentId));
                    } else if (which == folders.size()) {
                        showCreateFolderDialog("", "Recordings");
                    } else {
                        showCreateFolderDialog(
                                selectedFolder == null ? "" : selectedFolder.id,
                                selectedFolder == null
                                        ? "Recordings" : selectedFolder.path);
                    }
                }).setNegativeButton("Back", null).show();
    }

    private void showCreateFolderDialog(String parentFolderId,
                                        String parentPath) {
        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setHint("Folder name");
        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setSingleLine(true);
        inputLayout.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = AndroidUi.dp(this, 20);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(pad, 0, pad, 0);
        container.addView(inputLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        String location = parentFolderId == null || parentFolderId.isEmpty()
                ? "Recordings" : parentPath;
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Create folder in " + location)
                .setView(container)
                .setNegativeButton("Back", null)
                .setPositiveButton("Create", null).create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty()) {
                                input.setError("Enter a folder name");
                                return;
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(false);
                            uiWorker.execute(() -> {
                                try {
                                    RecordingService value = service;
                                    ReliableSessionStore.Folder created;
                                    if (value != null) {
                                        created = value.createFolder(name,
                                                parentFolderId);
                                    } else {
                                        ReliableSessionStore browser =
                                                ReliableSessionStore.openForBrowsing(
                                                        this);
                                        created = browser.createFolder(name,
                                                parentFolderId);
                                    }
                                    runOnUiThread(() -> {
                                        selectedFolderId = created.id;
                                        selectedFolderName = created.name;
                                        saveMainSelectionState();
                                        dialog.dismiss();
                                        refreshFolders();
                                        updateSetupButtons();
                                    });
                                } catch (Exception failure) {
                                    runOnUiThread(() -> {
                                        dialog.getButton(
                                                AlertDialog.BUTTON_POSITIVE)
                                                .setEnabled(true);
                                        input.setError(
                                                PhoneDiagnostics.exactFailure(
                                                        "Creating folder",
                                                        failure));
                                    });
                                }
                            });
                        }));
        dialog.show();
    }

    private void refreshInputs() {
        inputsLoaded = true;
        if (!inputRefreshRunning.compareAndSet(false, true)) return;
        inputButton.setText("Microphone: checking…");
        inputButton.setEnabled(false);
        int preserve = selectedDeviceId;
        inputWorker.execute(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            List<AudioInputOption> loaded = AudioInputCatalog.list(this);
            org.json.JSONObject rawDiagnostics = AudioInputCatalog.diagnosticSnapshot(this);
            long duration = Math.max(0L, android.os.SystemClock.elapsedRealtime() - started);
            runOnUiThread(() -> applyInputs(loaded, preserve));
            diag(PhoneDiagnostics.INFO, "microphone.refresh_result", snapshot.currentSessionId,
                    "Currently available microphone list was refreshed",
                    PhoneDiagnostics.fields("available_count", loaded.size(),
                            "android_audio_diagnostics", rawDiagnostics,
                            "refresh_duration_ms", duration));
        });
    }

    private void applyInputs(List<AudioInputOption> loaded, int preserve) {
        inputRefreshRunning.set(false);
        AudioInputOption previous = null;
        for (AudioInputOption input : inputs) {
            if (input.getDeviceId() == preserve) {
                previous = input;
                break;
            }
        }
        inputs.clear(); inputs.addAll(loaded);
        if (inputs.isEmpty()) {
            selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
            saveMainSelectionState();
            inputButton.setText("Microphone: none available");
            inputButton.setEnabled(true);
            render(snapshot);
            return;
        }
        AudioInputOption resolved = inputManuallySelectedThisRun
                ? AudioInputCatalog.resolveFreshSelection(inputs, preserve, previous)
                : AudioInputCatalog.preferredAutomaticInput(inputs);
        selectedDeviceId = resolved == null
                ? AudioInputOption.DEFAULT_DEVICE_ID : resolved.getDeviceId();
        saveMainSelectionState();
        inputButton.setEnabled(true);
        updateSetupButtons();
        render(snapshot);
    }

    private void startWithFreshMicrophone() {
        final int previousId = selectedDeviceId;
        if (!inputRefreshRunning.compareAndSet(false, true)) {
            showPrimaryPending("Checking microphone…");
            waitForFreshMicrophoneThenStart(previousId);
            return;
        }
        inputsLoaded = true;
        showPrimaryPending("Checking microphone…");
        AudioInputOption previousSelection = findInputById(previousId);
        refreshInputForStart(previousId, previousSelection, 0);
    }

    private void refreshInputForStart(int previousId,
                                      AudioInputOption previousSelection,
                                      int attempt) {
        inputWorker.execute(() -> {
            List<AudioInputOption> loaded = AudioInputCatalog.list(this);
            boolean previousBluetooth = previousSelection != null
                    && previousSelection.isBluetooth();
            boolean bluetoothEnumerated = AudioInputCatalog.preferredAutomaticInput(loaded) != null
                    && AudioInputCatalog.preferredAutomaticInput(loaded).isBluetooth();
            boolean profileConnected = AudioInputCatalog.isBluetoothInputProfileConnected(this);
            if (previousBluetooth && !bluetoothEnumerated && profileConnected && attempt < 10) {
                uiHandler.postDelayed(() -> refreshInputForStart(previousId,
                        previousSelection, attempt + 1), 150L);
                return;
            }
            runOnUiThread(() -> {
                if (previousBluetooth && !bluetoothEnumerated && profileConnected) {
                    inputRefreshRunning.set(false);
                    primaryButton.setText("Start recording");
                    primaryButton.setContentDescription("Start recording");
                    primaryButton.setEnabled(true);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Bluetooth microphone is still connecting")
                            .setMessage("The connected Bluetooth headset has not exposed its microphone route to Android yet. Voice Button will not silently switch this recording to the phone microphone. Try Start again in a moment, or choose the built-in microphone explicitly.")
                            .setPositiveButton("Back", null)
                            .show();
                    return;
                }
                applyInputs(loaded, previousId);
                completeStartWithCurrentInputs(previousId);
            });
        });
    }

    private void waitForFreshMicrophoneThenStart(int previousId) {
        if (inputRefreshRunning.get()) {
            uiHandler.postDelayed(() -> waitForFreshMicrophoneThenStart(previousId), 100L);
            return;
        }
        completeStartWithCurrentInputs(previousId);
    }

    private void completeStartWithCurrentInputs(int previousId) {
        if (inputs.isEmpty() || selectedDeviceId == AudioInputOption.DEFAULT_DEVICE_ID) {
            primaryButton.setText("Start recording");
            primaryButton.setContentDescription("Start recording");
            primaryButton.setEnabled(false);
            if (primaryDisabledReasonText != null) {
                primaryDisabledReasonText.setText("No physical microphone is currently available.");
                primaryDisabledReasonText.setVisibility(View.VISIBLE);
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle("No microphone available")
                    .setMessage("No physical microphone is currently available to Android.")
                    .setPositiveButton("Back", null)
                    .show();
            return;
        }
        if (previousId != selectedDeviceId) {
            diag(PhoneDiagnostics.WARN, "microphone.start_reresolved",
                    snapshot.currentSessionId,
                    "The saved Android microphone ID was stale and was resolved to a current input",
                    PhoneDiagnostics.fields("previous_device_id", previousId,
                            "current_device_id", selectedDeviceId,
                            "current_label", selectedInputLabel()));
        }
        showPrimaryPending("Starting…");
        sendAction(RecordingService.ACTION_START, null, true);
    }

    private void showInputPicker() {
        if (snapshot.recording) {
            new MaterialAlertDialogBuilder(this).setTitle("Microphone locked while recording")
                    .setMessage("Pause the recording before selecting another microphone.")
                    .setPositiveButton("Back", null).show();
            return;
        }
        String[] labels = new String[inputs.size() + 1];
        for (int i = 0; i < inputs.size(); i++) labels[i] = inputs.get(i).getLabel();
        labels[inputs.size()] = "Refresh microphone list";
        new MaterialAlertDialogBuilder(this).setTitle("Microphone")
                .setItems(labels, (dialog, which) -> {
                    if (which == inputs.size()) { refreshInputs(); return; }
                    AudioInputOption selected = inputs.get(which);
                    inputManuallySelectedThisRun = true;
                    selectedDeviceId = selected.getDeviceId();
                    saveMainSelectionState();
                    updateSetupButtons();
                    diag(PhoneDiagnostics.INFO, "microphone.selected", snapshot.currentSessionId,
                            "A microphone was selected",
                            PhoneDiagnostics.fields("device_id", selected.getDeviceId(),
                                    "device_type", selected.getDeviceType(),
                                    "label", selected.getLabel()));
                }).setNegativeButton("Back", null).show();
    }

    private AudioInputOption findInputById(int deviceId) {
        for (AudioInputOption input : inputs) {
            if (input.getDeviceId() == deviceId) return input;
        }
        return null;
    }

    private static boolean containsDeviceId(AudioDeviceInfo[] devices, int deviceId) {
        if (devices == null) return false;
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.getId() == deviceId) return true;
        }
        return false;
    }

    private static boolean containsBluetoothInput(AudioDeviceInfo[] devices) {
        if (devices == null) return false;
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSource()) continue;
            int type = device.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || (Build.VERSION.SDK_INT >= 31
                    && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
        }
        return false;
    }

    private void registerAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered) return;
        AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        manager.registerAudioDeviceCallback(audioDeviceCallback, uiHandler);
        audioDeviceCallbackRegistered = true;
    }

    private void unregisterAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered) return;
        AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (manager != null) manager.unregisterAudioDeviceCallback(audioDeviceCallback);
        audioDeviceCallbackRegistered = false;
    }

    private void saveMainSelectionState() {
        if (guiPreferences == null) return;
        guiPreferences.edit()
                .putString(PREF_FOLDER_ID, selectedFolderId)
                .putString(PREF_FOLDER_NAME, selectedFolderName)
                .putInt(PREF_DEVICE_ID, selectedDeviceId)
                .apply();
    }

    private void updateSetupButtons() {
        if (folderButton != null) {
            String label = selectedFolderName;
            for (ReliableSessionStore.Folder folder : folders) {
                if (folder.id.equals(selectedFolderId)) {
                    label = folder.path;
                    break;
                }
            }
            folderButton.setText("Folder: " + label);
        }
        if (inputButton != null) inputButton.setText("Microphone: " + selectedInputLabel());
    }

    private String selectedInputLabel() {
        for (AudioInputOption input : inputs) {
            if (input.getDeviceId() == selectedDeviceId) return input.getLabel();
        }
        return inputs.isEmpty() ? "none available" : inputs.get(0).getLabel();
    }

    private void primaryAction() {

        VoiceButtonLocalTrace.log(this, "ui.main.primary_tap",
                "state", snapshot.state,
                "recording", snapshot.recording,
                "paused", snapshot.paused,
                "selected_device_id", selectedDeviceId,
                "service_bound", service != null,
                "button_text", primaryButton == null ? "" : primaryButton.getText());
        if (statusDetail != null) statusDetail.setText("Applying recording action…");
        diag(PhoneDiagnostics.INFO, "ui.main.primary_pressed", snapshot.currentSessionId,
                "Primary recording action was pressed",
                PhoneDiagnostics.fields("state", snapshot.state,
                        "recording", snapshot.recording,
                        "paused", snapshot.paused,
                        "selected_device_id", selectedDeviceId));
        String resolvedAction = RecordingStateResolver.primaryAction(
                snapshot.recording,
                snapshot.openSession != null && snapshot.openSession.paused,
                snapshot.interrupted != null);
        if (RecordingService.ACTION_RESUME.equals(resolvedAction)) {
            showPrimaryPending("Resuming…");
            sendAction(RecordingService.ACTION_RESUME, snapshot.openSession.sessionId, true);
            return;
        }
        if (RecordingService.ACTION_PAUSE.equals(resolvedAction)) {
            showPrimaryPending("Pausing…");
            sendAction(RecordingService.ACTION_PAUSE, snapshot.currentSessionId, false);
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissionsIfNeeded();
            return;
        }
        if (snapshot.recordingErrorActive) {
            sendAction(RecordingService.ACTION_PAUSE, snapshot.currentSessionId, false);
            return;
        }
        if (snapshot.interrupted != null) {
            showRecoveryDialog(snapshot.interrupted);
            return;
        }
        startWithFreshMicrophone();
    }

    private void showPrimaryPending(String label) {
        if (primaryButton == null) return;
        primaryButton.setText(label);
        primaryButton.setContentDescription(label);
        primaryButton.setEnabled(false);
        if (primaryDisabledReasonText != null) {
            primaryDisabledReasonText.setText("Recording request submitted. Protected capture is opening.");
            primaryDisabledReasonText.setVisibility(View.VISIBLE);
        }
    }

    private void finishCurrent() {
        ReliableSessionManifest open = snapshot.openSession;
        if (open == null && !snapshot.recording) return;
        String duration = RecordingUi.formatDuration(snapshot.durationMs);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Finish this recording?")
                .setMessage("This closes the current recording and starts finalization. "
                        + "The protected audio stays saved and playable, but this recording "
                        + "cannot be resumed after it is closed.\n\nCurrent duration: " + duration)
                .setPositiveButton("Finish recording", (dialog, which) -> performFinishCurrent())
                .setNegativeButton("Keep recording", null)
                .show();
    }

    private void performFinishCurrent() {
        ReliableSessionManifest open = snapshot.openSession;
        if (open == null && !snapshot.recording) return;
        String sessionId = open == null ? snapshot.currentSessionId : open.sessionId;
        if (secondaryButton != null) {
            secondaryButton.setText("Finishing…");
            secondaryButton.setContentDescription("Finishing recording");
            secondaryButton.setEnabled(false);
        }
        if (statusDetail != null) statusDetail.setText("Finishing recording safely…");
        diag(PhoneDiagnostics.INFO, "ui.main.finish_pressed", sessionId,
                "Finish recording was confirmed",
                PhoneDiagnostics.fields("state", snapshot.state,
                        "recording", snapshot.recording,
                        "paused", snapshot.paused));
        sendAction(RecordingService.ACTION_FINISH, sessionId, false);
    }

    private void showRecoveryDialog(ReliableSessionManifest interrupted) {
        if (interrupted == null || interrupted.paused
                || !"INTERRUPTED".equals(interrupted.state) || isFinishing()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Interrupted recording found")
                .setMessage("About " + RecordingUi.formatDuration(interrupted.totalDurationMs)
                        + " is safely stored. Continue the same recording, or close it as an MP3 and start a new one?")
                .setCancelable(false)
                .setPositiveButton("Continue recording", (dialog, which) -> {
                    diag(PhoneDiagnostics.INFO, "ui.recovery_continue", interrupted.sessionId,
                            "User chose to continue the interrupted recording",
                            PhoneDiagnostics.fields("duration_ms", interrupted.totalDurationMs,
                                    "segment_count", interrupted.segments.size()));
                    sendAction(RecordingService.ACTION_RESUME, interrupted.sessionId, true);
                })
                .setNegativeButton("Close old and start new", (dialog, which) -> {
                    diag(PhoneDiagnostics.INFO, "ui.recovery_finish_and_start", interrupted.sessionId,
                            "User chose to close the interrupted recording and start a new one",
                            PhoneDiagnostics.fields("duration_ms", interrupted.totalDurationMs,
                                    "segment_count", interrupted.segments.size()));
                    sendAction(RecordingService.ACTION_FINISH_AND_START, interrupted.sessionId, true);
                })
                .show();
    }

    private void showMoreMenu() {
        String[] actions = {
                "Player and files",
                "Current status details",
                "Refresh microphones",
                "Retry synchronization",
                "Copy support summary",
                "Export full diagnostics",
                "About"
        };
        new MaterialAlertDialogBuilder(this).setTitle("More")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) openPlayer();
                    else if (which == 1) showStatusDetails();
                    else if (which == 2) refreshInputs();
                    else if (which == 3) retrySynchronization();
                    else if (which == 4) copySupportSummary();
                    else if (which == 5) exportFullDiagnostics();
                    else showAbout();
                }).setNegativeButton("Back", null).show();
    }

    private void retrySynchronization() {
        diag(PhoneDiagnostics.INFO, "ui.synchronization_retry_requested", null,
                "The user requested synchronization only",
                PhoneDiagnostics.fields("recording", snapshot.recording,
                        "state", snapshot.state,
                        "pending_bytes", snapshot.uploadPendingBytes));
        RecordingService value = service;
        if (value != null) value.retrySynchronization();
        else UploadWorkScheduler.enqueue(this, "manual_retry_without_service");
    }

    private void openPlayer() {
        VoiceButtonLocalTrace.log(this, "ui.main.open_player_tap",
                "state", snapshot.state,
                "service_bound", service != null,
                "current_session", snapshot.currentSessionId);
        startActivity(new Intent(this, PlayerActivity.class));
    }

    private String effectiveRecordingState() {
        ReliableSessionManifest open = snapshot.openSession;
        return RecordingStateResolver.normalize(snapshot.state,
                snapshot.recording,
                open != null && open.paused,
                snapshot.interrupted != null);
    }

    private String effectiveRecordingExplanation() {
        return RecordingStateResolver.explanation(effectiveRecordingState(),
                snapshot.explanation);
    }

    private void refreshTranscriptionStatusOnce() {
        if (transcriptionClient == null
                || !transcriptionStatusRunning.compareAndSet(false, true)) return;
        try {
            transcriptionStatusWorker.execute(() -> {
                try {
                    ReliableUploadClient.TranscriptionStatus value =
                            transcriptionClient.transcriptionStatus();
                    runOnUiThread(() -> applyTranscriptionStatus(value, null));
                } catch (Exception failure) {
                    runOnUiThread(() -> applyTranscriptionStatus(null, failure));
                } finally {
                    transcriptionStatusRunning.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            transcriptionStatusRunning.set(false);
        }
    }

    private MaterialButton materialPrimaryButton(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(19);
        button.setMinHeight(AndroidUi.dp(this, 56));
        return button;
    }

    private MaterialButton materialSecondaryButton(String text) {
        MaterialButton button = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(AndroidUi.dp(this, 48));
        return button;
    }

    private MaterialButton materialDangerButton(String text) {
        MaterialButton button = materialSecondaryButton(text);
        int error = MaterialColors.getColor(button,
                com.google.android.material.R.attr.colorError, AndroidUi.RED);
        button.setTextColor(error);
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(error));
        return button;
    }

    private MaterialButton materialToolbarButton(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(AndroidUi.dp(this, 48));
        return button;
    }

    private void showStatusDetails() {
        refreshTranscriptionStatusOnce();
        String effectiveState = effectiveRecordingState();
        String message = "Recording: " + MainScreenText.stateTitle(effectiveState,
                snapshot.recording, snapshot.openSession != null && snapshot.openSession.paused,
                snapshot.recordingErrorActive)
                + "\n\n" + effectiveRecordingExplanation()
                + "\n\nMicrophone: " + snapshot.routedInput
                + "\nFolder: " + selectedFolderName
                + "\n" + String.valueOf(serverHealthText.getText())
                + "\n" + String.valueOf(transferText.getText())
                + "\n" + String.valueOf(transcriptionSummaryText.getText())
                + (transcriptionCurrentText.getText().length() == 0 ? ""
                        : "\n" + transcriptionCurrentText.getText());
        new MaterialAlertDialogBuilder(this).setTitle("Current status")
                .setMessage(message).setPositiveButton("Back", null).show();
    }

    private String readLatestPlayerSummary() {
        java.io.File file = new java.io.File(new java.io.File(getNoBackupFilesDir(),
                "player_state"), "latest_summary.txt");
        if (!file.isFile()) return "unavailable";
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            String value = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? "empty" : value.replace("\n", " ").replace("\r", " ");
        } catch (Exception failure) {
            return "unreadable: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage();
        }
    }

    private void copySupportSummary() {
        String report;
        RecordingService value = service;
        VoiceButtonLocalTrace.log(this, "ui.main.copy_support_tap",
                "state", snapshot.state,
                "service_bound", value != null,
                "player", readLatestPlayerSummary());
        if (value == null) {
            report = "Voice Button support summary\napp_version="
                    + BuildConfig.VERSION_NAME + " code=" + BuildConfig.VERSION_CODE
                    + "\nservice=not_connected\nsummary_reliability=stale_ui_snapshot"
                    + "\nstate=" + effectiveRecordingState()
                    + "\nstatus=" + effectiveRecordingExplanation()
                    + "\nplayer=" + readLatestPlayerSummary()
                    + "\nlocal_trace_tail=\n"
                    + VoiceButtonLocalTrace.tail(this, 24000) + "\n";
        } else report = value.buildSupportSummary();
        try {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("Clipboard is unavailable");
            clipboard.setPrimaryClip(ClipData.newPlainText("Voice Button support summary", report));
            ClipData copied = clipboard.getPrimaryClip();
            CharSequence copiedText = copied == null || copied.getItemCount() == 0
                    ? null : copied.getItemAt(0).coerceToText(this);
            if (copiedText == null || !report.contentEquals(copiedText)) {
                throw new IllegalStateException("Android did not retain the copied text");
            }
            diag(PhoneDiagnostics.INFO, "ui.copy_debug", snapshot.currentSessionId,
                    "Bounded support summary was verified on the clipboard",
                    PhoneDiagnostics.fields("characters", report.length()));
            new MaterialAlertDialogBuilder(this).setTitle("Support summary copied")
                    .setMessage(report.length() + " characters are on the clipboard and ready to paste.")
                    .setPositiveButton("OK", null).show();
        } catch (Exception failure) {
            new MaterialAlertDialogBuilder(this).setTitle("Copy failed")
                    .setMessage(PhoneDiagnostics.exactFailure(
                            "Copying the support summary", failure))
                    .setPositiveButton("Back", null).show();
        }
    }

    private void exportFullDiagnostics() {
        RecordingService value = service;
        uiWorker.execute(() -> {
            try {
                VoiceButtonLocalTrace.log(this, "ui.main.export_diagnostics_capture",
                        "state", effectiveRecordingState(),
                        "service_bound", value != null,
                        "player", readLatestPlayerSummary());
                String report = value == null ? "Recording service is not connected.\n"
                        + "Summary reliability: stale UI snapshot only.\n"
                        + "Support summary:\n" + effectiveRecordingState()
                        + "\n" + effectiveRecordingExplanation()
                        + "\nPlayer:\n" + readLatestPlayerSummary()
                        + "\nLocal trace tail:\n" + VoiceButtonLocalTrace.tail(this, 48000) + "\n"
                        : value.buildDebugReport();
                java.io.File pending = new java.io.File(getCacheDir(),
                        PENDING_DIAGNOSTICS_EXPORT);
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(pending, false)) {
                    output.write(report.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                runOnUiThread(() -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE,
                            "voicebutton-diagnostics-" + BuildConfig.VERSION_NAME + ".txt");
                    startActivityForResult(intent, DEBUG_EXPORT_REQUEST);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle("Export failed")
                        .setMessage(PhoneDiagnostics.exactFailure(
                                "Capturing diagnostics", failure))
                        .setPositiveButton("Back", null).show());
            }
        });
    }

    private void writeFullDiagnostics(Uri destination) {
        uiWorker.execute(() -> {
            java.io.File pending = new java.io.File(getCacheDir(),
                    PENDING_DIAGNOSTICS_EXPORT);
            try {
                if (!pending.isFile()) throw new java.io.IOException(
                        "Captured diagnostic report is unavailable");
                try (java.io.FileInputStream input = new java.io.FileInputStream(pending);
                     OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new java.io.IOException("Destination could not be opened");
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.flush();
                }
                pending.delete();
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle("Diagnostics exported")
                        .setMessage("The live diagnostic report captured before the file picker was written successfully.")
                        .setPositiveButton("OK", null).show());
            } catch (Exception failure) {
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle("Export failed")
                        .setMessage(PhoneDiagnostics.exactFailure(
                                "Exporting captured diagnostics", failure))
                        .setPositiveButton("Back", null).show());
            }
        });
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this).setTitle("Voice Button")
                .setMessage("Version " + BuildConfig.VERSION_NAME
                        + "\nRecording and synchronization continue in the foreground after this screen closes."
                        + "\n\nOverview diagnostics are intentionally hidden. Use Current status details, Copy support summary, or Export full diagnostics when needed.")
                .setPositiveButton("Back", null).show();
    }

    private void sendAction(String action, String sessionId, boolean foreground) {
        VoiceButtonLocalTrace.log(this, "ui.main.send_recording_action",
                "action", action,
                "session", sessionId,
                "foreground", foreground,
                "selected_device_id", selectedDeviceId,
                "folder", selectedFolderId,
                "state", snapshot.state,
                "service_bound", service != null);

        diag(PhoneDiagnostics.INFO, "ui.service_action_sent", sessionId,
                "Main screen sent a RecordingService action",
                PhoneDiagnostics.fields("action", action,
                        "foreground", foreground,
                        "device_id", selectedDeviceId,
                        "folder_id", selectedFolderId,
                        "folder_name", selectedFolderName));
        Intent intent = new Intent(this, RecordingService.class).setAction(action)
                .putExtra(RecordingService.EXTRA_DEVICE_ID, selectedDeviceId)
                .putExtra(RecordingService.EXTRA_FOLDER_ID, selectedFolderId)
                .putExtra(RecordingService.EXTRA_FOLDER_NAME, selectedFolderName);
        if (sessionId != null) intent.putExtra(RecordingService.EXTRA_SESSION_ID, sessionId);
        if (foreground) ContextCompat.startForegroundService(this, intent);
        else startService(intent);
    }

    private void render(RecordingService.Snapshot value) {
        snapshot = value == null ? RecordingService.Snapshot.initial() : value;
        ReliableSessionManifest open = snapshot.openSession;
        String effectiveState = RecordingStateResolver.normalize(snapshot.state,
                snapshot.recording, open != null && open.paused,
                snapshot.interrupted != null);
        if (open != null) {
            selectedFolderId = open.folderId;
            selectedFolderName = open.folderName;
            saveMainSelectionState();
        }
        String structureKey = MainScreenText.structureKey(effectiveState,
                snapshot.recording, snapshot.paused,
                snapshot.recordingErrorActive, snapshot.recordingErrorAlarmAudible,
                open != null, selectedFolderId, selectedDeviceId,
                snapshot.sessions.size());
        if (!structureKey.equals(lastStructureKey)) {
            lastStructureKey = structureKey;
            int color = RecordingUi.stateColor(effectiveState);
            setTextIfChanged(statusTitle, MainScreenText.stateTitle(effectiveState,
                    snapshot.recording, snapshot.paused, snapshot.recordingErrorActive));
            statusTitle.setTextColor(color);
            setTextIfChanged(statusDetail, MainScreenText.stateSummary(effectiveState,
                    snapshot.recording, snapshot.paused,
                    snapshot.recordingErrorActive, open != null));
            statusDetail.setVisibility(MainScreenText.shouldShowStateDetail(
                    effectiveState, snapshot.recording, snapshot.paused,
                    snapshot.recordingErrorActive, open != null)
                    ? View.VISIBLE : View.GONE);
            if (snapshot.recording) primaryButton.setText("Pause recording");
            else if (open != null && open.paused) primaryButton.setText("Resume recording");
            else if (snapshot.recordingErrorActive) primaryButton.setText("Pause recovery");
            else if (snapshot.interrupted != null) primaryButton.setText("Recover recording");
            else primaryButton.setText("Start recording");
            boolean hasRecordPermission = hasPermission(Manifest.permission.RECORD_AUDIO);
            boolean hasMicrophone = !inputs.isEmpty();
            boolean canAttemptMicrophone = PrimaryActionPolicy.canAttemptMicrophone(
                    hasMicrophone, inputRefreshRunning.get(), hasRecordPermission);
            boolean primaryEnabled = PrimaryActionPolicy.isEnabled(
                    snapshot.recording, effectiveState, open != null,
                    open != null && open.paused, snapshot.interrupted != null,
                    canAttemptMicrophone);
            primaryButton.setEnabled(primaryEnabled);
            primaryButton.setContentDescription(primaryButton.getText());
            String disabledReason = primaryEnabled ? "" : PrimaryActionPolicy.disabledReason(
                    snapshot.recording, effectiveState, open != null,
                    open != null && open.paused, snapshot.interrupted != null,
                    hasMicrophone, hasRecordPermission);
            setTextIfChanged(primaryDisabledReasonText, disabledReason);
            primaryDisabledReasonText.setVisibility(disabledReason.isEmpty()
                    ? View.GONE : View.VISIBLE);
            configureSecondaryAction(open);
            updateSetupButtons();
        }
        setTextIfChanged(durationText, RecordingUi.formatDuration(snapshot.durationMs));
        durationText.setVisibility(MainScreenText.shouldShowTimer(
                effectiveState, snapshot.recording, snapshot.paused, open != null)
                ? View.VISIBLE : View.GONE);
        if (setupContainer != null) {
            setupContainer.setVisibility(MainScreenText.shouldShowSetup(
                    effectiveState, snapshot.recording, open != null,
                    snapshot.interrupted != null, snapshot.recordingErrorActive)
                    ? View.VISIBLE : View.GONE);
        }
        setTextIfChanged(currentText, MainScreenText.localProtection(
                open == null ? selectedFolderName : open.folderName, open != null));
        currentText.setVisibility(open != null ? View.VISIBLE : View.GONE);
        String microphone = snapshot.recording ? snapshot.routedInput : selectedInputLabel();
        setTextIfChanged(routedText, "Microphone: " + microphone);
        routedText.setVisibility(snapshot.recording ? View.VISIBLE : View.GONE);
        setTextIfChanged(micLevelText, MainScreenText.microphone(
                snapshot.recording, snapshot.inputSignalDetected));
        micLevelText.setVisibility(snapshot.recording ? View.VISIBLE : View.GONE);
        int level = snapshot.recording ? snapshot.inputLevelPermille : 0;
        if (Math.abs(micLevelBar.getProgress() - level) >= 8) micLevelBar.setProgress(level);
        micLevelBar.setContentDescription(micLevelText.getText());
        micLevelBar.setVisibility(snapshot.recording ? View.VISIBLE : View.GONE);
        int uploadFilesLeft = OverviewProgress.uploadFilesRemaining(snapshot.sessions);
        boolean uploadHasUnmeasured = OverviewProgress.hasUnmeasuredUpload(snapshot.sessions);
        setTextIfChanged(transferText, MainScreenText.uploadOverall(
                snapshot.uploadProgressPermille, uploadFilesLeft, uploadHasUnmeasured));
        ReliableSessionManifest uploadSession = OverviewProgress.findSession(
                snapshot.sessions, snapshot.liveUploadSessionId);
        String uploadFileName = OverviewProgress.fileName(uploadSession);
        int uploadFileProgress = OverviewProgress.fileProgressPermille(uploadSession);
        setTextIfChanged(uploadCurrentText, MainScreenText.uploadCurrent(
                uploadFileName, uploadFileProgress, uploadFilesLeft,
                snapshot.liveUploadOperation));
        progressBar.setIndeterminate(uploadHasUnmeasured && snapshot.uploadTotalBytes <= 0L);
        int overallUploadProgress = Math.max(0, Math.min(1000,
                snapshot.uploadProgressPermille));
        if (!progressBar.isIndeterminate()
                && progressBar.getProgress() != overallUploadProgress) {
            progressBar.setProgress(overallUploadProgress);
        }
        boolean backupComplete = uploadFilesLeft <= 0 && !uploadHasUnmeasured;
        transferText.setTextColor("retry_backoff".equals(snapshot.liveUploadOperation)
                ? AndroidUi.ORANGE : backupComplete ? AndroidUi.GREEN : AndroidUi.INK);
        transferText.setVisibility(View.VISIBLE);
        progressBar.setContentDescription(transferText.getText());
        progressBar.setVisibility(backupComplete ? View.GONE : View.VISIBLE);

        boolean showCurrentUpload = !backupComplete && uploadSession != null;
        uploadCurrentText.setVisibility(showCurrentUpload ? View.VISIBLE : View.GONE);
        if (showCurrentUpload) {
            boolean currentUnknown = uploadFileProgress < 0;
            uploadCurrentProgressBar.setIndeterminate(currentUnknown);
            if (!currentUnknown) {
                int currentUploadPermille = Math.max(0, Math.min(1000, uploadFileProgress));
                if (uploadCurrentProgressBar.getProgress() != currentUploadPermille) {
                    uploadCurrentProgressBar.setProgress(currentUploadPermille);
                }
            }
            uploadCurrentProgressBar.setContentDescription(uploadCurrentText.getText());
            uploadCurrentProgressBar.setVisibility(View.VISIBLE);
        } else {
            uploadCurrentProgressBar.setIndeterminate(false);
            uploadCurrentProgressBar.setProgress(backupComplete ? 1000 : 0);
            uploadCurrentProgressBar.setVisibility(View.GONE);
        }
    }

    private void applyTranscriptionStatus(
            ReliableUploadClient.TranscriptionStatus value, Exception failure) {
        if (transcriptionSummaryText == null || transcriptionCurrentText == null
                || transcriptionProgressBar == null || transcriptionCurrentProgressBar == null
                || serverHealthText == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (value != null) {
            lastTranscriptionStatus = value;
            lastTranscriptionStatusSuccessElapsedMs = now;
            if (value.completeCount != lastReportedTranscriptionCompleteCount
                    || value.notTranscribedCount != lastReportedTranscriptionPendingCount) {
                lastReportedTranscriptionCompleteCount = value.completeCount;
                lastReportedTranscriptionPendingCount = value.notTranscribedCount;
                diag(PhoneDiagnostics.INFO, "transcription.status_received", null,
                        "Jetson transcription state was received",
                        PhoneDiagnostics.fields("complete_count", value.completeCount,
                                "pending_count", value.notTranscribedCount,
                                "overall_percent", value.overallPercent));
            }
            setTextIfChanged(serverHealthText, MainScreenText.jetsonHealth(true, true, 0L));
            serverHealthText.setContentDescription(serverHealthText.getText());
            serverHealthText.setVisibility(View.GONE);
            renderTranscriptionOverview(value, "", false);
            return;
        }
        if (failure != null) {
            diag(PhoneDiagnostics.ERROR, "transcription.status_failed", null,
                    "Reading Jetson transcription state failed",
                    PhoneDiagnostics.fields("exception_class", failure.getClass().getName(),
                            "exception_message", String.valueOf(failure.getMessage())));
        }
        boolean hasSuccess = lastTranscriptionStatus != null
                && lastTranscriptionStatusSuccessElapsedMs >= 0L;
        long age = hasSuccess
                ? Math.max(0L, now - lastTranscriptionStatusSuccessElapsedMs) : 0L;
        setTextIfChanged(serverHealthText,
                MainScreenText.jetsonHealth(hasSuccess, false, age));
        serverHealthText.setContentDescription(serverHealthText.getText());
        serverHealthText.setVisibility(View.GONE);
        if (hasSuccess) {
            renderTranscriptionOverview(lastTranscriptionStatus,
                    " · last update " + formatStatusAge(age) + " ago", true);
        } else {
            setTextIfChanged(transcriptionSummaryText,
                    "Transcription overall: unavailable · files left unknown");
            setTextIfChanged(transcriptionCurrentText,
                    "Transcription current file: unavailable");
            transcriptionSummaryText.setTextColor(AndroidUi.ORANGE);
            transcriptionCurrentText.setTextColor(AndroidUi.ORANGE);
            transcriptionProgressBar.setIndeterminate(true);
            transcriptionProgressBar.setVisibility(View.VISIBLE);
            transcriptionProgressBar.setContentDescription(
                    "Transcription status unavailable");
            transcriptionCurrentProgressBar.setVisibility(View.GONE);
        }
    }

    private void renderTranscriptionOverview(
            ReliableUploadClient.TranscriptionStatus value,
            String staleSuffix, boolean stale) {
        setTextIfChanged(transcriptionSummaryText, MainScreenText.transcriptionOverall(
                value.overallPercent, value.notTranscribedCount, staleSuffix));
        ReliableUploadClient.CurrentTranscription current = value.current;
        String label = current == null ? ""
                : (current.displayName == null || current.displayName.isEmpty()
                        ? current.sessionId : current.displayName);
        setTextIfChanged(transcriptionCurrentText, MainScreenText.transcriptionCurrent(
                label, current == null ? 0 : current.percent,
                value.notTranscribedCount, current == null ? "" : current.phase));
        transcriptionSummaryText.setTextColor(stale ? AndroidUi.ORANGE
                : value.notTranscribedCount <= 0 ? AndroidUi.GREEN : AndroidUi.INK);
        transcriptionCurrentText.setTextColor(stale ? AndroidUi.ORANGE : AndroidUi.INK);
        transcriptionProgressBar.setIndeterminate(false);
        int progress = Math.max(0, Math.min(1000, value.overallPercent * 10));
        if (transcriptionProgressBar.getProgress() != progress) {
            transcriptionProgressBar.setProgress(progress);
        }
        transcriptionProgressBar.setContentDescription(
                "Transcription overall " + value.overallPercent + " percent · "
                        + MainScreenText.filesLeftLabel(value.notTranscribedCount));
        boolean transcriptionComplete = value.notTranscribedCount <= 0;
        transcriptionProgressBar.setVisibility(transcriptionComplete ? View.GONE : View.VISIBLE);
        transcriptionCurrentText.setVisibility(current == null ? View.GONE : View.VISIBLE);
        if (current != null) {
            transcriptionCurrentProgressBar.setIndeterminate(false);
            int currentProgress = Math.max(0, Math.min(1000, current.percent * 10));
            if (transcriptionCurrentProgressBar.getProgress() != currentProgress) {
                transcriptionCurrentProgressBar.setProgress(currentProgress);
            }
            transcriptionCurrentProgressBar.setContentDescription(
                    "Current transcription " + current.percent + " percent");
            transcriptionCurrentProgressBar.setVisibility(View.VISIBLE);
        } else {
            transcriptionCurrentProgressBar.setProgress(transcriptionComplete ? 1000 : 0);
            transcriptionCurrentProgressBar.setVisibility(View.GONE);
        }
    }

    private static String formatStatusAge(long ageMs) {
        long seconds = Math.max(0L, ageMs) / 1000L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        return minutes < 60L ? minutes + "m" : (minutes / 60L) + "h";
    }

    private void configureSecondaryAction(ReliableSessionManifest open) {
        secondaryButton.setEnabled(true);
        if ("FINISHING".equals(snapshot.state)) {
            secondaryButton.setVisibility(View.VISIBLE);
            secondaryButton.setText("Finishing…");
            secondaryButton.setContentDescription("Finishing recording");
            secondaryButton.setEnabled(false);
            secondaryButton.setOnClickListener(null);
        } else if (snapshot.recordingErrorActive && snapshot.recordingErrorAlarmAudible) {
            secondaryButton.setVisibility(View.VISIBLE);
            secondaryButton.setText("Silence alarm");
            secondaryButton.setContentDescription("Silence recording error alarm");
            secondaryButton.setOnClickListener(v -> sendAction(
                    RecordingService.ACTION_SILENCE_ALARM,
                    snapshot.currentSessionId, false));
        } else if (snapshot.recording || open != null) {
            secondaryButton.setVisibility(View.VISIBLE);
            secondaryButton.setText("Finish");
            secondaryButton.setContentDescription(
                    "Finish recording; confirmation required");
            secondaryButton.setOnClickListener(v -> finishCurrent());
        } else {
            secondaryButton.setVisibility(View.GONE);
            secondaryButton.setOnClickListener(null);
        }
    }

    private static void setTextIfChanged(TextView view, String text) {
        if (!String.valueOf(view.getText()).equals(text)) view.setText(text);
    }

    private void requestPermissionsIfNeeded() {

        List<String> missing = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQUEST);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST) {
            org.json.JSONArray result = new org.json.JSONArray();
            for (int i = 0; i < permissions.length; i++) {
                org.json.JSONObject item = new org.json.JSONObject();
                try {
                    item.put("permission", permissions[i]);
                    item.put("granted", i < results.length && results[i] == PackageManager.PERMISSION_GRANTED);
                } catch (Exception ignored) {}
                result.put(item);
            }
            diag(PhoneDiagnostics.INFO, "permissions.result", null,
                    "Android permission request completed", PhoneDiagnostics.fields("results", result));
            refreshInputs();
            render(snapshot);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
                                              Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DEBUG_EXPORT_REQUEST && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            writeFullDiagnostics(data.getData());
        }
    }

    @Override public void onBackPressed() {
        diag(PhoneDiagnostics.INFO, "ui.background_requested", snapshot.currentSessionId,
                "The main screen was closed; recording and synchronization continue in the foreground service",
                PhoneDiagnostics.fields("state", snapshot.state,
                        "recording", snapshot.recording,
                        "pending_bytes", snapshot.uploadPendingBytes));
        finishAndRemoveTask();
    }

    private static boolean isBusyState(String state) {
        return "PREPARING".equals(state)
                || "PAUSING".equals(state)
                || "FINISHING".equals(state)
                || "SYNCHRONIZING".equals(state)
                || "RECONCILING".equals(state)
                || "COMPRESSING".equals(state)
                || "CLEANING".equals(state);
    }

    private void diag(String level, String event, String sessionId,
                      String message, org.json.JSONObject fields) {
        PhoneDiagnostics value = diagnostics;
        if (value == null) {
            value = PhoneDiagnostics.get();
            diagnostics = value;
        }
        if (value != null) value.log(level, event, sessionId, message, fields);
    }

    private static int tint(int color) {
        return Color.rgb((Color.red(color) + 255 * 7) / 8,
                (Color.green(color) + 255 * 7) / 8,
                (Color.blue(color) + 255 * 7) / 8);
    }

}
