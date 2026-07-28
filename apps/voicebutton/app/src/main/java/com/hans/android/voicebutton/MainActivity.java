package com.hans.android.voicebutton;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
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

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.common_ui.AndroidUi;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1001;

    private final List<AudioInputOption> inputs = new ArrayList<>();
    private final List<ReliableSessionStore.Folder> folders = new ArrayList<>();
    private LinearLayout statusCard;
    private TextView statusTitle;
    private TextView statusDetail;
    private ProgressBar progressBar;
    private Spinner folderSpinner;
    private Spinner inputSpinner;
    private TextView routedText;
    private TextView durationText;
    private TextView storageText;
    private TextView currentText;
    private Button primaryButton;
    private Button finishButton;
    private TextView finishReason;
    private Button recordingsButton;

    private int selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
    private String selectedFolderId = "default";
    private String selectedFolderName = "Default";
    private boolean updatingFolders;
    private RecordingService service;
    private boolean bound;
    private RecordingService.Snapshot snapshot = RecordingService.Snapshot.initial();
    private String promptedSessionId = "";
    private PhoneDiagnostics diagnostics;

    private final RecordingService.StatusListener statusListener = value ->
            runOnUiThread(() -> render(value));

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
        diagnostics = PhoneDiagnostics.initialize(this,
                BuildConfig.VOICE_BASE_URL, BuildConfig.VERSION_NAME);
        diag(PhoneDiagnostics.INFO, "ui.main.create", null,
                "MainActivity onCreate", PhoneDiagnostics.fields("has_saved_state", savedInstanceState != null));
        buildScreen();
        refreshInputs();
        requestPermissionsIfNeeded();
    }

    @Override protected void onStart() {
        super.onStart();
        diag(PhoneDiagnostics.INFO, "ui.main.start", null,
                "MainActivity onStart", PhoneDiagnostics.fields());
        refreshInputs();
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

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(AndroidUi.BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUi.dp(this, 18);
        content.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(AndroidUi.title(this, "Reliable Voice Recorder"));
        content.addView(AndroidUi.subtitle(this,
                "Record, pause, resume, and synchronize compressed audio in the background."));

        statusCard = AndroidUi.card(this);
        statusTitle = AndroidUi.text(this, "READY", 18, true, AndroidUi.GREEN);
        statusDetail = AndroidUi.body(this, "Choose an input and start recording");
        statusCard.addView(statusTitle);
        statusCard.addView(statusDetail);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        progressBar.setContentDescription("Operation in progress");
        statusCard.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 8)));
        content.addView(statusCard);

        LinearLayout folderCard = AndroidUi.card(this);
        folderCard.addView(AndroidUi.section(this, "Folder"));
        folderSpinner = new Spinner(this);
        folderSpinner.setMinimumHeight(AndroidUi.dp(this, 52));
        folderSpinner.setContentDescription("Choose recording folder or create a new folder");
        folderCard.addView(folderSpinner);
        folderCard.addView(AndroidUi.small(this,
                "Audio chunks and transcript chunks are stored in the selected folder on this phone and on Jetson."));
        content.addView(folderCard);

        LinearLayout inputCard = AndroidUi.card(this);
        inputCard.addView(AndroidUi.section(this, "Microphone"));
        inputSpinner = new Spinner(this);
        inputSpinner.setMinimumHeight(AndroidUi.dp(this, 52));
        inputSpinner.setContentDescription("Choose microphone input");
        inputCard.addView(inputSpinner);
        routedText = AndroidUi.body(this, "Actual route: not recording");
        inputCard.addView(routedText);
        Button refresh = AndroidUi.button(this, "Refresh connected inputs");
        refresh.setMinHeight(AndroidUi.dp(this, 52));
        refresh.setOnClickListener(v -> {
            diag(PhoneDiagnostics.INFO, "microphone.refresh_pressed", snapshot.currentSessionId,
                    "Refresh connected inputs was pressed", PhoneDiagnostics.fields());
            refreshInputs();
        });
        inputCard.addView(refresh);
        content.addView(inputCard);

        LinearLayout protection = AndroidUi.card(this);
        protection.addView(AndroidUi.section(this, "Current recording"));
        durationText = AndroidUi.body(this, "Duration: 00:00:00");
        storageText = AndroidUi.body(this, "All local audio: 0 B");
        currentText = AndroidUi.small(this, "No recording is open.");
        protection.addView(durationText);
        protection.addView(storageText);
        protection.addView(currentText);
        content.addView(protection);

        primaryButton = AndroidUi.button(this, "Start recording");
        primaryButton.setMinHeight(AndroidUi.dp(this, 62));
        primaryButton.setTextSize(18);
        primaryButton.setContentDescription("Primary recording action");
        primaryButton.setOnClickListener(v -> primaryAction());
        content.addView(primaryButton);

        finishButton = AndroidUi.button(this, "Finish recording");
        finishButton.setMinHeight(AndroidUi.dp(this, 54));
        finishButton.setTextColor(AndroidUi.RED);
        finishButton.setBackground(AndroidUi.round(Color.WHITE, AndroidUi.RED, AndroidUi.dp(this, 14)));
        finishButton.setOnClickListener(v -> finishCurrent());
        content.addView(finishButton);
        finishReason = AndroidUi.small(this,
                "Finish permanently closes the current recording. Pause keeps it open for Resume.");
        content.addView(finishReason);

        recordingsButton = AndroidUi.button(this, "Open recordings");
        recordingsButton.setMinHeight(AndroidUi.dp(this, 54));
        recordingsButton.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class)));
        content.addView(recordingsButton);

        Button retry = AndroidUi.button(this, "Synchronize with server now");
        retry.setMinHeight(AndroidUi.dp(this, 50));
        retry.setOnClickListener(v -> sendAction(RecordingService.ACTION_RETRY, null, false));
        content.addView(retry);

        setContentView(scroll);
    }

    private void refreshFolders() {
        if (folderSpinner == null) return;
        updatingFolders = true;
        folders.clear();
        if (service != null) folders.addAll(service.listFolders());
        if (folders.isEmpty()) folders.add(new ReliableSessionStore.Folder("default", "Default", 0L));
        List<String> labels = new ArrayList<>();
        int selectedPosition = 0;
        for (int i = 0; i < folders.size(); i++) {
            ReliableSessionStore.Folder folder = folders.get(i);
            labels.add(folder.name);
            if (folder.id.equals(selectedFolderId)) selectedPosition = i;
        }
        labels.add("<Create new>");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        folderSpinner.setAdapter(adapter);
        folderSpinner.setSelection(selectedPosition);
        ReliableSessionStore.Folder selected = folders.get(selectedPosition);
        selectedFolderId = selected.id;
        selectedFolderName = selected.name;
        folderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingFolders) return;
                if (position == folders.size()) {
                    int restore = 0;
                    for (int i = 0; i < folders.size(); i++) {
                        if (folders.get(i).id.equals(selectedFolderId)) restore = i;
                    }
                    updatingFolders = true;
                    folderSpinner.setSelection(restore);
                    updatingFolders = false;
                    showCreateFolderDialog();
                    return;
                }
                if (position >= 0 && position < folders.size()) {
                    ReliableSessionStore.Folder folder = folders.get(position);
                    selectedFolderId = folder.id;
                    selectedFolderName = folder.name;
                    diag(PhoneDiagnostics.INFO, "folder.selected", snapshot.currentSessionId,
                            "A recording folder was selected",
                            PhoneDiagnostics.fields("folder_id", folder.id, "folder_name", folder.name));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updatingFolders = false;
    }

    private void showCreateFolderDialog() {
        if (snapshot.recording || snapshot.openSession != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Finish the current recording first")
                    .setMessage("A recording keeps its original folder. Finish it before creating or switching folders.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Folder name");
        int pad = AndroidUi.dp(this, 20);
        LinearLayout container = new LinearLayout(this);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Create recording folder")
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("Enter a folder name");
                        return;
                    }
                    if (service == null) {
                        input.setError("Recording service is not ready");
                        return;
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    new Thread(() -> {
                        try {
                            ReliableSessionStore.Folder created = service.createFolder(name);
                            runOnUiThread(() -> {
                                selectedFolderId = created.id;
                                selectedFolderName = created.name;
                                refreshFolders();
                                dialog.dismiss();
                            });
                        } catch (Exception failure) {
                            runOnUiThread(() -> {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                input.setError(PhoneDiagnostics.exactFailure("Creating folder", failure));
                            });
                        }
                    }, "voicebutton-create-folder").start();
                }));
        dialog.show();
    }

    private void refreshInputs() {
        int preserve = selectedDeviceId;
        long refreshStarted = android.os.SystemClock.elapsedRealtime();
        inputs.clear();
        inputs.addAll(AudioInputCatalog.list(this));
        org.json.JSONArray available = new org.json.JSONArray();
        for (AudioInputOption option : inputs) {
            org.json.JSONObject item = new org.json.JSONObject();
            try {
                item.put("device_id", option.getDeviceId());
                item.put("device_type", option.getDeviceType());
                item.put("label", option.getLabel());
                item.put("category", option.getCategory().name());
            } catch (Exception ignored) {}
            available.put(item);
        }
        org.json.JSONObject rawDiagnostics = AudioInputCatalog.diagnosticSnapshot(this);
        diag(PhoneDiagnostics.INFO, "microphone.refresh_result", snapshot.currentSessionId,
                "Currently available physical microphone list was refreshed",
                PhoneDiagnostics.fields("available_count", inputs.size(),
                        "devices", available,
                        "android_audio_diagnostics", rawDiagnostics,
                        "refresh_duration_ms", Math.max(0L,
                                android.os.SystemClock.elapsedRealtime() - refreshStarted),
                        "bluetooth_permission", Build.VERSION.SDK_INT < 31
                                || hasPermission(Manifest.permission.BLUETOOTH_CONNECT),
                        "record_permission", hasPermission(Manifest.permission.RECORD_AUDIO)));
        ArrayAdapter<AudioInputOption> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, inputs);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        inputSpinner.setAdapter(adapter);
        if (inputs.isEmpty()) {
            selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
            inputSpinner.setEnabled(false);
            inputSpinner.setContentDescription("No real microphone is currently available");
            routedText.setText("Available input: none. Connect a microphone and press Refresh connected inputs.");
            if (snapshot != null) render(snapshot);
            return;
        }
        int selected = 0;
        boolean preserved = false;
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).getDeviceId() == preserve) {
                selected = i;
                preserved = true;
                break;
            }
        }
        inputSpinner.setSelection(selected);
        selectedDeviceId = inputs.get(selected).getDeviceId();
        inputSpinner.setEnabled(!snapshot.recording);
        inputSpinner.setContentDescription("Choose a currently available microphone input");
        inputSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < inputs.size()) {
                    AudioInputOption selected = inputs.get(position);
                    selectedDeviceId = selected.getDeviceId();
                    diag(PhoneDiagnostics.INFO, "microphone.selected", snapshot.currentSessionId,
                            "A microphone was selected",
                            PhoneDiagnostics.fields("device_id", selected.getDeviceId(),
                                    "device_type", selected.getDeviceType(),
                                    "label", selected.getLabel(),
                                    "category", selected.getCategory().name()));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                selectedDeviceId = AudioInputOption.DEFAULT_DEVICE_ID;
            }
        });
        if (!preserved && preserve != AudioInputOption.DEFAULT_DEVICE_ID) {
            routedText.setText("The previous microphone disconnected. Select one of the currently available inputs.");
        }
        if (snapshot != null) render(snapshot);
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
        diag(PhoneDiagnostics.INFO, "ui.main.finish_pressed", sessionId,
                "Finish recording button was pressed and accepted immediately",
                PhoneDiagnostics.fields("state", snapshot.state,
                        "recording", snapshot.recording,
                        "paused", snapshot.paused));
        sendAction(RecordingService.ACTION_FINISH, sessionId, false);
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
        int color = RecordingUi.stateColor(snapshot.state);
        statusTitle.setText(snapshot.state);
        statusTitle.setTextColor(color);
        statusDetail.setText(snapshot.explanation);
        statusCard.setBackground(round(tint(color), color, AndroidUi.dp(this, 16)));
        boolean busy = isBusyState(snapshot.state);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        inputSpinner.setEnabled(!snapshot.recording && !inputs.isEmpty());
        if (snapshot.openSession != null) {
            selectedFolderId = snapshot.openSession.folderId;
            selectedFolderName = snapshot.openSession.folderName;
        }
        if (folderSpinner != null) {
            folderSpinner.setEnabled(!snapshot.recording && snapshot.openSession == null);
            int folderPosition = -1;
            for (int i = 0; i < folders.size(); i++) {
                if (folders.get(i).id.equals(selectedFolderId)) folderPosition = i;
            }
            if (folderPosition >= 0 && folderSpinner.getSelectedItemPosition() != folderPosition) {
                updatingFolders = true;
                folderSpinner.setSelection(folderPosition);
                updatingFolders = false;
            }
        }
        if (inputs.isEmpty() && !snapshot.recording) {
            routedText.setText("Available input: none. Connect a microphone and press Refresh connected inputs.");
        } else {
            routedText.setText("Actual route: " + snapshot.routedInput);
        }
        durationText.setText("Duration: " + RecordingUi.formatDuration(snapshot.durationMs));
        storageText.setText("All local audio, text, and metadata: "
                + RecordingUi.formatBytes(snapshot.localBytes));

        ReliableSessionManifest open = snapshot.openSession;
        if (open == null) currentText.setText("No recording is open.");
        else currentText.setText(open.folderName + " · " + RecordingUi.humanState(open)
                + " · local chunks " + open.segments.size()
                + " · server durable " + open.durableRemoteChunkCount()
                + " · pending " + RecordingUi.formatBytes(open.pendingRemoteBytes())
                + " · text chunks " + open.transcriptChunkCount());

        if (snapshot.openSession != null && snapshot.openSession.paused) {
            primaryButton.setText("Resume recording");
        } else if (snapshot.recording) primaryButton.setText("Pause recording");
        else if (snapshot.interrupted != null) primaryButton.setText("Resolve interrupted recording");
        else primaryButton.setText("Start recording");

        boolean openRecording = snapshot.recording || open != null;
        primaryButton.setEnabled(PrimaryActionPolicy.isEnabled(
                snapshot.recording,
                snapshot.state,
                open != null,
                open != null && open.paused,
                snapshot.interrupted != null,
                !inputs.isEmpty()));
        finishButton.setEnabled(snapshot.recording
                || (open != null && !"CLEANING".equals(snapshot.state)
                && !"PREPARING".equals(snapshot.state)
                && !"PAUSING".equals(snapshot.state)));
        recordingsButton.setEnabled(!"CLEANING".equals(snapshot.state));
        finishButton.setVisibility(openRecording ? View.VISIBLE : View.GONE);
        finishReason.setVisibility(openRecording ? View.VISIBLE : View.GONE);
        recordingsButton.setText("Open recordings (" + snapshot.sessions.size() + ")");

        if (snapshot.interrupted != null
                && !snapshot.recording
                && !snapshot.interrupted.sessionId.equals(promptedSessionId)
                && hasPermission(Manifest.permission.RECORD_AUDIO)
                && !inputs.isEmpty()) {
            showRecoveryDialog(snapshot.interrupted);
        }
        if (snapshot.interrupted == null) promptedSessionId = "";
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

    @Override public void onBackPressed() {
        if (!AppClosePolicy.requiresWarning(snapshot.recording, snapshot.state)) {
            closeAppNow("back_without_critical_work");
            return;
        }
        String message;
        if (snapshot.recording || "PREPARING".equals(snapshot.state)
                || "PAUSING".equals(snapshot.state)) {
            message = "Recording will stop immediately. Complete synchronized MP3 frames remain recoverable, and the recording will be marked interrupted for the next app launch.";
        } else if ("FINISHING".equals(snapshot.state)
                || "COMPRESSING".equals(snapshot.state)) {
            message = "Final MP3 assembly will stop immediately. Temporary output may be discarded, and the final MP3 will be rebuilt from durable segments the next time you open the app.";
        } else {
            message = "The current operation will stop immediately. Completed local data remains, but unfinished work will resume only after you open the app again.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Close Voice Button now?")
                .setMessage(message)
                .setNegativeButton("Keep app open", (dialog, which) ->
                        diag(PhoneDiagnostics.INFO, "ui.close_cancelled", snapshot.currentSessionId,
                                "User kept Voice Button open",
                                PhoneDiagnostics.fields("state", snapshot.state)))
                .setPositiveButton("Close app now", (dialog, which) ->
                        closeAppNow("back_confirmed_during_critical_work"))
                .show();
    }

    private void closeAppNow(String reason) {
        diag(PhoneDiagnostics.WARN, "ui.close_confirmed", snapshot.currentSessionId,
                "Voice Button close was confirmed",
                PhoneDiagnostics.fields("reason", reason,
                        "state", snapshot.state,
                        "recording", snapshot.recording,
                        "paused", snapshot.paused));
        Intent intent = new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_EXIT);
        startService(intent);
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
