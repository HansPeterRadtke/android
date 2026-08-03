package com.hans.android.voicebutton;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.common_ui.AndroidUi;

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

    private final List<AudioInputOption> inputs = new ArrayList<>();
    private final List<ReliableSessionStore.Folder> folders = new ArrayList<>();
    private LinearLayout statusCard;
    private TextView statusTitle;
    private TextView statusDetail;
    private ProgressBar progressBar;
    private TextView transferText;
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

    private int selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
    private String selectedFolderId = "default";
    private String selectedFolderName = "Default";
    private boolean updatingFolders;
    private boolean inputsLoaded;
    private RecordingService service;
    private boolean bound;
    private RecordingService.Snapshot snapshot = RecordingService.Snapshot.initial();
    private String promptedSessionId = "";
    private PhoneDiagnostics diagnostics;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService uiWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean inputRefreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean folderRefreshRunning = new AtomicBoolean(false);
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
        diagnostics = PhoneDiagnostics.get();
        if (diagnostics == null) {
            PhoneDiagnostics.initializeAsync(this, BuildConfig.VOICE_BASE_URL,
                    BuildConfig.VERSION_NAME);
        }
        diag(PhoneDiagnostics.INFO, "ui.main.create", null,
                "MainActivity onCreate", PhoneDiagnostics.fields("has_saved_state", savedInstanceState != null));
        buildScreen();
        requestPermissionsIfNeeded();
    }

    @Override protected void onStart() {
        super.onStart();
        diag(PhoneDiagnostics.INFO, "ui.main.start", null,
                "MainActivity onStart", PhoneDiagnostics.fields());
        if (!inputsLoaded) refreshInputs();
        bindService(new Intent(this, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
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
        super.onDestroy();
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(AndroidUi.BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUi.dp(this, 12);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = AndroidUi.title(this, "Voice Button");
        AndroidUi.stableLine(this, title, 42);
        content.addView(title);

        statusCard = AndroidUi.card(this);
        statusCard.setPadding(AndroidUi.dp(this, 16), AndroidUi.dp(this, 12),
                AndroidUi.dp(this, 16), AndroidUi.dp(this, 12));
        statusTitle = AndroidUi.text(this, "READY", 20, true, AndroidUi.GREEN);
        AndroidUi.stableLine(this, statusTitle, 34);
        durationText = AndroidUi.text(this, "00:00:00", 34, true, AndroidUi.INK);
        durationText.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        durationText.setGravity(Gravity.CENTER);
        durationText.setMinHeight(AndroidUi.dp(this, 52));
        durationText.setMaxHeight(AndroidUi.dp(this, 52));
        statusDetail = AndroidUi.body(this, "Ready to start a protected recording.");
        AndroidUi.stableLine(this, statusDetail, 38);
        currentText = AndroidUi.small(this, "Local protection: ready");
        AndroidUi.stableLine(this, currentText, 30);
        routedText = AndroidUi.small(this, "Microphone: checking…");
        AndroidUi.stableLine(this, routedText, 30);
        micLevelText = AndroidUi.small(this, "Microphone signal: not recording");
        AndroidUi.stableLine(this, micLevelText, 28);
        micLevelBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        micLevelBar.setMax(1000); micLevelBar.setProgress(0);
        micLevelBar.setContentDescription("Live microphone input level");
        transferText = AndroidUi.small(this, "Server: nothing waiting");
        AndroidUi.stableLine(this, transferText, 30);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000); progressBar.setProgress(0);
        progressBar.setContentDescription("Server synchronization progress");
        primaryButton = AndroidUi.primaryButton(this, "Start recording");
        primaryButton.setContentDescription("Primary recording action");
        primaryButton.setOnClickListener(v -> primaryAction());
        secondaryButton = AndroidUi.secondaryButton(this, "Player and files");
        secondaryButton.setOnClickListener(v -> openPlayer());

        statusCard.addView(statusTitle);
        statusCard.addView(durationText);
        statusCard.addView(statusDetail);
        statusCard.addView(currentText);
        statusCard.addView(primaryButton, fixedButtonParams(58));
        statusCard.addView(secondaryButton, fixedButtonParams(50));
        statusCard.addView(routedText);
        statusCard.addView(micLevelText);
        statusCard.addView(micLevelBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 10)));
        statusCard.addView(transferText);
        statusCard.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 12)));
        content.addView(statusCard);

        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.HORIZONTAL);
        folderButton = AndroidUi.secondaryButton(this, "Folder: Default");
        folderButton.setSingleLine(true);
        folderButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        folderButton.setOnClickListener(v -> showFolderPicker());
        inputButton = AndroidUi.secondaryButton(this, "Microphone: checking…");
        inputButton.setSingleLine(true);
        inputButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
        inputButton.setOnClickListener(v -> showInputPicker());
        setup.addView(folderButton, weightedButtonParams());
        setup.addView(inputButton, weightedButtonParams());
        content.addView(setup);

        moreButton = AndroidUi.toolbarButton(this, "More");
        moreButton.setOnClickListener(v -> showMoreMenu());
        content.addView(moreButton, fixedButtonParams(48));

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams fixedButtonParams(int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, heightDp));
        params.setMargins(0, AndroidUi.dp(this, 4), 0, AndroidUi.dp(this, 4));
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, AndroidUi.dp(this, 52), 1f);
        params.setMargins(AndroidUi.dp(this, 3), AndroidUi.dp(this, 4),
                AndroidUi.dp(this, 3), AndroidUi.dp(this, 4));
        return params;
    }

    private void refreshFolders() {
        RecordingService value = service;
        if (value == null || !folderRefreshRunning.compareAndSet(false, true)) return;
        uiWorker.execute(() -> {
            List<ReliableSessionStore.Folder> loaded = new ArrayList<>();
            try { loaded.addAll(value.listFolders()); }
            catch (Exception ignored) {}
            if (loaded.isEmpty()) loaded.add(new ReliableSessionStore.Folder(
                    "default", "Default", 0L));
            runOnUiThread(() -> {
                folderRefreshRunning.set(false);
                folders.clear(); folders.addAll(loaded);
                boolean selectedExists = false;
                for (ReliableSessionStore.Folder folder : folders) {
                    if (folder.id.equals(selectedFolderId)) {
                        selectedFolderName = folder.name;
                        selectedExists = true;
                        break;
                    }
                }
                if (!selectedExists) {
                    selectedFolderId = folders.get(0).id;
                    selectedFolderName = folders.get(0).name;
                }
                updateSetupButtons();
            });
        });
    }

    private void showFolderPicker() {
        if (snapshot.recording || snapshot.openSession != null) {
            new AlertDialog.Builder(this).setTitle("Folder locked for this recording")
                    .setMessage("Pause or finish the current recording before changing its folder.")
                    .setPositiveButton("Back", null).show();
            return;
        }
        String[] labels = new String[folders.size() + 1];
        for (int i = 0; i < folders.size(); i++) labels[i] = folders.get(i).name;
        labels[folders.size()] = "Create new folder…";
        new AlertDialog.Builder(this).setTitle("Recording folder")
                .setItems(labels, (dialog, which) -> {
                    if (which == folders.size()) {
                        showCreateFolderDialog();
                    } else {
                        ReliableSessionStore.Folder folder = folders.get(which);
                        selectedFolderId = folder.id;
                        selectedFolderName = folder.name;
                        updateSetupButtons();
                        diag(PhoneDiagnostics.INFO, "folder.selected", snapshot.currentSessionId,
                                "A recording folder was selected",
                                PhoneDiagnostics.fields("folder_id", folder.id,
                                        "folder_name", folder.name));
                    }
                }).setNegativeButton("Back", null).show();
    }

    private void showCreateFolderDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true); input.setHint("Folder name");
        int pad = AndroidUi.dp(this, 20);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Create recording folder").setView(container)
                .setNegativeButton("Back", null).setPositiveButton("Create", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) { input.setError("Enter a folder name"); return; }
                    RecordingService value = service;
                    if (value == null) { input.setError("Recording service is not ready"); return; }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    uiWorker.execute(() -> {
                        try {
                            ReliableSessionStore.Folder created = value.createFolder(name);
                            runOnUiThread(() -> {
                                selectedFolderId = created.id;
                                selectedFolderName = created.name;
                                dialog.dismiss(); refreshFolders(); updateSetupButtons();
                            });
                        } catch (Exception failure) {
                            runOnUiThread(() -> {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                input.setError(PhoneDiagnostics.exactFailure(
                                        "Creating folder", failure));
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
        uiWorker.execute(() -> {
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
        inputs.clear(); inputs.addAll(loaded);
        if (inputs.isEmpty()) {
            selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
            inputButton.setText("Microphone: none available");
            inputButton.setEnabled(true);
            render(snapshot);
            return;
        }
        int selected = 0;
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).getDeviceId() == preserve) { selected = i; break; }
        }
        selectedDeviceId = inputs.get(selected).getDeviceId();
        inputButton.setEnabled(true);
        updateSetupButtons();
        render(snapshot);
    }

    private void showInputPicker() {
        if (snapshot.recording) {
            new AlertDialog.Builder(this).setTitle("Microphone locked while recording")
                    .setMessage("Pause the recording before selecting another microphone.")
                    .setPositiveButton("Back", null).show();
            return;
        }
        String[] labels = new String[inputs.size() + 1];
        for (int i = 0; i < inputs.size(); i++) labels[i] = inputs.get(i).getLabel();
        labels[inputs.size()] = "Refresh microphone list";
        new AlertDialog.Builder(this).setTitle("Microphone")
                .setItems(labels, (dialog, which) -> {
                    if (which == inputs.size()) { refreshInputs(); return; }
                    AudioInputOption selected = inputs.get(which);
                    selectedDeviceId = selected.getDeviceId();
                    updateSetupButtons();
                    diag(PhoneDiagnostics.INFO, "microphone.selected", snapshot.currentSessionId,
                            "A microphone was selected",
                            PhoneDiagnostics.fields("device_id", selected.getDeviceId(),
                                    "device_type", selected.getDeviceType(),
                                    "label", selected.getLabel()));
                }).setNegativeButton("Back", null).show();
    }

    private void updateSetupButtons() {
        if (folderButton != null) folderButton.setText("Folder: " + selectedFolderName);
        if (inputButton != null) inputButton.setText("Microphone: " + selectedInputLabel());
    }

    private String selectedInputLabel() {
        for (AudioInputOption input : inputs) {
            if (input.getDeviceId() == selectedDeviceId) return input.getLabel();
        }
        return inputs.isEmpty() ? "none available" : inputs.get(0).getLabel();
    }

    private void primaryAction() {

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
            sendAction(RecordingService.ACTION_RESUME, snapshot.openSession.sessionId, true);
            return;
        }
        if (RecordingService.ACTION_PAUSE.equals(resolvedAction)) {
            sendAction(RecordingService.ACTION_PAUSE, snapshot.currentSessionId, false);
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestPermissionsIfNeeded();
            return;
        }
        if (inputs.isEmpty() || selectedDeviceId == AudioInputOption.DEFAULT_DEVICE_ID) {
            diag(PhoneDiagnostics.WARN, "microphone.start_blocked", snapshot.currentSessionId,
                    "Recording start was blocked because no real microphone was selected",
                    PhoneDiagnostics.fields("available_count", inputs.size(),
                            "selected_device_id", selectedDeviceId));
            new AlertDialog.Builder(this)
                    .setTitle("No microphone available")
                    .setMessage("Connect a real microphone, then press Refresh connected inputs. The app no longer uses synthetic or historical microphone entries.")
                    .setPositiveButton("OK", null)
                    .show();
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
        sendAction(RecordingService.ACTION_START, null, true);
    }

    private void finishCurrent() {
        ReliableSessionManifest open = snapshot.openSession;
        if (open == null && !snapshot.recording) return;
        String sessionId = open == null ? snapshot.currentSessionId : open.sessionId;
        new AlertDialog.Builder(this).setTitle("Finish this recording?")
                .setMessage("Finish closes this recording permanently. Audio already captured stays safe and synchronization continues in the background.")
                .setNegativeButton("Back", null)
                .setPositiveButton("Finish recording", (dialog, which) -> {
                    diag(PhoneDiagnostics.INFO, "ui.main.finish_pressed", sessionId,
                            "Finish recording was confirmed",
                            PhoneDiagnostics.fields("state", snapshot.state,
                                    "recording", snapshot.recording,
                                    "paused", snapshot.paused));
                    sendAction(RecordingService.ACTION_FINISH, sessionId, false);
                }).show();
    }

    private void showRecoveryDialog(ReliableSessionManifest interrupted) {
        if (interrupted == null || interrupted.paused
                || !"INTERRUPTED".equals(interrupted.state) || isFinishing()) return;
        promptedSessionId = interrupted.sessionId;
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this).setTitle("More")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) openPlayer();
                    else if (which == 1) showStatusDetails();
                    else if (which == 2) refreshInputs();
                    else if (which == 3) sendAction(RecordingService.ACTION_RETRY, null, false);
                    else if (which == 4) copySupportSummary();
                    else if (which == 5) exportFullDiagnostics();
                    else showAbout();
                }).setNegativeButton("Back", null).show();
    }

    private void openPlayer() {
        startActivity(new Intent(this, AudioLibraryActivity.class));
    }

    private void showStatusDetails() {
        String message = "State: " + snapshot.state
                + "\n\n" + snapshot.explanation
                + "\n\nMicrophone: " + snapshot.routedInput
                + "\nFolder: " + selectedFolderName
                + "\nPending server data: "
                + RecordingUi.formatBytes(snapshot.uploadPendingBytes);
        new AlertDialog.Builder(this).setTitle("Current status")
                .setMessage(message).setPositiveButton("Back", null).show();
    }

    private void copySupportSummary() {
        String report;
        RecordingService value = service;
        if (value == null) {
            report = "Voice Button support summary\napp_version="
                    + BuildConfig.VERSION_NAME + "\nservice=not_connected\nstate="
                    + snapshot.state + "\nstatus=" + snapshot.explanation + "\n";
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
            new AlertDialog.Builder(this).setTitle("Support summary copied")
                    .setMessage(report.length() + " characters are on the clipboard and ready to paste.")
                    .setPositiveButton("OK", null).show();
        } catch (Exception failure) {
            new AlertDialog.Builder(this).setTitle("Copy failed")
                    .setMessage(PhoneDiagnostics.exactFailure(
                            "Copying the support summary", failure))
                    .setPositiveButton("Back", null).show();
        }
    }

    private void exportFullDiagnostics() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE,
                "voicebutton-diagnostics-" + BuildConfig.VERSION_NAME + ".txt");
        startActivityForResult(intent, DEBUG_EXPORT_REQUEST);
    }

    private void writeFullDiagnostics(Uri destination) {
        RecordingService value = service;
        uiWorker.execute(() -> {
            try {
                String report = value == null ? "Recording service is not connected.\n"
                        + "Support summary:\n" + snapshot.state + "\n" + snapshot.explanation
                        : value.buildDebugReport();
                try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new java.io.IOException("Destination could not be opened");
                    output.write(report.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Diagnostics exported")
                        .setMessage("The full diagnostic ledger was written to the selected file.")
                        .setPositiveButton("OK", null).show());
            } catch (Exception failure) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Export failed")
                        .setMessage(PhoneDiagnostics.exactFailure(
                                "Exporting diagnostics", failure))
                        .setPositiveButton("Back", null).show());
            }
        });
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("Voice Button")
                .setMessage("Version " + BuildConfig.VERSION_NAME
                        + "\nRecording and synchronization continue in the foreground after this screen closes."
                        + "\n\nOverview diagnostics are intentionally hidden. Use Current status details, Copy support summary, or Export full diagnostics when needed.")
                .setPositiveButton("Back", null).show();
    }

    private void sendAction(String action, String sessionId, boolean foreground) {

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
        if (open != null) {
            selectedFolderId = open.folderId;
            selectedFolderName = open.folderName;
        }
        String structureKey = MainScreenText.structureKey(snapshot.state,
                snapshot.recording, snapshot.paused,
                snapshot.recordingErrorActive, snapshot.recordingErrorAlarmAudible,
                open != null, selectedFolderId, selectedDeviceId,
                snapshot.sessions.size());
        if (!structureKey.equals(lastStructureKey)) {
            lastStructureKey = structureKey;
            int color = RecordingUi.stateColor(snapshot.state);
            setTextIfChanged(statusTitle, MainScreenText.stateTitle(snapshot.state,
                    snapshot.recording, snapshot.paused, snapshot.recordingErrorActive));
            statusTitle.setTextColor(color);
            statusCard.setBackground(AndroidUi.round(
                    tint(color), color, AndroidUi.dp(this, 16)));
            setTextIfChanged(statusDetail, MainScreenText.stateSummary(snapshot.state,
                    snapshot.recording, snapshot.paused,
                    snapshot.recordingErrorActive, open != null));
            if (open != null && open.paused) primaryButton.setText("Resume recording");
            else if (snapshot.recording) primaryButton.setText("Pause recording");
            else if (snapshot.recordingErrorActive) primaryButton.setText("Pause recovery");
            else if (snapshot.interrupted != null) primaryButton.setText("Recover recording");
            else primaryButton.setText("Start recording");
            primaryButton.setEnabled(PrimaryActionPolicy.isEnabled(
                    snapshot.recording, snapshot.state, open != null,
                    open != null && open.paused, snapshot.interrupted != null,
                    !inputs.isEmpty()));
            configureSecondaryAction(open);
            updateSetupButtons();
        }
        setTextIfChanged(durationText, RecordingUi.formatDuration(snapshot.durationMs));
        setTextIfChanged(currentText, MainScreenText.localProtection(
                open == null ? selectedFolderName : open.folderName, open != null));
        String microphone = snapshot.recording ? snapshot.routedInput : selectedInputLabel();
        setTextIfChanged(routedText, "Microphone: " + microphone);
        setTextIfChanged(micLevelText, MainScreenText.microphone(
                snapshot.recording, snapshot.inputSignalDetected));
        int level = snapshot.recording ? snapshot.inputLevelPermille : 0;
        if (Math.abs(micLevelBar.getProgress() - level) >= 8) micLevelBar.setProgress(level);
        micLevelBar.setContentDescription(micLevelText.getText());
        setTextIfChanged(transferText, MainScreenText.transfer(snapshot.recording,
                snapshot.uploadTotalBytes, snapshot.uploadPendingBytes,
                snapshot.uploadProgressPermille));
        if (progressBar.getProgress() != snapshot.uploadProgressPermille) {
            progressBar.setProgress(snapshot.uploadProgressPermille);
        }
        progressBar.setContentDescription(transferText.getText());
        if (snapshot.interrupted != null && !snapshot.recording
                && !snapshot.recordingErrorActive
                && !"RECOVERING".equals(snapshot.state)
                && !snapshot.interrupted.sessionId.equals(promptedSessionId)
                && hasPermission(Manifest.permission.RECORD_AUDIO)
                && !inputs.isEmpty()) showRecoveryDialog(snapshot.interrupted);
        if (snapshot.interrupted == null) promptedSessionId = "";
    }

    private void configureSecondaryAction(ReliableSessionManifest open) {
        if (snapshot.recordingErrorActive && snapshot.recordingErrorAlarmAudible) {
            secondaryButton.setText("Silence alarm");
            secondaryButton.setTextColor(AndroidUi.RED);
            secondaryButton.setBackground(AndroidUi.round(Color.WHITE,
                    AndroidUi.RED, AndroidUi.dp(this, 12)));
            secondaryButton.setOnClickListener(v -> sendAction(
                    RecordingService.ACTION_SILENCE_ALARM,
                    snapshot.currentSessionId, false));
        } else if (snapshot.recording || open != null) {
            secondaryButton.setText("Finish recording");
            secondaryButton.setTextColor(AndroidUi.RED);
            secondaryButton.setBackground(AndroidUi.round(Color.WHITE,
                    AndroidUi.RED, AndroidUi.dp(this, 12)));
            secondaryButton.setOnClickListener(v -> finishCurrent());
        } else {
            secondaryButton.setText("Player and files");
            secondaryButton.setTextColor(AndroidUi.BLUE);
            secondaryButton.setBackground(AndroidUi.round(Color.WHITE,
                    Color.rgb(201, 211, 224), AndroidUi.dp(this, 12)));
            secondaryButton.setOnClickListener(v -> openPlayer());
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

    private static GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable value = new GradientDrawable();
        value.setColor(fill);
        value.setStroke(1, stroke);
        value.setCornerRadius(radius);
        return value;
    }
}
