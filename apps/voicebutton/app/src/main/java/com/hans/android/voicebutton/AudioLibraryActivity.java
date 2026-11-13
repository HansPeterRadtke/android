package com.hans.android.voicebutton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import androidx.documentfile.provider.DocumentFile;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.common_ui.AndroidUi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("SetTextI18n")
public final class AudioLibraryActivity extends Activity {
    private static final int REQUEST_ROOT = 7301;
    private static final int REQUEST_FILE = 7302;
    private static final int REQUEST_MOVE = 7303;
    private static final String PREFS = "voicebutton_gui_state";
    private static final String ROOT_URI = "library_root_uri";
    private static final String MODE = "library_recordings_mode";
    private static final String APP_FOLDER_ID = "library_app_folder_id";
    private static final String APP_FOLDER_NAME = "library_app_folder_name";
    private static final String DIRECTORY_STACK = "library_directory_stack";
    private static final String DIRECTORY_NAMES = "library_directory_names";
    private static final String CURRENT_DIRECTORY = "library_current_directory";
    private static final String CURRENT_DIRECTORY_NAME = "library_current_directory_name";
    private static final String CACHE = "library_visible_cache";
    private static final String SCROLL_FIRST = "library_scroll_first";
    private static final String SCROLL_TOP = "library_scroll_top";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackPreparation =
            Executors.newSingleThreadExecutor();
    private final LibraryAdapter adapter = new LibraryAdapter();
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final AtomicInteger playbackGeneration = new AtomicInteger();
    private final ArrayList<Uri> directoryStack = new ArrayList<>();
    private final ArrayList<String> directoryNameStack = new ArrayList<>();

    private ReliableSessionStore store;
    private SharedPreferences preferences;
    private boolean recordingsMode = true;
    private ReliableSessionStore.Folder appFolderFilter;
    private DocumentFile currentDirectory;
    private String currentDirectoryName = "Phone files";
    private int restoreScrollFirst;
    private int restoreScrollTop;
    private boolean firstResume = true;
    private DocumentFile pendingMove;
    private TextView pathText;
    private TextView stateText;
    private android.widget.ProgressBar fileTaskProgress;
    private Button recordingsModeButton;
    private Button filesModeButton;
    private Button upButton;
    private android.window.OnBackInvokedCallback backCallback;
    private Button primaryLibraryAction;
    private ListView list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        recordingsMode = preferences.getBoolean(MODE, true);
        // A fresh Library launch always answers the root recordings question.
        // Do not restore a previously visited leaf folder and make the hierarchy
        // appear to have disappeared after an app/process restart.
        appFolderFilter = null;
        preferences.edit().remove(APP_FOLDER_ID).remove(APP_FOLDER_NAME).apply();
        restoreScrollFirst = preferences.getInt(SCROLL_FIRST, 0);
        restoreScrollTop = preferences.getInt(SCROLL_TOP, 0);
        buildScreen();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback);
        }
        restoreCachedItems();
        if (recordingsMode) showRecordings(); else restorePhoneLocation();
    }

    @Override protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        if (recordingsMode) showRecordings(); else refreshFiles();
    }

    @Override protected void onPause() {
        saveLibraryState();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        playbackGeneration.incrementAndGet();
        playbackPreparation.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUi.dp(this, 12), AndroidUi.dp(this, 8),
                AndroidUi.dp(this, 12), AndroidUi.dp(this, 8));
        root.setBackgroundColor(AndroidUi.BG);

        LinearLayout toolbar = row();
        Button back = VoiceButtonMaterial.toolbarButton(this, "Back");
        back.setId(R.id.voicebutton_library_back);
        back.setOnClickListener(v -> navigateBack());
        Button home = VoiceButtonMaterial.toolbarButton(this, "Home");
        home.setOnClickListener(v -> home());
        toolbar.addView(back, half());
        toolbar.addView(home, half());
        root.addView(toolbar);

        TextView title = AndroidUi.title(this, "Library");
        AndroidUi.stableLine(this, title, 40);
        root.addView(title);
        if (getResources().getConfiguration().screenHeightDp < 520) title.setVisibility(View.GONE);
        stateText = AndroidUi.small(this, "Choose audio to play");
        stateText.setId(R.id.voicebutton_library_status);
        AndroidUi.readableLine(this, stateText, 0, 4);
        stateText.setContentDescription("Library status");
        stateText.setMinHeight(AndroidUi.dp(this, 28));
        root.addView(stateText);
        fileTaskProgress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        fileTaskProgress.setId(R.id.voicebutton_library_operation_progress);
        fileTaskProgress.setMax(1000);
        fileTaskProgress.setVisibility(View.GONE);
        root.addView(fileTaskProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 8)));

        LinearLayout modes = row();
        recordingsModeButton = AndroidUi.modeButton(this, "App recordings", true);
        filesModeButton = AndroidUi.modeButton(this, "Phone files", false);
        recordingsModeButton.setOnClickListener(v -> {
            appFolderFilter = null;
            showRecordings();
        });
        filesModeButton.setOnClickListener(v -> showPhoneRoot());
        modes.addView(recordingsModeButton);
        modes.addView(filesModeButton);
        root.addView(modes);

        LinearLayout navigation = row();
        upButton = VoiceButtonMaterial.toolbarButton(this, "Up");
        upButton.setId(R.id.voicebutton_library_up);
        upButton.setOnClickListener(v -> up());
        pathText = AndroidUi.body(this, "App recording folders");
        pathText.setId(R.id.voicebutton_library_path);
        pathText.setSingleLine(false);
        pathText.setEllipsize(null);
        pathText.setMinHeight(AndroidUi.dp(this, 48));
        upButton.setMinHeight(AndroidUi.dp(this, 48));
        navigation.addView(upButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        navigation.addView(pathText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(navigation);

        list = new ListView(this);
        list.setId(R.id.voicebutton_library_list);
        list.setAdapter(adapter);
        list.setDividerHeight(1);
        list.setOnItemClickListener((parent, view, position, id) ->
                open(adapter.item(position), position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            manage(adapter.item(position));
            return true;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    saveLibraryState();
                }
            }
            @Override public void onScroll(AbsListView view, int first,
                                           int visible, int total) {
                // Scrolling must not serialize JSON and write preferences every
                // animation frame. Persist only after scrolling stops/onPause.
                if (visible > 0) {
                    restoreScrollFirst = first;
                    View firstRow = list.getChildAt(0);
                    restoreScrollTop = firstRow == null ? 0 : firstRow.getTop();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout libraryActions = row();
        primaryLibraryAction = VoiceButtonMaterial.secondaryButton(this, "New folder");
        primaryLibraryAction.setId(R.id.voicebutton_library_primary_action);
        primaryLibraryAction.setOnClickListener(v -> {
            if (recordingsMode) {
                if (store == null) {
                    stateText.setText("Folders are still loading. Retry when ready.");
                } else {
                    createFolder(appFolderFilter == null ? "" : appFolderFilter.id);
                }
            } else {
                chooseRoot();
            }
        });
        libraryActions.addView(primaryLibraryAction, half());
        Button more = VoiceButtonMaterial.toolbarButton(this, "More");
        more.setId(R.id.voicebutton_library_more);
        more.setOnClickListener(v -> showLibraryMenu());
        libraryActions.addView(more, half());
        root.addView(libraryActions);
        updateModeButtons();
        setContentView(root);
    }

    private void showLibraryMenu() {
        String[] actions = recordingsMode
                ? new String[]{"Manage current app folder", "Create root folder",
                        "Choose phone folder", "Open any phone file", "Library help"}
                : new String[]{"Choose a different phone folder", "Open any phone file",
                        "App recordings", "Library help"};
        new MaterialAlertDialogBuilder(this).setTitle("Library options")
                .setItems(actions, (dialog, which) -> {
                    if (recordingsMode) {
                        if (which == 0) manageAppFolders();
                        else if (which == 1) createFolder("");
                        else if (which == 2) chooseRoot();
                        else if (which == 3) openAnyFile();
                        else showLibraryAbout();
                    } else {
                        if (which == 0) chooseRoot();
                        else if (which == 1) openAnyFile();
                        else if (which == 2) {
                            appFolderFilter = null;
                            showRecordings();
                        } else showLibraryAbout();
                    }
                }).setNegativeButton("Back", null).show();
    }

    private void showLibraryAbout() {
        new MaterialAlertDialogBuilder(this).setTitle("Library")
                .setMessage("Tap a recording or folder to open it. Use its Actions button for rename, move or deletion. New folder creates a folder here. Switch between App recordings and Phone files at the top; use the file picker to select another phone storage folder.")
                .setPositiveButton("Back", null).show();
    }

    private void showRecordings() {
        // Any deliberate folder/mode change invalidates an earlier asynchronous
        // playback preparation, preventing a late unexpected Player launch.
        playbackGeneration.incrementAndGet();
        recordingsMode = true;
        stateText.setText("Loading recording folders…");
        currentDirectory = null;
        directoryStack.clear();
        directoryNameStack.clear();
        updateModeButtons();
        saveLibraryState();
        ReliableSessionStore.Folder requested = appFolderFilter;
        int generation = loadGeneration.incrementAndGet();
        worker.execute(() -> {
            ArrayList<LibraryItem> items = new ArrayList<>();
            try {
                if (store == null) {
                    store = ReliableSessionStore.openForBrowsing(this);
                }
                ReliableSessionStore.Folder current = requested;
                if (current != null) {
                    try {
                        current = store.getFolder(current.id);
                    } catch (Exception staleFolder) {
                        // A stale navigation target must never blank the Library.
                        // Returning to root exposes the authoritative hierarchy.
                        current = null;
                    }
                }
                final ReliableSessionStore.Folder currentFolder = current;
                // Background lookups never mutate the active view. Only the
                // generation-checked main-thread callback may navigate.
                List<ReliableSessionManifest> manifests = store.list();
                String parentId = currentFolder == null ? "" : currentFolder.id;
                List<ReliableSessionStore.Folder> visibleFolders =
                        LibraryFolderPolicy.visibleFolders(store.listFolders(),
                                store.childFolders(parentId), currentFolder == null);
                for (ReliableSessionStore.Folder child : visibleFolders) {
                    int directRecordings = 0;
                    for (ReliableSessionManifest manifest : manifests) {
                        if (child.id.equals(manifest.folderId)) {
                            directRecordings++;
                        }
                    }
                    items.add(LibraryItem.appFolder(child,
                            directRecordings, currentFolder == null));
                }
                if (currentFolder != null) {
                    manifests.sort(Comparator.comparingLong(
                            (ReliableSessionManifest value) -> value.createdAt)
                            .reversed());
                    for (ReliableSessionManifest manifest : manifests) {
                        if (!currentFolder.id.equals(manifest.folderId)) continue;
                        File file = RecordingUi.recordingFile(this, manifest);
                        items.add(LibraryItem.recording(manifest, file,
                                file != null && file.isFile()));
                    }
                }
                runOnUiThread(() -> {
                    if (generation != loadGeneration.get() || !recordingsMode) return;
                    appFolderFilter = currentFolder;
                    upButton.setEnabled(currentFolder != null);
                    pathText.setText(currentFolder == null
                            ? "Recordings" : "Recordings/" + currentFolder.path);
                    adapter.replace(items);
                    restoreListPosition();
                    saveVisibleCache(items);
                    saveLibraryState();
                    int childCount = 0;
                    int recordingCount = 0;
                    for (LibraryItem item : items) {
                        if (item.appFolder != null) childCount++;
                        else if (item.recording != null) recordingCount++;
                    }
                    if (items.isEmpty()) {
                        stateText.setText("This folder is empty · use New folder or choose another folder.");
                    } else {
                        stateText.setText(childCount + " folders · "
                                + recordingCount + " recordings");
                    }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration.get() && recordingsMode) {
                        stateText.setText("Could not load recordings: "
                                + failure.getMessage());
                    }
                });
            }
        });
    }

    private void showPhoneRoot() {
        playbackGeneration.incrementAndGet();
        loadGeneration.incrementAndGet(); // Invalidate App recordings callbacks.
        recordingsMode = false; updateModeButtons();
        String raw = preferences.getString(ROOT_URI, "");
        if (raw == null || raw.isEmpty()) {
            currentDirectory = null; directoryStack.clear();
            directoryNameStack.clear(); adapter.replace(new ArrayList<>());
            pathText.setText("No phone folder selected");
            stateText.setText("Choose a phone folder or open any file");
            upButton.setEnabled(false); saveLibraryState();
            return;
        }
        Uri rootUri = Uri.parse(raw);
        currentDirectory = DocumentFile.fromTreeUri(this, rootUri);
        currentDirectoryName = "Phone files";
        directoryStack.clear(); directoryStack.add(rootUri);
        directoryNameStack.clear(); directoryNameStack.add(currentDirectoryName);
        saveLibraryState(); refreshFiles();
    }

    private void restorePhoneLocation() {
        loadGeneration.incrementAndGet();
        recordingsMode = false; updateModeButtons();
        directoryStack.clear(); directoryNameStack.clear();
        try {
            JSONArray uris = new JSONArray(preferences.getString(
                    DIRECTORY_STACK, "[]"));
            JSONArray names = new JSONArray(preferences.getString(
                    DIRECTORY_NAMES, "[]"));
            for (int i = 0; i < uris.length(); i++) {
                directoryStack.add(Uri.parse(uris.getString(i)));
                directoryNameStack.add(i < names.length()
                        ? names.optString(i, "Phone files") : "Phone files");
            }
        } catch (Exception ignored) {}
        String current = preferences.getString(CURRENT_DIRECTORY, "");
        if (current == null || current.isEmpty()) {
            showPhoneRoot(); return;
        }
        Uri uri = Uri.parse(current);
        currentDirectory = DocumentFile.fromSingleUri(this, uri);
        if (currentDirectory == null) currentDirectory =
                DocumentFile.fromTreeUri(this, uri);
        currentDirectoryName = preferences.getString(
                CURRENT_DIRECTORY_NAME, "Phone files");
        if (directoryStack.isEmpty()) {
            directoryStack.add(uri); directoryNameStack.add(currentDirectoryName);
        }
        refreshFiles();
    }

    private void refreshFiles() {
        if (recordingsMode || currentDirectory == null) return;
        Uri directoryUri = currentDirectory.getUri();
        pathText.setText(currentDirectoryName);
        upButton.setEnabled(directoryStack.size() > 1);
        stateText.setText(adapter.getCount() > 0
                ? "Refreshing phone folder…" : "Reading phone folder…");
        saveLibraryState();
        int generation = loadGeneration.incrementAndGet();
        worker.execute(() -> {
            ArrayList<LibraryItem> items = new ArrayList<>();
            try {
                for (FastDocumentDirectory.Entry entry
                        : FastDocumentDirectory.list(this, directoryUri)) {
                    items.add(LibraryItem.document(this, entry, directoryUri));
                }
                items.sort((left, right) -> {
                    if (left.directory != right.directory)
                        return left.directory ? -1 : 1;
                    return left.title.compareToIgnoreCase(right.title);
                });
                runOnUiThread(() -> {
                    if (generation != loadGeneration.get()) return;
                    adapter.replace(items); restoreListPosition();
                    saveVisibleCache(items); saveLibraryState();
                    stateText.setText(items.isEmpty()
                            ? "This phone folder is empty · choose another folder."
                            : items.size() + " items");
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration.get() && !recordingsMode) {
                        stateText.setText(
                                "Could not read phone folder: " + failure.getMessage()
                                + " · choose another folder from the bottom control.");
                    }
                });
            }
        });
    }

    private void open(LibraryItem item, int position) {
        if (item.appFolder != null) {
            appFolderFilter = item.appFolder;
            showRecordings();
            return;
        }
        if (item.directory) {
            playbackGeneration.incrementAndGet();
            currentDirectory = item.document;
            currentDirectoryName = item.title;
            directoryStack.add(item.document.getUri());
            directoryNameStack.add(item.title);
            restoreScrollFirst = 0; restoreScrollTop = 0;
            saveLibraryState(); refreshFiles();
            return;
        }
        if(CapturePlaybackGate.isCapturing()) {
            Toast.makeText(this,"Pause recording before playing audio",Toast.LENGTH_LONG).show();return;
        }
        if (!item.playable) {
            if (item.recording != null) {
                prepareRecordingAndOpen(item);
            } else {
                Toast.makeText(this, "This file is not playable",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        playbackGeneration.incrementAndGet();
        launchPlayer(item, item.source);
    }

    private void prepareRecordingAndOpen(LibraryItem item) {
        ReliableSessionManifest manifest = item.recording;
        if (manifest == null) return;
        int generation = playbackGeneration.incrementAndGet();
        showFileOperation("Preparing " + item.title + " for playback…",
                0L, 0L, true);
        Intent service = new Intent(this, RecordingService.class)
                .setAction(RecordingService.ACTION_PREPARE_PLAYBACK)
                .putExtra(RecordingService.EXTRA_SESSION_ID,
                        manifest.sessionId);
        try {
            ContextCompat.startForegroundService(this, service);
        } catch (RuntimeException failure) {
            showFileOperation("Could not start playback preparation: "
                    + String.valueOf(failure.getMessage()), 0L, 0L, false);
            return;
        }
        playbackPreparation.execute(() -> {
            ReliableSessionStore browser = null;
            try {
                browser = ReliableSessionStore.openForBrowsing(
                        AudioLibraryActivity.this);
            } catch (Exception ignored) {}
            long deadline = SystemClock.elapsedRealtime() + 180_000L;
            while (generation == playbackGeneration.get()
                    && !Thread.currentThread().isInterrupted()
                    && SystemClock.elapsedRealtime() < deadline) {
                File file = RecordingUi.recordingFile(
                        AudioLibraryActivity.this, manifest);
                if (file != null && file.isFile() && file.length() > 0L) {
                    PlayerSource source = PlayerSource.recording(file,
                            item.title, file.length(), manifest.sessionId,
                            manifest.folderId);
                    runOnUiThread(() -> {
                        if (generation != playbackGeneration.get()
                                || isFinishing() || isDestroyed()) return;
                        showFileOperation("Opening " + item.title,
                                0L, 0L, false);
                        launchPlayer(item, source);
                    });
                    return;
                }
                try {
                    ReliableSessionManifest latest = browser == null
                            ? null : browser.load(manifest.sessionId);
                    if (latest != null && "ERROR".equals(latest.state)
                            && latest.error != null
                            && !latest.error.trim().isEmpty()) {
                        String exact = latest.error.trim();
                        runOnUiThread(() -> {
                            if (generation != playbackGeneration.get()
                                    || isFinishing() || isDestroyed()) return;
                            showFileOperation("Could not prepare playback: "
                                    + exact, 0L, 0L, false);
                        });
                        return;
                    }
                } catch (Exception ignored) {}
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            runOnUiThread(() -> {
                if (generation == playbackGeneration.get()
                        && !isFinishing() && !isDestroyed()) {
                    showFileOperation("Playback preparation did not complete; "
                            + "local durable audio remains preserved",
                            0L, 0L, false);
                }
            });
        });
    }

    private void launchPlayer(LibraryItem selected,
                              PlayerSource selectedSource) {
        if (selectedSource == null) return;
        Intent intent = new Intent(this, PlayerActivity.class);
        selectedSource.put(intent);
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        ArrayList<String> kinds = new ArrayList<>();
        ArrayList<String> sessions = new ArrayList<>();
        ArrayList<String> folders = new ArrayList<>();
        ArrayList<Long> byteList = new ArrayList<>();
        int queueIndex = -1;
        for (LibraryItem candidate : adapter.items()) {
            PlayerSource source = candidate == selected
                    ? selectedSource : candidate.source;
            if (!candidate.directory && source != null
                    && (candidate == selected || candidate.playable)) {
                if (candidate == selected) queueIndex = uris.size();
                uris.add(source.uri.toString());
                titles.add(source.title);
                kinds.add(source.kind);
                sessions.add(source.sessionId);
                folders.add(source.folderId);
                byteList.add(source.bytes);
            }
        }
        if (queueIndex < 0) {
            queueIndex = 0;
            uris.add(selectedSource.uri.toString());
            titles.add(selectedSource.title);
            kinds.add(selectedSource.kind);
            sessions.add(selectedSource.sessionId);
            folders.add(selectedSource.folderId);
            byteList.add(selectedSource.bytes);
        }
        long[] bytes = new long[byteList.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = byteList.get(i);
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_URIS, uris);
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_TITLES, titles);
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_KINDS, kinds);
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_SESSIONS, sessions);
        intent.putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_FOLDERS, folders);
        intent.putExtra(PlayerActivity.EXTRA_QUEUE_BYTES, bytes);
        intent.putExtra(PlayerActivity.EXTRA_QUEUE_INDEX, queueIndex);
        startActivity(intent);
    }

    private void manage(LibraryItem item) {
        if (item.appFolder != null) manageAppFolderItem(item.appFolder);
        else if (item.recording != null) manageRecording(item);
        else if (item.document != null) manageDocument(item);
    }

    private void manageAppFolderItem(ReliableSessionStore.Folder folder) {
        String[] actions = {
                "Open this folder", "Create subfolder", "Rename folder", "Move folder"
        };
        new MaterialAlertDialogBuilder(this).setTitle(folder.path)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        appFolderFilter = folder;
                        restoreScrollFirst = 0;
                        restoreScrollTop = 0;
                        showRecordings();
                    } else if (which == 1) {
                        // A cancelled dialog must never navigate or change
                        // the selected folder behind the user's back.
                        createFolderAt(folder.id, folder.path);
                    } else if (which == 2) {
                        renameFolder(folder);
                    } else {
                        moveFolder(folder);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void manageRecording(LibraryItem item) {
        String[] actions = {"Open in player", "Rename recording", "Move to app folder", "Delete local recording"};
        new MaterialAlertDialogBuilder(this).setTitle(item.title).setItems(actions, (d, which) -> {
            if (which == 0) open(item, adapter.position(item));
            else if (which == 1) renameRecording(item);
            else if (which == 2) moveRecording(item);
            else confirmDeleteRecording(item);
        }).setNegativeButton("Back", null).show();
    }

    private interface NameOperation<T> {
        T apply(String newName) throws Exception;
    }

    private <T> void editName(String title, String initialName,
                              String pendingMessage, NameOperation<T> action,
                              java.util.function.Consumer<T> onSuccess) {
        EditText input = nameInput(initialName);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty()) {
                                input.setError("Enter a name");
                                return;
                            }
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                    .setEnabled(false);
                            showFileOperation(pendingMessage, 0L, 0L, true);
                            worker.execute(() -> {
                                try {
                                    T result = action.apply(name);
                                    runOnUiThread(() -> {
                                        showFileOperation("Saved " + name, 0L, 0L, false);
                                        dialog.dismiss();
                                        onSuccess.accept(result);
                                    });
                                } catch (Exception failure) {
                                    runOnUiThread(() -> {
                                        showFileOperation("Cannot save: "
                                                + failure.getMessage(),
                                                0L, 0L, false);
                                        input.setError(String.valueOf(failure.getMessage()));
                                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                                .setEnabled(true);
                                    });
                                }
                            });
                        }));
        dialog.show();
    }

    private void showFileOperation(String message, long completed,
                                   long total, boolean active) {
        stateText.setText(message);
        if (!active) {
            fileTaskProgress.setVisibility(View.GONE);
            return;
        }
        if (total > 0L) {
            int permille = (int)Math.max(0L, Math.min(1000L,
                    1000L * Math.min(total, completed) / total));
            fileTaskProgress.setIndeterminate(false);
            fileTaskProgress.setProgress(permille);
        } else {
            fileTaskProgress.setIndeterminate(true);
        }
        fileTaskProgress.setContentDescription(message);
        fileTaskProgress.setVisibility(View.VISIBLE);
    }

    private FileOperations.TransferListener fileTransferProgress(String fileName) {
        return new FileOperations.TransferListener() {
            @Override public void onCopy(long copiedBytes, long expectedBytes) {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showFileOperation("Copying " + fileName,
                                copiedBytes, expectedBytes, true);
                    }
                });
            }

            @Override public void onVerifying() {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showFileOperation("Verifying copied file content…",
                                0L, 0L, true);
                    }
                });
            }
        };
    }

    private void renameRecording(LibraryItem item) {
        if (item == null || item.recording == null) return;
        editName("Rename recording",
                RecordingUi.title(item.recording),
                "Renaming recording…",
                name -> store.renameSession(item.recording.sessionId, name),
                changed -> showRecordings());
    }

    private void moveRecording(LibraryItem item) {
        showFileOperation("Loading available destination folders…", 0L, 0L, true);
        worker.execute(() -> {
            try {
                List<ReliableSessionStore.Folder> folders = store.listFolders();
                String[] names = new String[folders.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = folders.get(i).path;
                }
                runOnUiThread(() -> {
                    showFileOperation("Choose destination for " + item.title,
                            0L, 0L, false);
                    new MaterialAlertDialogBuilder(this).setTitle("Move recording")
                            .setItems(names, (d,w) -> {
                                if (folders.get(w).id.equals(item.recording.folderId)) {
                                    stateText.setText("Recording is already in this folder.");
                                    return;
                                }
                                showFileOperation("Moving recording…", 0L, 0L, true);
                                worker.execute(() -> {
                                    try {
                                        store.moveSession(item.recording.sessionId,
                                                folders.get(w).id);
                                        runOnUiThread(() -> {
                                            showFileOperation("Recording moved", 0L, 0L, false);
                                            showRecordings();
                                        });
                                    } catch (Exception failure) { error(failure); }
                                });
                            }).setNegativeButton("Cancel", null).show();
                });
            } catch (Exception failure) { error(failure); }
        });
    }

    private void confirmDeleteRecording(LibraryItem item) {
        if (item == null || item.recording == null) return;
        if (!item.recording.recordingFinished || !item.recording.remoteCommitted) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Phone copy cannot be deleted yet")
                    .setMessage("Finish recording and wait for Jetson to confirm the "
                            + "complete audio backup. This phone copy is still required.")
                    .setPositiveButton("Back", null).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this phone copy?")
                .setMessage("Jetson has confirmed a completed backup. Only this phone copy will be deleted; the server recording and transcript remain available.")
                .setNegativeButton("Back", null)
                .setPositiveButton("Delete local copy", (dialog, which) -> worker.execute(() -> {
                    try {
                        store.deleteFinishedSession(item.recording.sessionId);
                        runOnUiThread(this::showRecordings);
                    } catch (Exception failure) {
                        error(failure);
                    }
                })).show();
    }

    private void manageDocument(LibraryItem item) {
        if (item.directory) {
            new MaterialAlertDialogBuilder(this).setTitle(item.title).setItems(new String[]{"Open folder", "Rename folder"}, (d,w) -> {
                if (w==0) open(item,adapter.position(item)); else renameDocument(item);
            }).setNegativeButton("Back",null).show();
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle(item.title).setItems(new String[]{"Open in player","Rename","Move","Delete"},(d,w)->{
            if(w==0)open(item,adapter.position(item));else if(w==1)renameDocument(item);else if(w==2){pendingMove=item.document;Intent move=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);move.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(move,REQUEST_MOVE);}else confirmDelete(item);
        }).setNegativeButton("Back",null).show();
    }

    private void renameDocument(LibraryItem item) {
        if (item.document == null) return;
        editName(item.directory ? "Rename folder" : "Rename file",
                item.title, "Renaming phone file…",
                name -> FileOperations.rename(this, item.document.getUri(), name),
                ignored -> refreshFiles());
    }

    private void confirmDelete(LibraryItem item) {
        new MaterialAlertDialogBuilder(this).setTitle("Delete file?").setMessage(item.title+" will be removed from this phone provider.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->worker.execute(()->{
            try{if(!item.document.delete())throw new java.io.IOException("The provider rejected deletion");runOnUiThread(this::refreshFiles);}catch(Exception failure){error(failure);}
        })).show();
    }

    private void manageAppFolders() {
        if (store == null) {
            stateText.setText("Recording folders are still loading.");
            return;
        }
        ReliableSessionStore.Folder current = appFolderFilter;
        String[] actions = current == null
                ? new String[]{"Create root folder"}
                : new String[]{"Create subfolder", "Rename this folder",
                        "Move this folder"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(current == null ? "Recordings folders"
                        : current.path)
                .setItems(actions, (dialog, which) -> {
                    if (current == null) {
                        createFolder("");
                    } else if (which == 0) {
                        createFolder(current.id);
                    } else if (which == 1) {
                        renameFolder(current);
                    } else {
                        moveFolder(current);
                    }
                }).setNegativeButton("Back", null).show();
    }

    private void createFolder(String parentFolderId) {
        String location = parentFolderId == null || parentFolderId.isEmpty()
                ? "Recordings" : appFolderFilter == null
                        ? "Recordings" : appFolderFilter.path;
        createFolderAt(parentFolderId, location);
    }

    private void createFolderAt(String parentFolderId, String location) {
        if (store == null) {
            stateText.setText("Recording folders are still loading.");
            return;
        }
        editName("Create folder in " + location, "",
                "Creating recording folder…",
                name -> store.createFolder(name, parentFolderId),
                created -> {
                    appFolderFilter = created;
                    showRecordings();
                });
    }

    private void renameFolder(ReliableSessionStore.Folder folder) {
        editName("Rename folder", folder.name, "Renaming folder…",
                name -> store.renameFolder(folder.id, name),
                renamed -> {
                    appFolderFilter = renamed;
                    showRecordings();
                });
    }

    private void moveFolder(ReliableSessionStore.Folder folder) {
        worker.execute(() -> {
            try {
                List<ReliableSessionStore.Folder> all = store.listFolders();
                ArrayList<ReliableSessionStore.Folder> destinations =
                        new ArrayList<>();
                ArrayList<String> labels = new ArrayList<>();
                destinations.add(null);
                labels.add("Recordings root");
                String prefix = folder.path + "/";
                for (ReliableSessionStore.Folder candidate : all) {
                    if (candidate.id.equals(folder.id)
                            || candidate.path.startsWith(prefix)) continue;
                    destinations.add(candidate);
                    labels.add(candidate.path);
                }
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle("Move " + folder.path)
                        .setItems(labels.toArray(new String[0]),
                                (dialog, which) -> worker.execute(() -> {
                                    try {
                                        ReliableSessionStore.Folder parent =
                                                destinations.get(which);
                                        ReliableSessionStore.Folder moved =
                                                store.moveFolder(folder.id,
                                                        parent == null ? ""
                                                                : parent.id);
                                        runOnUiThread(() -> {
                                            appFolderFilter = moved;
                                            showRecordings();
                                        });
                                    } catch (Exception failure) {
                                        error(failure);
                                    }
                                }))
                        .setNegativeButton("Back", null).show());
            } catch (Exception failure) {
                error(failure);
            }
        });
    }

    private void chooseRoot() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_ROOT);
    }
    private void openAnyFile() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null){
            if(requestCode==REQUEST_MOVE)pendingMove=null;
            return;
        }
        Uri uri=data.getData();
        FileOperations.persistPermissions(this, uri, data.getFlags());
        if(requestCode==REQUEST_ROOT){preferences.edit().putString(ROOT_URI,uri.toString()).apply();showPhoneRoot();}
        else if(requestCode==REQUEST_FILE){try{DocumentFile file=FileOperations.document(this,uri);LibraryItem item=LibraryItem.document(file,null);open(item,0);}catch(Exception failure){error(failure);}}
        else if(requestCode==REQUEST_MOVE&&pendingMove!=null){
            DocumentFile moving=pendingMove;pendingMove=null;
            showFileOperation("Moving " + moving.getName() + "…", 0L, 0L, true);
            worker.execute(()->{
                try {
                    FileOperations.move(this,moving.getUri(),uri,moving.getName(),
                            fileTransferProgress(moving.getName()));
                    runOnUiThread(()->{
                        showFileOperation("File moved and verified", 0L, 0L, false);
                        refreshFiles();
                    });
                } catch(Exception failure) {error(failure);}
            });
        }
    }

    private void up() {
        if (recordingsMode) {
            ReliableSessionStore.Folder current = appFolderFilter;
            if (current == null) {
                finish();
                return;
            }
            String parentId = current.parentId;
            showFileOperation("Opening parent recording folder…",
                    0L, 0L, true);
            int generation = loadGeneration.incrementAndGet();
            worker.execute(() -> {
                ReliableSessionStore.Folder parent = null;
                try {
                    if (parentId != null && !parentId.isEmpty()) {
                        parent = store.getFolder(parentId);
                    }
                } catch (Exception ignored) {
                    // An unreadable old parent must never trap navigation.
                    parent = null;
                }
                final ReliableSessionStore.Folder resolved = parent;
                runOnUiThread(() -> {
                    if (generation != loadGeneration.get()
                            || !recordingsMode || isFinishing()) return;
                    appFolderFilter = resolved;
                    showFileOperation("Opening recording folder…", 0L, 0L, false);
                    showRecordings();
                });
            });
            return;
        }
        if (directoryStack.size() <= 1) return;
        playbackGeneration.incrementAndGet();
        directoryStack.remove(directoryStack.size() - 1);
        if (!directoryNameStack.isEmpty()) {
            directoryNameStack.remove(directoryNameStack.size() - 1);
        }
        Uri uri = directoryStack.get(directoryStack.size() - 1);
        currentDirectory = DocumentFile.fromSingleUri(this, uri);
        if (currentDirectory == null) currentDirectory = DocumentFile.fromTreeUri(this, uri);
        currentDirectoryName = directoryNameStack.isEmpty()
                ? "Phone files" : directoryNameStack.get(directoryNameStack.size() - 1);
        restoreScrollFirst = 0;
        restoreScrollTop = 0;
        saveLibraryState();
        refreshFiles();
    }
    private void navigateBack() {
        if (recordingsMode && appFolderFilter != null) {
            up();
        } else if (!recordingsMode && directoryStack.size() > 1) {
            up();
        } else {
            finish();
        }
    }

    @Override public void onBackPressed() {
        navigateBack();
    }
    private void home(){Intent intent=new Intent(this,MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);}
    private void updateModeButtons() {
        recordingsModeButton.setText(recordingsMode
                ? "App recordings ✓" : "App recordings");
        filesModeButton.setText(recordingsMode
                ? "Phone files" : "Phone files ✓");
        if (primaryLibraryAction != null) {
            primaryLibraryAction.setText(recordingsMode
                    ? "New folder" : "Choose folder");
            primaryLibraryAction.setContentDescription(recordingsMode
                    ? "Create an app recording folder here"
                    : "Choose a phone storage folder");
        }
    }
    private EditText nameInput(String value){EditText input=new TextInputEditText(this);input.setSingleLine(true);input.setText(value);input.selectAll();return input;}
    private void error(Exception failure) {
        runOnUiThread(() -> {
            String message = failure == null || failure.getMessage() == null
                    ? "Unknown file operation error" : failure.getMessage();
            showFileOperation("File operation failed: " + message,
                    0L, 0L, false);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void saveLibraryState() {
        if (preferences == null || list == null) return;
        JSONArray uris = new JSONArray(); JSONArray names = new JSONArray();
        for (Uri uri : directoryStack) uris.put(uri.toString());
        for (String name : directoryNameStack) names.put(name);
        View first = list.getChildAt(0);
        int firstPosition = list.getFirstVisiblePosition();
        int top = first == null ? 0 : first.getTop();
        restoreScrollFirst = firstPosition;
        restoreScrollTop = top;
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(MODE, recordingsMode)
                .putString(APP_FOLDER_ID, appFolderFilter == null ? "" : appFolderFilter.id)
                .putString(APP_FOLDER_NAME, appFolderFilter == null ? "" : appFolderFilter.name)
                .putString(DIRECTORY_STACK, uris.toString())
                .putString(DIRECTORY_NAMES, names.toString())
                .putString(CURRENT_DIRECTORY, currentDirectory == null
                        ? "" : currentDirectory.getUri().toString())
                .putString(CURRENT_DIRECTORY_NAME, currentDirectoryName)
                .putInt(SCROLL_FIRST, firstPosition)
                .putInt(SCROLL_TOP, top);
        editor.apply();
    }

    private void saveVisibleCache(List<LibraryItem> items) {
        // The recording folder hierarchy is reloaded from the authoritative
        // local database. Only phone-provider lists use this preview cache.
        if (recordingsMode || preferences == null) return;
        final int generation = loadGeneration.get();
        final String directoryUri = currentDirectory == null
                ? "" : currentDirectory.getUri().toString();
        final ArrayList<LibraryItem> snapshot = new ArrayList<>(items);
        // A large phone folder can contain a thousand cached rows. Never
        // serialize their JSON or query DocumentFile on the main/UI thread.
        try {
            worker.execute(() -> {
                try {
                    JSONObject root = new JSONObject();
                    root.put("schema", 2);
                    root.put("recordings_mode", false);
                    root.put("app_folder_id", "");
                    root.put("directory_uri", directoryUri);
                    JSONArray array = new JSONArray();
                    int limit = Math.min(snapshot.size(), 1000);
                    for (int i = 0; i < limit; i++) {
                        if (generation != loadGeneration.get()) return;
                        array.put(snapshot.get(i).toJson());
                    }
                    root.put("items", array);
                    if (generation == loadGeneration.get() && !recordingsMode) {
                        preferences.edit().putString(CACHE, root.toString()).apply();
                    }
                } catch (Exception failure) {
                    // Cache failure must never obscure the live folder contents.
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Activity closed; no new caching work is admitted.
        }
    }

    private void restoreCachedItems() {
        try {
            if (recordingsMode) return;
            JSONObject root = new JSONObject(preferences.getString(CACHE, "{}"));
            if (root.optInt("schema", 0) != 2) return;
            if (root.optBoolean("recordings_mode", true) != recordingsMode) return;
            String expectedFolder = appFolderFilter == null ? "" : appFolderFilter.id;
            if (!expectedFolder.equals(root.optString("app_folder_id", ""))) return;
            if (!recordingsMode) {
                String expectedDirectory = preferences.getString(
                        CURRENT_DIRECTORY, "");
                if (!String.valueOf(expectedDirectory).equals(
                        root.optString("directory_uri", ""))) return;
            }
            ArrayList<LibraryItem> items = new ArrayList<>();
            JSONArray array = root.optJSONArray("items");
            if (array != null) for (int i = 0; i < array.length(); i++) {
                LibraryItem item = LibraryItem.fromJson(this,
                        array.optJSONObject(i));
                if (item != null) items.add(item);
            }
            if (!items.isEmpty()) {
                adapter.replace(items);
                stateText.setText(items.size() + " cached items · refreshing…");
                restoreListPosition();
            }
        } catch (Exception ignored) {}
    }

    private void restoreListPosition() {
        if (list == null) return;
        int first = restoreScrollFirst;
        int top = restoreScrollTop;
        list.post(() -> list.setSelectionFromTop(first, top));
    }
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);return row;}
    private LinearLayout.LayoutParams half(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,AndroidUi.dp(this,50),1f);p.setMargins(2,2,2,2);return p;}
    private LinearLayout.LayoutParams third(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,AndroidUi.dp(this,52),1f);p.setMargins(2,2,2,2);return p;}

    private static final class LibraryItem {
        final String title,detail;final boolean directory,playable;final DocumentFile document;
        final ReliableSessionManifest recording;final ReliableSessionStore.Folder appFolder;final PlayerSource source;
        private LibraryItem(String title,String detail,boolean directory,boolean playable,
                            DocumentFile document,ReliableSessionManifest recording,
                            ReliableSessionStore.Folder appFolder,PlayerSource source){
            this.title=title;this.detail=detail;this.directory=directory;this.playable=playable;
            this.document=document;this.recording=recording;this.appFolder=appFolder;this.source=source;
        }
        static LibraryItem appFolder(ReliableSessionStore.Folder folder, int count,
                                     boolean showFullPath) {
            String title = showFullPath ? folder.path : folder.name;
            return new LibraryItem(title,
                    "Folder · " + count + " recordings", true, false,
                    null, null, folder, null);
        }
        static LibraryItem recording(ReliableSessionManifest manifest,File file,boolean playable){
            String fileName = com.hans.android.audio.reliable.RecordingFileNames
                    .visibleMp3Name(manifest.createdAt, manifest.displayName);
            PlayerSource source = playable
                    ? PlayerSource.recording(file, fileName, file.length(),
                    manifest.sessionId, manifest.folderId) : null;
            String detail = RecordingUi.humanState(manifest) + " · "
                    + RecordingUi.formatBytes(RecordingUi.recordingBytes(manifest))
                    + (manifest.remoteCommitted ? " · backup confirmed"
                            : " · phone copy required");
            return new LibraryItem(fileName,detail,false,playable,null,manifest,null,source);
        }
        static LibraryItem document(DocumentFile file,Uri parent){
            String name=file.getName()==null?"Unnamed":file.getName();boolean directory=file.isDirectory();
            long bytes=directory?0L:file.length(); String type=file.getType();
            PlayerSource source=directory?null:new PlayerSource(file.getUri(),name,PlayerSource.KIND_DOCUMENT,bytes,"","",parent);
            return new LibraryItem(name,directory?"Folder":(type==null?"File":type)+" · "+RecordingUi.formatBytes(bytes),directory,!directory,file,null,null,source);
        }
        static LibraryItem document(Context context, FastDocumentDirectory.Entry entry,
                                    Uri parent) {
            DocumentFile file = DocumentFile.fromSingleUri(context, entry.uri);
            PlayerSource source = entry.directory ? null : new PlayerSource(
                    entry.uri, entry.name, PlayerSource.KIND_DOCUMENT,
                    entry.bytes, "", "", parent);
            String detail = entry.directory ? "Folder"
                    : (entry.mimeType.isEmpty() ? "File" : entry.mimeType)
                    + " · " + RecordingUi.formatBytes(entry.bytes);
            return new LibraryItem(entry.name, detail, entry.directory,
                    !entry.directory, file, null, null, source);
        }
        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("title", title); value.put("detail", detail);
            value.put("directory", directory); value.put("playable", playable);
            if (document != null) value.put("document_uri", document.getUri().toString());
            if (source != null) value.put("source", source.toJson());
            if (appFolder != null) {
                value.put("app_folder_id", appFolder.id);
                value.put("app_folder_name", appFolder.name);
                value.put("app_folder_parent_id", appFolder.parentId);
                value.put("app_folder_path", appFolder.path);
            }
            return value;
        }
        static LibraryItem fromJson(Context context, JSONObject value) {
            if (value == null) return null;
            String uri = value.optString("document_uri", "");
            DocumentFile document = uri.isEmpty() ? null
                    : DocumentFile.fromSingleUri(context, Uri.parse(uri));
            PlayerSource source = PlayerSource.fromJson(value.optJSONObject("source"));
            if (source != null && PlayerSource.KIND_RECORDING.equals(source.kind)
                    && "file".equalsIgnoreCase(source.uri.getScheme())
                    && (source.uri.getPath() == null
                    || !new File(source.uri.getPath()).isFile())) return null;
            String folderId = value.optString("app_folder_id", "");
            ReliableSessionStore.Folder folder = folderId.isEmpty() ? null
                    : new ReliableSessionStore.Folder(folderId,
                    value.optString("app_folder_name", "App folder"),
                    value.optString("app_folder_parent_id", ""),
                    0L, value.optString("app_folder_name", "App folder"),
                    value.optString("app_folder_parent_id", ""),
                    value.optString("app_folder_path",
                            value.optString("app_folder_name", "App folder")));
            return new LibraryItem(value.optString("title", "Unnamed"),
                    value.optString("detail", ""),
                    value.optBoolean("directory", false),
                    value.optBoolean("playable", false), document,
                    null, folder, source);
        }
    }

    private final class LibraryAdapter extends BaseAdapter {
        private final ArrayList<LibraryItem> items = new ArrayList<>();

        void replace(List<LibraryItem> values) {
            items.clear(); items.addAll(values); notifyDataSetChanged();
        }
        LibraryItem item(int position) { return items.get(position); }
        int position(LibraryItem item) { return items.indexOf(item); }
        List<LibraryItem> items() { return new ArrayList<>(items); }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            RowHolder holder;
            if (convert instanceof LinearLayout && convert.getTag() instanceof RowHolder) {
                holder = (RowHolder) convert.getTag();
            } else {
                LinearLayout row = new LinearLayout(AudioLibraryActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(AndroidUi.dp(AudioLibraryActivity.this, 8),
                        AndroidUi.dp(AudioLibraryActivity.this, 6),
                        AndroidUi.dp(AudioLibraryActivity.this, 6),
                        AndroidUi.dp(AudioLibraryActivity.this, 6));
                row.setBackgroundColor(Color.WHITE);
                LinearLayout contents = new LinearLayout(AudioLibraryActivity.this);
                contents.setOrientation(LinearLayout.HORIZONTAL);
                contents.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout labels = new LinearLayout(AudioLibraryActivity.this);
                labels.setOrientation(LinearLayout.VERTICAL);

                TextView name = AndroidUi.text(AudioLibraryActivity.this,
                        "", 16, true, AndroidUi.INK);
                name.setSingleLine(false);
                name.setHorizontallyScrolling(false);
                name.setEllipsize(null);
                name.setLineSpacing(0f, 1.08f);
                TextView detail = AndroidUi.small(AudioLibraryActivity.this, "");
                detail.setSingleLine(false);
                detail.setHorizontallyScrolling(false);
                detail.setEllipsize(null);
                labels.addView(name);
                labels.addView(detail);
                contents.addView(labels, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button actions = VoiceButtonMaterial.toolbarButton(
                        AudioLibraryActivity.this, "Actions");
                actions.setId(R.id.voicebutton_library_item_actions);
                actions.setMinHeight(AndroidUi.dp(AudioLibraryActivity.this, 48));
                actions.setMinWidth(AndroidUi.dp(AudioLibraryActivity.this, 72));
                actions.setFocusable(false);
                LinearLayout.LayoutParams actionParams =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                actionParams.setMarginStart(AndroidUi.dp(AudioLibraryActivity.this, 7));
                contents.addView(actions, actionParams);
                row.addView(contents);
                holder = new RowHolder(row, name, detail, actions);
                row.setTag(holder);
                convert = row;
            }
            LibraryItem item = items.get(position);
            holder.name.setText((item.directory ? "Folder · " : "") + item.title);
            holder.detail.setText(item.detail);
            holder.actions.setContentDescription("Actions for " + item.title);
            holder.actions.setOnClickListener(v -> manage(item));
            return convert;
        }
    }

    private static final class RowHolder {
        final LinearLayout row;
        final TextView name;
        final TextView detail;
        final Button actions;
        RowHolder(LinearLayout row, TextView name, TextView detail,
                  Button actions) {
            this.row = row;
            this.name = name;
            this.detail = detail;
            this.actions = actions;
        }
    }

}
