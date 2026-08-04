package com.hans.android.voicebutton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
    private final LibraryAdapter adapter = new LibraryAdapter();
    private final AtomicInteger loadGeneration = new AtomicInteger();
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
    private Button recordingsModeButton;
    private Button filesModeButton;
    private Button upButton;
    private ListView list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        recordingsMode = preferences.getBoolean(MODE, true);
        String folderId = preferences.getString(APP_FOLDER_ID, "");
        if (folderId != null && !folderId.isEmpty()) {
            appFolderFilter = new ReliableSessionStore.Folder(folderId,
                    preferences.getString(APP_FOLDER_NAME, "App folder"), 0L);
        }
        restoreScrollFirst = preferences.getInt(SCROLL_FIRST, 0);
        restoreScrollTop = preferences.getInt(SCROLL_TOP, 0);
        buildScreen();
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

    @Override protected void onDestroy() {
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
        Button back = AndroidUi.toolbarButton(this, "Back");
        back.setOnClickListener(v -> navigateBack());
        Button home = AndroidUi.toolbarButton(this, "Home");
        home.setOnClickListener(v -> home());
        toolbar.addView(back, half());
        toolbar.addView(home, half());
        root.addView(toolbar);

        TextView title = AndroidUi.title(this, "Library");
        AndroidUi.stableLine(this, title, 40);
        root.addView(title);
        stateText = AndroidUi.small(this, "Choose audio to play");
        AndroidUi.stableLine(this, stateText, 30);
        root.addView(stateText);

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
        upButton = AndroidUi.toolbarButton(this, "Up");
        upButton.setOnClickListener(v -> up());
        pathText = AndroidUi.body(this, "App recording folders");
        AndroidUi.stableLine(this, pathText, 46);
        navigation.addView(upButton, new LinearLayout.LayoutParams(
                AndroidUi.dp(this, 72), AndroidUi.dp(this, 46)));
        navigation.addView(pathText, new LinearLayout.LayoutParams(
                0, AndroidUi.dp(this, 46), 1f));
        root.addView(navigation);

        list = new ListView(this);
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
                saveLibraryState();
            }
            @Override public void onScroll(AbsListView view, int first,
                                           int visible, int total) {
                if (visible > 0) saveLibraryState();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button more = AndroidUi.toolbarButton(this, "More");
        more.setOnClickListener(v -> showLibraryMenu());
        root.addView(more, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 48)));
        setContentView(root);
    }

    private void showLibraryMenu() {
        String[] actions = recordingsMode
                ? new String[]{"Manage app folders", "Choose phone folder",
                        "Open any phone file", "About library"}
                : new String[]{"Choose phone folder", "Open any phone file",
                        "App recordings", "About library"};
        new AlertDialog.Builder(this).setTitle("More")
                .setItems(actions, (dialog, which) -> {
                    if (recordingsMode) {
                        if (which == 0) manageAppFolders();
                        else if (which == 1) chooseRoot();
                        else if (which == 2) openAnyFile();
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
        new AlertDialog.Builder(this).setTitle("Library")
                .setMessage("Tap an item to open it. Long-press an item for rename, move, or delete actions. Folder management and phone-file import stay in More so navigation remains clear.")
                .setPositiveButton("Back", null).show();
    }

    private void showRecordings() {
        recordingsMode = true; currentDirectory = null; directoryStack.clear();
        directoryNameStack.clear(); updateModeButtons(); saveLibraryState();
        upButton.setEnabled(appFolderFilter != null);
        pathText.setText(appFolderFilter == null ? "App recording folders" : appFolderFilter.name);
        stateText.setText(appFolderFilter == null ? "Loading folders…" : "Loading recordings…");
        ReliableSessionStore.Folder filter = appFolderFilter;
        int generation = loadGeneration.incrementAndGet();
        worker.execute(() -> {
            ArrayList<LibraryItem> items = new ArrayList<>();
            try {
                if (store == null) store = ReliableSessionStore.openForBrowsing(this);
                List<ReliableSessionManifest> manifests = store.list();
                if (filter == null) {
                    for (ReliableSessionStore.Folder folder : store.listFolders()) {
                        int count = 0;
                        for (ReliableSessionManifest manifest : manifests) {
                            if (folder.id.equals(manifest.folderId)) count++;
                        }
                        items.add(LibraryItem.appFolder(folder, count));
                    }
                } else {
                    manifests.sort(Comparator.comparingLong(
                            (ReliableSessionManifest value) -> value.createdAt).reversed());
                    for (ReliableSessionManifest manifest : manifests) {
                        if (!filter.id.equals(manifest.folderId)) continue;
                        File file = RecordingUi.recordingFile(this, manifest);
                        items.add(LibraryItem.recording(manifest, file,
                                file != null && file.isFile()));
                    }
                }
                runOnUiThread(() -> {
                    if (generation != loadGeneration.get()) return;
                    adapter.replace(items);
                    restoreListPosition();
                    saveVisibleCache(items);
                    saveLibraryState();
                    stateText.setText(items.isEmpty()
                            ? (filter == null ? "No app folders" : "No recordings in this folder")
                            : items.size() + (filter == null ? " folders" : " recordings"));
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration.get()) stateText.setText(
                            "Could not load recordings: " + failure.getMessage());
                });
            }
        });
    }

    private void showPhoneRoot() {
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
                ? "Refreshing folder…" : "Reading folder…");
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
                    stateText.setText(items.size() + " items");
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation == loadGeneration.get()) stateText.setText(
                            "Could not read folder: " + failure.getMessage());
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
            currentDirectory = item.document;
            currentDirectoryName = item.title;
            directoryStack.add(item.document.getUri());
            directoryNameStack.add(item.title);
            restoreScrollFirst = 0; restoreScrollTop = 0;
            saveLibraryState(); refreshFiles();
            return;
        }
        if (!item.playable) {
            Toast.makeText(this, "This recording has no playable local file yet", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        item.source.put(intent);
        ArrayList<String> uris = new ArrayList<>(), titles = new ArrayList<>(), kinds = new ArrayList<>(), sessions = new ArrayList<>(), folders = new ArrayList<>();
        ArrayList<Long> byteList = new ArrayList<>();
        int queueIndex = -1;
        for (LibraryItem candidate : adapter.items()) {
            if (!candidate.directory && candidate.playable && candidate.source != null) {
                if (candidate == item) queueIndex = uris.size();
                uris.add(candidate.source.uri.toString()); titles.add(candidate.source.title);
                kinds.add(candidate.source.kind); sessions.add(candidate.source.sessionId);
                folders.add(candidate.source.folderId); byteList.add(candidate.source.bytes);
            }
        }
        long[] bytes = new long[byteList.size()]; for (int i=0;i<bytes.length;i++) bytes[i]=byteList.get(i);
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
        if (item.appFolder != null) renameFolder(item.appFolder);
        else if (item.recording != null) manageRecording(item);
        else if (item.document != null) manageDocument(item);
    }

    private void manageRecording(LibraryItem item) {
        String[] actions = {"Rename recording", "Move to app folder", "Open in player"};
        new AlertDialog.Builder(this).setTitle(item.title).setItems(actions, (d, which) -> {
            if (which == 0) renameRecording(item);
            else if (which == 1) moveRecording(item);
            else open(item, adapter.position(item));
        }).setNegativeButton("Back", null).show();
    }

    private void renameRecording(LibraryItem item) {
        EditText input = nameInput(item.title);
        new AlertDialog.Builder(this).setTitle("Rename recording").setView(input)
                .setNegativeButton("Back", null).setPositiveButton("Rename", (d,w) -> worker.execute(() -> {
                    try { store.renameSession(item.recording.sessionId, input.getText().toString()); runOnUiThread(this::showRecordings); }
                    catch (Exception failure) { error(failure); }
                })).show();
    }

    private void moveRecording(LibraryItem item) {
        worker.execute(() -> {
            try {
                List<ReliableSessionStore.Folder> folders = store.listFolders();
                String[] names = new String[folders.size()]; for (int i=0;i<names.length;i++) names[i]=folders.get(i).name;
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Move recording").setItems(names, (d,w) -> worker.execute(() -> {
                    try { store.moveSession(item.recording.sessionId, folders.get(w).id); runOnUiThread(this::showRecordings); }
                    catch (Exception failure) { error(failure); }
                })).setNegativeButton("Back", null).show());
            } catch (Exception failure) { error(failure); }
        });
    }

    private void manageDocument(LibraryItem item) {
        if (item.directory) {
            new AlertDialog.Builder(this).setTitle(item.title).setItems(new String[]{"Open folder", "Rename folder"}, (d,w) -> {
                if (w==0) open(item,adapter.position(item)); else renameDocument(item);
            }).setNegativeButton("Back",null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(item.title).setItems(new String[]{"Open in player","Rename","Move","Delete"},(d,w)->{
            if(w==0)open(item,adapter.position(item));else if(w==1)renameDocument(item);else if(w==2){pendingMove=item.document;Intent move=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);move.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(move,REQUEST_MOVE);}else confirmDelete(item);
        }).setNegativeButton("Back",null).show();
    }

    private void renameDocument(LibraryItem item) {
        EditText input=nameInput(item.title);
        new AlertDialog.Builder(this).setTitle(item.directory?"Rename folder":"Rename file").setView(input).setNegativeButton("Back",null).setPositiveButton("Rename",(d,w)->worker.execute(()->{
            try{FileOperations.rename(this,item.document.getUri(),input.getText().toString());runOnUiThread(this::refreshFiles);}catch(Exception failure){error(failure);}
        })).show();
    }

    private void confirmDelete(LibraryItem item) {
        new AlertDialog.Builder(this).setTitle("Delete file?").setMessage(item.title+" will be removed from this phone provider.").setNegativeButton("Cancel",null).setPositiveButton("Delete",(d,w)->worker.execute(()->{
            try{if(!item.document.delete())throw new java.io.IOException("The provider rejected deletion");runOnUiThread(this::refreshFiles);}catch(Exception failure){error(failure);}
        })).show();
    }

    private void manageAppFolders() {
        if (store == null) return;
        new AlertDialog.Builder(this).setTitle("App folders").setItems(new String[]{"Create folder","Rename folder"},(d,w)->{
            if(w==0)createFolder();else chooseFolderToRename();
        }).setNegativeButton("Back",null).show();
    }

    private void createFolder() {
        EditText input=nameInput("");
        new AlertDialog.Builder(this).setTitle("Create app folder").setView(input).setNegativeButton("Back",null).setPositiveButton("Create",(d,w)->worker.execute(()->{
            try{store.createFolder(input.getText().toString());runOnUiThread(this::showRecordings);}catch(Exception failure){error(failure);}
        })).show();
    }

    private void chooseFolderToRename() {
        worker.execute(()->{
            try{List<ReliableSessionStore.Folder> folders=store.listFolders();String[] names=new String[folders.size()];for(int i=0;i<names.length;i++)names[i]=folders.get(i).name;runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Rename app folder").setItems(names,(d,w)->renameFolder(folders.get(w))).setNegativeButton("Back",null).show());}catch(Exception failure){error(failure);}
        });
    }

    private void renameFolder(ReliableSessionStore.Folder folder) {
        EditText input=nameInput(folder.name);
        new AlertDialog.Builder(this).setTitle("Rename app folder").setView(input).setNegativeButton("Back",null).setPositiveButton("Rename",(d,w)->worker.execute(()->{
            try{store.renameFolder(folder.id,input.getText().toString());runOnUiThread(this::showRecordings);}catch(Exception failure){error(failure);}
        })).show();
    }

    private void chooseRoot() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_ROOT);
    }
    private void openAnyFile() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_FILE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();FileOperations.persistPermissions(this, uri, data.getFlags());
        if(requestCode==REQUEST_ROOT){preferences.edit().putString(ROOT_URI,uri.toString()).apply();showPhoneRoot();}
        else if(requestCode==REQUEST_FILE){try{DocumentFile file=FileOperations.document(this,uri);LibraryItem item=LibraryItem.document(file,null);open(item,0);}catch(Exception failure){error(failure);}}
        else if(requestCode==REQUEST_MOVE&&pendingMove!=null){DocumentFile moving=pendingMove;pendingMove=null;worker.execute(()->{try{FileOperations.move(this,moving.getUri(),uri,moving.getName());runOnUiThread(this::refreshFiles);}catch(Exception failure){error(failure);}});}
    }

    private void up() {
        if (recordingsMode) {
            if (appFolderFilter != null) {
                appFolderFilter = null;
                showRecordings();
            } else finish();
            return;
        }
        if(directoryStack.size()<=1)return;
        directoryStack.remove(directoryStack.size()-1);
        if (!directoryNameStack.isEmpty())
            directoryNameStack.remove(directoryNameStack.size()-1);
        Uri uri=directoryStack.get(directoryStack.size()-1);
        currentDirectory=DocumentFile.fromSingleUri(this,uri);
        if(currentDirectory==null)currentDirectory=DocumentFile.fromTreeUri(this,uri);
        currentDirectoryName = directoryNameStack.isEmpty()
                ? "Phone files" : directoryNameStack.get(directoryNameStack.size()-1);
        restoreScrollFirst = 0; restoreScrollTop = 0;
        saveLibraryState(); refreshFiles();
    }
    private void navigateBack(){if(!recordingsMode&&directoryStack.size()>1)up();else finish();}
    private void home(){Intent intent=new Intent(this,MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);}
    private void updateModeButtons(){recordingsModeButton.setText(recordingsMode?"App recordings ✓":"App recordings");filesModeButton.setText(recordingsMode?"Phone files":"Phone files ✓");}
    private EditText nameInput(String value){EditText input=new EditText(this);input.setSingleLine(true);input.setText(value);input.selectAll();return input;}
    private void error(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}

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
        try {
            JSONObject root = new JSONObject();
            root.put("schema", 2);
            root.put("recordings_mode", recordingsMode);
            root.put("app_folder_id", appFolderFilter == null ? "" : appFolderFilter.id);
            root.put("directory_uri", currentDirectory == null
                    ? "" : currentDirectory.getUri().toString());
            JSONArray array = new JSONArray();
            int limit = Math.min(items.size(), 1000);
            for (int i = 0; i < limit; i++) array.put(items.get(i).toJson());
            root.put("items", array);
            preferences.edit().putString(CACHE, root.toString()).apply();
        } catch (Exception ignored) {}
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
        static LibraryItem appFolder(ReliableSessionStore.Folder folder,int count){
            return new LibraryItem(folder.name,count+" recordings",true,false,null,null,folder,null);
        }
        static LibraryItem recording(ReliableSessionManifest manifest,File file,boolean playable){
            String fileName = playable && file != null ? file.getName()
                    : RecordingUi.title(manifest) + ".mp3";
            PlayerSource source=playable?PlayerSource.recording(file,fileName,file.length(),manifest.sessionId,manifest.folderId):null;
            String detail = RecordingUi.title(manifest) + " · "
                    + RecordingUi.humanState(manifest) + " · "
                    + RecordingUi.formatBytes(RecordingUi.recordingBytes(manifest));
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
                    value.optString("app_folder_name", "App folder"), 0L);
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
                row.setPadding(AndroidUi.dp(AudioLibraryActivity.this, 12),
                        AndroidUi.dp(AudioLibraryActivity.this, 9),
                        AndroidUi.dp(AudioLibraryActivity.this, 12),
                        AndroidUi.dp(AudioLibraryActivity.this, 9));
                row.setBackgroundColor(Color.WHITE);
                TextView name = AndroidUi.text(AudioLibraryActivity.this,
                        "", 16, false, AndroidUi.INK);
                name.setSingleLine(false);
                name.setHorizontallyScrolling(false);
                name.setEllipsize(null);
                name.setLineSpacing(0f, 1.08f);
                TextView detail = AndroidUi.small(AudioLibraryActivity.this, "");
                detail.setSingleLine(false);
                detail.setHorizontallyScrolling(false);
                detail.setEllipsize(null);
                row.addView(name);
                row.addView(detail);
                holder = new RowHolder(row, name, detail);
                row.setTag(holder);
                convert = row;
            }
            LibraryItem item = items.get(position);
            String completeName = (item.directory ? "Folder · " : "") + item.title;
            FileNameParts parts = FileNameParts.split(completeName, 44);
            SpannableStringBuilder styledName = new SpannableStringBuilder(
                    parts.complete());
            int headEnd = parts.head.length();
            if (headEnd > 0) {
                styledName.setSpan(new StyleSpan(Typeface.BOLD), 0, headEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (headEnd < styledName.length()) {
                styledName.setSpan(new RelativeSizeSpan(0.84f), headEnd,
                        styledName.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            holder.name.setText(styledName);
            holder.detail.setText(item.detail);
            return convert;
        }
    }

    private static final class RowHolder {
        final LinearLayout row;
        final TextView name;
        final TextView detail;
        RowHolder(LinearLayout row, TextView name, TextView detail) {
            this.row = row;
            this.name = name;
            this.detail = detail;
        }
    }

}
