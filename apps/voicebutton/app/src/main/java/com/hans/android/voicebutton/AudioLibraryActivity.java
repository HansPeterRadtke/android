package com.hans.android.voicebutton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.common_ui.AndroidUi;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class AudioLibraryActivity extends Activity {
    private static final int REQUEST_ROOT = 7301;
    private static final int REQUEST_FILE = 7302;
    private static final int REQUEST_MOVE = 7303;
    private static final String PREFS = "voicebutton_library";
    private static final String ROOT_URI = "root_uri";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LibraryAdapter adapter = new LibraryAdapter();
    private final ArrayList<Uri> directoryStack = new ArrayList<>();

    private ReliableSessionStore store;
    private SharedPreferences preferences;
    private boolean recordingsMode = true;
    private ReliableSessionStore.Folder appFolderFilter;
    private DocumentFile currentDirectory;
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
        try { store = new ReliableSessionStore(this); }
        catch (Exception failure) { Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show(); }
        buildScreen();
        showRecordings();
    }

    @Override protected void onResume() {
        super.onResume();
        if (recordingsMode) showRecordings(); else refreshFiles();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUi.dp(this, 12), AndroidUi.dp(this, 8), AndroidUi.dp(this, 12), AndroidUi.dp(this, 8));
        root.setBackgroundColor(AndroidUi.BG);

        LinearLayout toolbar = row();
        Button back = AndroidUi.button(this, "Back"); back.setOnClickListener(v -> navigateBack());
        Button home = AndroidUi.button(this, "Home"); home.setOnClickListener(v -> home());
        toolbar.addView(back, half()); toolbar.addView(home, half()); root.addView(toolbar);
        root.addView(AndroidUi.title(this, "Player library"));
        stateText = AndroidUi.small(this, "Choose a recording or phone file"); root.addView(stateText);

        LinearLayout modes = row();
        recordingsModeButton = AndroidUi.modeButton(this, "App recordings", true);
        filesModeButton = AndroidUi.modeButton(this, "Phone files", false);
        recordingsModeButton.setOnClickListener(v -> {
            appFolderFilter = null;
            showRecordings();
        });
        filesModeButton.setOnClickListener(v -> showPhoneRoot());
        modes.addView(recordingsModeButton); modes.addView(filesModeButton); root.addView(modes);

        LinearLayout navigation = row();
        upButton = AndroidUi.button(this, "Up"); upButton.setOnClickListener(v -> up());
        pathText = AndroidUi.body(this, "App folders"); pathText.setGravity(Gravity.CENTER_VERTICAL);
        navigation.addView(upButton, new LinearLayout.LayoutParams(AndroidUi.dp(this, 72), AndroidUi.dp(this, 48)));
        navigation.addView(pathText, new LinearLayout.LayoutParams(0, AndroidUi.dp(this, 48), 1f));
        root.addView(navigation);

        list = new ListView(this); list.setAdapter(adapter); list.setDividerHeight(1);
        list.setOnItemClickListener((parent, view, position, id) -> open(adapter.item(position), position));
        list.setOnItemLongClickListener((parent, view, position, id) -> { manage(adapter.item(position)); return true; });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = row();
        Button folders = AndroidUi.button(this, "Folders"); folders.setOnClickListener(v -> manageAppFolders());
        Button choose = AndroidUi.button(this, "Choose phone folder"); choose.setOnClickListener(v -> chooseRoot());
        Button any = AndroidUi.button(this, "Open any file"); any.setOnClickListener(v -> openAnyFile());
        actions.addView(folders, third()); actions.addView(choose, third()); actions.addView(any, third()); root.addView(actions);
        root.addView(AndroidUi.small(this, "Tap to play. Long-press to rename, move or manage. LibVLC attempts every selected local format."));
        setContentView(root);
    }

    private void showRecordings() {
        recordingsMode = true; currentDirectory = null; directoryStack.clear(); updateModeButtons();
        upButton.setEnabled(appFolderFilter != null);
        pathText.setText(appFolderFilter == null ? "App recording folders" : appFolderFilter.name);
        stateText.setText(appFolderFilter == null ? "Loading folders…" : "Loading recordings…");
        ReliableSessionStore.Folder filter = appFolderFilter;
        worker.execute(() -> {
            ArrayList<LibraryItem> items = new ArrayList<>();
            try {
                if (store == null) store = new ReliableSessionStore(this);
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
                    adapter.replace(items);
                    stateText.setText(items.isEmpty()
                            ? (filter == null ? "No app folders" : "No recordings in this folder")
                            : items.size() + (filter == null ? " folders" : " recordings"));
                });
            } catch (Exception failure) {
                runOnUiThread(() -> stateText.setText("Could not load recordings: " + failure.getMessage()));
            }
        });
    }

    private void showPhoneRoot() {
        recordingsMode = false; updateModeButtons();
        String raw = preferences.getString(ROOT_URI, "");
        if (raw == null || raw.isEmpty()) {
            currentDirectory = null; directoryStack.clear(); adapter.replace(new ArrayList<>());
            pathText.setText("No phone folder selected"); stateText.setText("Choose a phone folder or open any file"); upButton.setEnabled(false);
            return;
        }
        DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(raw));
        if (root == null || !root.exists()) {
            preferences.edit().remove(ROOT_URI).apply();
            currentDirectory = null; stateText.setText("The saved folder is no longer available"); return;
        }
        directoryStack.clear(); directoryStack.add(root.getUri()); currentDirectory = root; refreshFiles();
    }

    private void refreshFiles() {
        if (recordingsMode || currentDirectory == null) return;
        DocumentFile directory = currentDirectory;
        pathText.setText(directory.getName() == null ? "Phone files" : directory.getName());
        upButton.setEnabled(directoryStack.size() > 1); stateText.setText("Reading folder…");
        worker.execute(() -> {
            ArrayList<LibraryItem> items = new ArrayList<>();
            try {
                for (DocumentFile file : directory.listFiles()) {
                    items.add(LibraryItem.document(file, directory.getUri()));
                }
                items.sort((left, right) -> {
                    if (left.directory != right.directory) return left.directory ? -1 : 1;
                    return left.title.compareToIgnoreCase(right.title);
                });
                runOnUiThread(() -> { adapter.replace(items); stateText.setText(items.size() + " items"); });
            } catch (Exception failure) {
                runOnUiThread(() -> stateText.setText("Could not read folder: " + failure.getMessage()));
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
            directoryStack.add(item.document.getUri());
            refreshFiles();
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
        Uri uri=directoryStack.get(directoryStack.size()-1);
        currentDirectory=DocumentFile.fromSingleUri(this,uri);
        if(currentDirectory==null)currentDirectory=DocumentFile.fromTreeUri(this,uri);
        refreshFiles();
    }
    private void navigateBack(){if(!recordingsMode&&directoryStack.size()>1)up();else finish();}
    private void home(){Intent intent=new Intent(this,MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);}
    private void updateModeButtons(){recordingsModeButton.setText(recordingsMode?"App recordings ✓":"App recordings");filesModeButton.setText(recordingsMode?"Phone files":"Phone files ✓");}
    private EditText nameInput(String value){EditText input=new EditText(this);input.setSingleLine(true);input.setText(value);input.selectAll();return input;}
    private void error(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}
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
            PlayerSource source=playable?PlayerSource.recording(file,RecordingUi.title(manifest),file.length(),manifest.sessionId,manifest.folderId):null;
            return new LibraryItem(RecordingUi.title(manifest),RecordingUi.humanState(manifest)+" · "+RecordingUi.formatBytes(RecordingUi.recordingBytes(manifest)),false,playable,null,manifest,null,source);
        }
        static LibraryItem document(DocumentFile file,Uri parent){
            String name=file.getName()==null?"Unnamed":file.getName();boolean directory=file.isDirectory();
            PlayerSource source=directory?null:new PlayerSource(file.getUri(),name,PlayerSource.KIND_DOCUMENT,file.length(),"","",parent);
            return new LibraryItem(name,directory?"Folder":(file.getType()==null?"File":file.getType())+" · "+RecordingUi.formatBytes(file.length()),directory,!directory,file,null,null,source);
        }
    }

    private final class LibraryAdapter extends BaseAdapter {
        private final ArrayList<LibraryItem> items=new ArrayList<>();
        void replace(List<LibraryItem> values){items.clear();items.addAll(values);notifyDataSetChanged();}
        LibraryItem item(int position){return items.get(position);}int position(LibraryItem item){return items.indexOf(item);}List<LibraryItem> items(){return new ArrayList<>(items);}
        @Override public int getCount(){return items.size();}@Override public Object getItem(int p){return items.get(p);}@Override public long getItemId(int p){return p;}
        @Override public View getView(int position,View convert,ViewGroup parent){LinearLayout row=convert instanceof LinearLayout?(LinearLayout)convert:new LinearLayout(AudioLibraryActivity.this);row.removeAllViews();row.setOrientation(LinearLayout.VERTICAL);row.setPadding(AndroidUi.dp(AudioLibraryActivity.this,12),AndroidUi.dp(AudioLibraryActivity.this,8),AndroidUi.dp(AudioLibraryActivity.this,12),AndroidUi.dp(AudioLibraryActivity.this,8));LibraryItem item=items.get(position);TextView title=AndroidUi.text(AudioLibraryActivity.this,(item.directory?"Folder · ":"")+item.title,16,true,AndroidUi.INK);TextView detail=AndroidUi.small(AudioLibraryActivity.this,item.detail);row.addView(title);row.addView(detail);row.setBackgroundColor(Color.WHITE);return row;}
    }
}
