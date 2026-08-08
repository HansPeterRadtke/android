package com.hans.android.voicebutton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.common_ui.AndroidUi;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("SetTextI18n")
public final class PlayerActivity extends Activity implements PlayerPlaybackService.Listener {
    static final String EXTRA_QUEUE_URIS = "queue_uris";
    static final String EXTRA_QUEUE_TITLES = "queue_titles";
    static final String EXTRA_QUEUE_BYTES = "queue_bytes";
    static final String EXTRA_QUEUE_KINDS = "queue_kinds";
    static final String EXTRA_QUEUE_SESSIONS = "queue_sessions";
    static final String EXTRA_QUEUE_FOLDERS = "queue_folders";
    static final String EXTRA_QUEUE_INDEX = "queue_index";

    private static final int REQUEST_MOVE = 7201;
    private static final int REQUEST_EXPORT = 7202;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService studioExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger studioGeneration = new AtomicInteger();

    private PlayerSettings settings;
    private final PlayerBridge player = new PlayerBridge();
    private PlayerPlaybackService playerService;
    private boolean playerBound;
    private volatile PlayerPlaybackService.Snapshot playerSnapshot =
            PlayerPlaybackService.Snapshot.initial();
    private String lastPlayerError = "";
    private ThorStudioClient studioClient;
    private PlayerSource originalSource;
    private PlayerSource activeSource;
    private boolean studioActive;
    private float studioSpeed = 1f;
    private Future<?> studioFuture;
    private Future<?> waveformFuture;
    private int sourceGeneration;
    private Bitmap waveformBitmap;
    private long waveformBitmapBytes;
    private long logicalDurationMs;
    private boolean userSeeking;
    private boolean userPlaybackIntent;
    private long userPlaybackIntentElapsedMs;
    private PlayerSource pendingExportSource;
    private final ServiceConnection playerConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            PlayerPlaybackService.LocalBinder local =
                    (PlayerPlaybackService.LocalBinder) binder;
            playerService = local.service();
            playerBound = true;
            player.attach(playerService);
            playerService.addListener(PlayerActivity.this);
            VoiceButtonLocalTrace.log(PlayerActivity.this,
                    "ui.player.passive_restore_suppressed",
                    "pending_open", player.hasPendingOpen(),
                    "service_has_source", playerService.hasSource());
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (playerService != null) {
                playerService.removeListener(PlayerActivity.this);
            }
            player.detach();
            playerService = null;
            playerBound = false;
        }
    };

    private ArrayList<String> queueUris = new ArrayList<>();
    private ArrayList<String> queueTitles = new ArrayList<>();
    private ArrayList<Long> queueBytes = new ArrayList<>();
    private ArrayList<String> queueKinds = new ArrayList<>();
    private ArrayList<String> queueSessions = new ArrayList<>();
    private ArrayList<String> queueFolders = new ArrayList<>();
    private int queueIndex = -1;

    private TextView titleText;
    private TextView stateText;
    private TextView timeText;
    private TextView modeText;
    private TextView speedText;
    private TextView studioText;
    private WaveformView waveformView;
    private SeekBar seek;
    private ProgressBar studioProgress;
    private Button playButton;
    private Button backSkipButton;
    private Button forwardSkipButton;
    private Button previousButton;
    private Button nextButton;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updatePosition();
            main.postDelayed(this, 500L);
        }
    };
    private final Runnable sleepStop = () -> {
        if (player != null) player.pause();
        Toast.makeText(this, "Sleep timer stopped playback", Toast.LENGTH_LONG).show();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new PlayerSettings(this);
        studioClient = new ThorStudioClient(this);
        buildScreen();
        loadQueue(getIntent());
        PlayerSource requested = PlayerSource.fromIntent(getIntent());
        if (requested != null) openSource(requested, settings.autoplay);
        scheduleSleepTimer();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); loadQueue(intent);
        PlayerSource requested = PlayerSource.fromIntent(intent);
        if (requested != null) openSource(requested, settings.autoplay);
    }

    @Override protected void onStart() {
        super.onStart();
        main.removeCallbacks(ticker);
        main.post(ticker);
        if (!playerBound) {
            bindService(new Intent(this, PlayerPlaybackService.class),
                    playerConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override protected void onStop() {
        main.removeCallbacks(ticker);
        if (playerBound) {
            if (playerService != null) playerService.removeListener(this);
            unbindService(playerConnection);
            player.detach();
            playerService = null;
            playerBound = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        studioGeneration.incrementAndGet();
        if (studioFuture != null) studioFuture.cancel(true);
        if (waveformFuture != null) waveformFuture.cancel(true);
        studioExecutor.shutdownNow(); fileExecutor.shutdownNow();
        if (waveformBitmap != null) waveformBitmap.recycle();
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
        back.setOnClickListener(v -> finish());
        Button home = AndroidUi.toolbarButton(this, "Home");
        home.setOnClickListener(v -> home());
        toolbar.addView(back, weighted());
        toolbar.addView(home, weighted());
        root.addView(toolbar);

        titleText = AndroidUi.text(this, "Player", 21, true, AndroidUi.INK);
        titleText.setSingleLine(false);
        titleText.setHorizontallyScrolling(false);
        titleText.setEllipsize(null);
        titleText.setPadding(0, AndroidUi.dp(this, 3), 0, AndroidUi.dp(this, 3));
        root.addView(titleText);
        stateText = AndroidUi.small(this, "Choose audio from Library");
        AndroidUi.stableLine(this, stateText, 30);
        root.addView(stateText);

        waveformView = new WaveformView(this);
        waveformView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        waveformView.setAdjustViewBounds(false);
        waveformView.setBackgroundColor(Color.rgb(238, 242, 248));
        waveformView.setContentDescription("Decoded audio waveform. Tap to seek.");
        waveformView.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP
                    && logicalDuration() > 0L) {
                view.performClick();
                float ratio = Math.max(0f, Math.min(1f,
                        event.getX() / Math.max(1f, view.getWidth())));
                long logical = Math.round(logicalDuration() * ratio);
                player.seek(PlayerTimeline.physicalTime(logical,
                        studioActive, studioSpeed));
                return true;
            }
            return true;
        });
        root.addView(waveformView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 94)));

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setContentDescription("Playback position");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value,
                                                    boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                long logical = PlayerTimeline.fromProgress(
                        bar.getProgress(), logicalDuration());
                player.seek(PlayerTimeline.physicalTime(logical,
                        studioActive, studioSpeed));
                userSeeking = false;
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 38)));
        timeText = AndroidUi.text(this, "00:00 / 00:00", 16, true, AndroidUi.INK);
        timeText.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        timeText.setGravity(Gravity.CENTER);
        AndroidUi.stableLine(this, timeText, 30);
        root.addView(timeText);

        LinearLayout transport = row();
        backSkipButton = AndroidUi.secondaryButton(this, "−10s");
        backSkipButton.setOnClickListener(v -> {
            VoiceButtonLocalTrace.log(this, "ui.player.skip_tap", "direction", "back", "seconds", settings.skipBack, "state", playerSnapshot.state);
            player.skip(-settings.skipBack);
        });
        playButton = AndroidUi.primaryButton(this, "Play");
        playButton.setOnClickListener(v -> {
            VoiceButtonLocalTrace.log(this, "ui.player.play_button_tap",
                    "snapshot_state", playerSnapshot.state,
                    "snapshot_playing", playerSnapshot.playing,
                    "snapshot_error", playerSnapshot.error,
                    "source", originalSource == null ? "" : originalSource.title,
                    "active_uri", activeSource == null ? "" : activeSource.uri,
                    "service_bound", player.isAttached(),
                    "service_has_source", player.hasSource(),
                    "pending_open", player.hasPendingOpen());
            if (player.isPlaying()) {
                userPlaybackIntent = false;
                userPlaybackIntentElapsedMs = 0L;
                playButton.setText("Play");
                stateText.setText("Pausing");
            } else {
                userPlaybackIntent = true;
                userPlaybackIntentElapsedMs = android.os.SystemClock.elapsedRealtime();
                playButton.setText("Pause");
                stateText.setText("Starting playback");
            }
            player.playPause();
        });
        forwardSkipButton = AndroidUi.secondaryButton(this, "+10s");
        forwardSkipButton.setOnClickListener(v -> {
            VoiceButtonLocalTrace.log(this, "ui.player.skip_tap", "direction", "forward", "seconds", settings.skipForward, "state", playerSnapshot.state);
            player.skip(settings.skipForward);
        });
        transport.addView(backSkipButton, weighted());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                0, AndroidUi.dp(this, 58), 1.35f);
        playParams.setMargins(AndroidUi.dp(this, 3), AndroidUi.dp(this, 3),
                AndroidUi.dp(this, 3), AndroidUi.dp(this, 3));
        transport.addView(playButton, playParams);
        transport.addView(forwardSkipButton, weighted());
        root.addView(transport);

        LinearLayout queue = row();
        previousButton = AndroidUi.toolbarButton(this, "Previous");
        previousButton.setOnClickListener(v -> changeQueue(-1));
        speedText = AndroidUi.secondaryButton(this, "1.00×");
        speedText.setOnClickListener(v -> showSpeedPresets());
        nextButton = AndroidUi.toolbarButton(this, "Next");
        nextButton.setOnClickListener(v -> changeQueue(1));
        queue.addView(previousButton, weighted());
        queue.addView(speedText, weighted());
        queue.addView(nextButton, weighted());
        root.addView(queue);

        modeText = AndroidUi.small(this, "Studio speed is ready");
        modeText.setGravity(Gravity.CENTER);
        AndroidUi.stableLine(this, modeText, 28);
        root.addView(modeText);
        studioProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        studioProgress.setMax(1000);
        studioProgress.setProgress(0);
        studioProgress.setVisibility(View.INVISIBLE);
        root.addView(studioProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, 8)));
        studioText = AndroidUi.small(this, "Instant playback available");
        studioText.setGravity(Gravity.CENTER);
        AndroidUi.stableLine(this, studioText, 28);
        root.addView(studioText);

        Button library = AndroidUi.secondaryButton(this, "Library");
        library.setOnClickListener(v -> openLibrary());
        root.addView(library, fullWidthButton(50));
        Button more = AndroidUi.toolbarButton(this, "More");
        more.setOnClickListener(v -> showPlayerMenu());
        root.addView(more, fullWidthButton(46));
        setContentView(root);
        updateLabels();
    }

    private LinearLayout.LayoutParams fullWidthButton(int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUi.dp(this, heightDp));
        params.setMargins(0, AndroidUi.dp(this, 3), 0, AndroidUi.dp(this, 3));
        return params;
    }

    private void openSource(PlayerSource source, boolean autoplay) {
        originalSource = source; activeSource = source; studioActive = false; studioSpeed = 1f; logicalDurationMs = 0L;
        int generation = ++sourceGeneration;
        if (waveformFuture != null) waveformFuture.cancel(true);
        if (waveformBitmap != null) { waveformBitmap.recycle(); waveformBitmap = null; }
        waveformBitmapBytes = 0L;
        waveformView.setImageDrawable(null);
        titleText.setText(source.title);
        stateText.setText("Opening " + source.kind);
        VoiceButtonLocalTrace.log(this, "ui.player.open_source",
                "title", source.title,
                "kind", source.kind,
                "uri", source.uri,
                "autoplay", autoplay,
                "queue_index", queueIndex,
                "bytes", source.bytes);
        playerDiagnostic(PhoneDiagnostics.INFO, "player.open", source.title,
                PhoneDiagnostics.fields("kind", source.kind,
                        "uri_scheme", source.uri.getScheme(),
                        "bytes", source.bytes,
                        "activity_thread", Thread.currentThread().getName()));
        player.open(source.uri, autoplay, settings.speed, settings.volume, settings.muted, settings.loop);
        loadWaveform(source, generation);
        applySpeed(); updateQueueButtons();
    }

    private void loadWaveform(PlayerSource source, int generation) {
        waveformFuture = studioExecutor.submit(() -> {
            try {
                File image = studioClient.prepareWaveform(source, (phase, done, total) -> {
                    if (generation == sourceGeneration) {
                        runOnUiThread(() -> stateText.setText(phase));
                    }
                });
                Bitmap bitmap = BitmapFactory.decodeFile(image.getAbsolutePath());
                if (bitmap == null) throw new java.io.IOException("Thor returned an unreadable waveform");
                runOnUiThread(() -> {
                    if (generation != sourceGeneration || isFinishing()) {
                        bitmap.recycle();
                        return;
                    }
                    if (waveformBitmap != null) waveformBitmap.recycle();
                    waveformBitmap = bitmap;
                    waveformBitmapBytes = bitmap.getAllocationByteCount();
                    waveformView.setImageBitmap(bitmap);
                    stateText.setText(playerSnapshot.error.isEmpty()
                            ? playerSnapshot.state : playerSnapshot.error);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation == sourceGeneration) {
                        stateText.setText("Waveform unavailable; playback still works");
                    }
                });
            }
        });
    }

    private void applySpeed() {
        updateLabels();
        if (originalSource == null) return;
        if (settings.speedMode.equals(PlayerSettings.MODE_INSTANT) || Math.abs(settings.speed - 1f) < .0001f) {
            cancelStudio();
            if (studioActive) switchToOriginal(); else player.setSpeed(settings.speed);
            modeText.setText("Instant · LibVLC pitch-preserving time stretch");
        } else {
            player.setSpeed(settings.speed);
            modeText.setText("Studio · preparing Rubber Band R3 fine at " + formatSpeed(settings.speed));
            prepareStudio(settings.speed);
        }
    }

    private void prepareStudio(float speed) {
        cancelStudio();
        int generation = studioGeneration.incrementAndGet();
        PlayerSource source = originalSource;
        studioProgress.setVisibility(View.VISIBLE); studioProgress.setIndeterminate(true);
        studioText.setText("Preparing studio audio… instant playback remains available");
        studioFuture = studioExecutor.submit(() -> {
            try {
                ThorStudioClient.Result result = studioClient.prepare(source, speed, (phase, done, total) ->
                        runOnUiThread(() -> showStudioProgress(generation, phase, done, total)));
                runOnUiThread(() -> {
                    if (generation != studioGeneration.get() || isFinishing()) return;
                    long logical = logicalPosition(); boolean playing = player.isPlaying();
                    activeSource = new PlayerSource(Uri.fromFile(result.file), source.title,
                            PlayerSource.KIND_STUDIO, result.file.length(), source.sessionId, source.folderId, null);
                    studioActive = true; studioSpeed = result.speed;
                    player.open(activeSource.uri, true, 1f, settings.volume, settings.muted, settings.loop);
                    main.postDelayed(() -> {
                        player.seek(PlayerTimeline.physicalTime(logical, true, studioSpeed));
                        if (!playing) player.pause();
                    }, 350L);
                    studioProgress.setVisibility(View.INVISIBLE);
                    studioText.setText(result.engine + " · exact " + formatSpeed(result.speed));
                    modeText.setText("Studio · " + result.engine);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation != studioGeneration.get()) return;
                    studioProgress.setVisibility(View.INVISIBLE);
                    studioText.setText("Studio unavailable: " + failure.getMessage() + " · using instant mode");
                    modeText.setText("Instant fallback · pitch preserved");
                });
            }
        });
    }

    private void showStudioProgress(int generation, String phase, long done, long total) {
        if (generation != studioGeneration.get()) return;
        studioText.setText(phase);
        if (total > 0L) {
            studioProgress.setIndeterminate(false);
            studioProgress.setProgress((int)Math.min(1000L, done * 1000L / total));
        } else studioProgress.setIndeterminate(true);
    }

    private void switchToOriginal() {
        long logical = logicalPosition(); boolean playing = player.isPlaying();
        studioActive = false; studioSpeed = 1f; activeSource = originalSource;
        player.open(originalSource.uri, true, settings.speed, settings.volume, settings.muted, settings.loop);
        main.postDelayed(() -> { player.seek(logical); if (!playing) player.pause(); }, 350L);
    }

    private void cancelStudio() {
        studioGeneration.incrementAndGet(); if (studioFuture != null) studioFuture.cancel(true); studioFuture = null;
        studioProgress.setVisibility(View.INVISIBLE);
    }

    private void changeSpeed(int direction) { settings.adjust(direction); applySpeed(); }

    private void showSpeedPresets() {
        String[] labels = new String[settings.presets.size() + 2];
        labels[0] = "Normal 1.00×"; labels[1] = "Advanced speed settings";
        for (int i=0;i<settings.presets.size();i++) labels[i+2]=formatSpeed(settings.presets.get(i));
        new AlertDialog.Builder(this).setTitle("Playback speed").setItems(labels,(d,which)->{
            if(which==0)settings.speed=1f; else if(which==1){showSettings();return;} else settings.speed=settings.presets.get(which-2);
            settings.save(); applySpeed();
        }).setNegativeButton("Back",null).show();
    }

    private void showPlayerMenu() {
        String[] actions = {
                "Player settings",
                "File actions",
                "Memory and engine",
                "Clear studio cache",
                "About player"
        };
        new AlertDialog.Builder(this).setTitle("More")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showSettings();
                    else if (which == 1) showFileActions();
                    else if (which == 2) showMemory();
                    else if (which == 3) clearStudioCache();
                    else showPlayerAbout();
                }).setNegativeButton("Back", null).show();
    }

    private void showPlayerAbout() {
        new AlertDialog.Builder(this).setTitle("Player")
                .setMessage("LibVLC provides broad-format playback. Studio speed uses Rubber Band R3 fine rendering on Thor. Detailed controls are hidden from the playback overview by design.")
                .setPositiveButton("Back", null).show();
    }

    private void showSettings() {
        ScrollView scroll=new ScrollView(this); LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int p=AndroidUi.dp(this,16);box.setPadding(p,p,p,p);scroll.addView(box);
        EditText speed=field(box,"Current speed",settings.speed); EditText min=field(box,"Minimum speed",settings.speedMin); EditText max=field(box,"Maximum speed",settings.speedMax); EditText step=field(box,"Speed step",settings.speedStep);
        EditText back=field(box,"Skip backward seconds",settings.skipBack); EditText forward=field(box,"Skip forward seconds",settings.skipForward); EditText presets=textField(box,"Speed presets, comma separated",settings.presetsText());
        EditText volume=field(box,"Volume 0 to 100",settings.volume); EditText sleep=field(box,"Sleep timer minutes, zero disables",settings.sleepMinutes);
        RadioGroup modes=new RadioGroup(this);RadioButton studio=radio("Studio — Rubber Band R3 fine",PlayerSettings.MODE_STUDIO.equals(settings.speedMode));RadioButton instant=radio("Instant — LibVLC pitch preserving",PlayerSettings.MODE_INSTANT.equals(settings.speedMode));modes.addView(studio);modes.addView(instant);box.addView(modes);
        CheckBox muted=check("Muted",settings.muted),loop=check("Loop current file",settings.loop),autoplay=check("Autoplay next file",settings.autoplay);box.addView(muted);box.addView(loop);box.addView(autoplay);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Player settings").setView(scroll).setNegativeButton("Back",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                float newMin=parse(min),newMax=parse(max),newStep=parse(step);if(newMin<.25f||newMax>8f||newMin>newMax||newStep<.01f||newStep>1f)throw new Exception("Speed range must stay within 0.25× to 8×; step 0.01 to 1.0");
                settings.speedMin=newMin;settings.speedMax=newMax;settings.speedStep=newStep;settings.speed=settings.normalize(parse(speed));settings.skipBack=PlayerSettings.clamp(parse(back),.1f,3600f);settings.skipForward=PlayerSettings.clamp(parse(forward),.1f,3600f);settings.volume=Math.max(0,Math.min(100,Math.round(parse(volume))));settings.sleepMinutes=Math.max(0,Math.min(1440,Math.round(parse(sleep))));settings.muted=muted.isChecked();settings.loop=loop.isChecked();settings.autoplay=autoplay.isChecked();settings.speedMode=studio.isChecked()?PlayerSettings.MODE_STUDIO:PlayerSettings.MODE_INSTANT;settings.setPresets(presets.getText().toString());settings.save();player.setVolume(settings.volume,settings.muted);player.setLoop(settings.loop);player.updateSkipValues(settings.skipBack,settings.skipForward);scheduleSleepTimer();applySpeed();dialog.dismiss();
            }catch(Exception failure){Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show();}
        }));dialog.show();
    }

    private void showMemory() {
        PlayerMemorySnapshot memory=PlayerMemorySnapshot.capture(this);
        String engine=player.technicalSummary()+(studioActive?"\nStudio: Rubber Band R3 fine at "+formatSpeed(studioSpeed):"");
        new AlertDialog.Builder(this).setTitle("Player memory and engine")
                .setMessage(memory.describe(activeSource==null?0L:activeSource.bytes,
                        ThorStudioClient.cacheBytes(this), waveformBitmapBytes, engine))
                .setPositiveButton("Refresh",(d,w)->showMemory())
                .setNeutralButton("Clear studio cache",(d,w)->clearStudioCache())
                .setNegativeButton("Back",null).show();
    }

    private void showFileActions() {
        if (originalSource==null){Toast.makeText(this,"Select a file first",Toast.LENGTH_SHORT).show();return;}
        String[] actions = studioActive
                ? new String[]{"Export studio WAV", "Export original", "Rename original", "Move original", "File details"}
                : new String[]{"Export current file", "Rename", "Move", "File details"};
        new AlertDialog.Builder(this).setTitle("File actions").setItems(actions,(d,w)->{
            if (studioActive) {
                if (w==0) exportSource(activeSource);
                else if (w==1) exportSource(originalSource);
                else if (w==2) renameCurrent();
                else if (w==3) moveCurrent();
                else showFileDetails();
            } else {
                if(w==0) exportSource(originalSource);
                else if(w==1) renameCurrent();
                else if(w==2) moveCurrent();
                else showFileDetails();
            }
        }).setNegativeButton("Back",null).show();
    }

    private void renameCurrent() {
        EditText input=new EditText(this);input.setSingleLine(true);input.setText(originalSource.title);input.selectAll();
        new AlertDialog.Builder(this).setTitle("Rename").setView(input).setNegativeButton("Back",null).setPositiveButton("Rename",(d,w)->{
            String name=input.getText().toString();fileExecutor.execute(()->{
                try{
                    if(PlayerSource.KIND_RECORDING.equals(originalSource.kind)){
                        ReliableSessionStore store=new ReliableSessionStore(this);ReliableSessionManifest changed=store.renameSession(originalSource.sessionId,name);File file=RecordingUi.recordingFile(this,changed);PlayerSource updated=PlayerSource.recording(file,RecordingUi.title(changed),file.length(),changed.sessionId,changed.folderId);runOnUiThread(()->openSource(updated,false));
                    }else{
                        Uri updated=FileOperations.rename(this,originalSource.uri,name);PlayerSource source=new PlayerSource(updated,name,originalSource.kind,originalSource.bytes,originalSource.sessionId,originalSource.folderId,originalSource.parentUri);runOnUiThread(()->openSource(source,false));
                    }
                }catch(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}
            });
        }).show();
    }

    private void moveCurrent() {
        if(PlayerSource.KIND_RECORDING.equals(originalSource.kind)){showRecordingFolderMove();return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,REQUEST_MOVE);
    }

    private void showRecordingFolderMove() {
        fileExecutor.execute(()->{
            try{
                ReliableSessionStore store=new ReliableSessionStore(this);java.util.List<ReliableSessionStore.Folder> folders=store.listFolders();String[] names=new String[folders.size()];for(int i=0;i<folders.size();i++)names[i]=folders.get(i).name;
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Move recording").setItems(names,(d,w)->fileExecutor.execute(()->{
                    try{ReliableSessionManifest changed=store.moveSession(originalSource.sessionId,folders.get(w).id);File file=RecordingUi.recordingFile(this,changed);PlayerSource updated=PlayerSource.recording(file,RecordingUi.title(changed),file.length(),changed.sessionId,changed.folderId);runOnUiThread(()->openSource(updated,false));}catch(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}
                })).setNegativeButton("Back",null).show());
            }catch(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}
        });
    }

    private void exportSource(PlayerSource source) {
        pendingExportSource = source;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType("audio/*");
        String title = PlayerSource.KIND_STUDIO.equals(source.kind)
                ? stripExtension(originalSource.title) + " " + formatSpeed(studioSpeed) + ".wav"
                : source.title;
        intent.putExtra(Intent.EXTRA_TITLE,title);startActivityForResult(intent,REQUEST_EXPORT);
    }

    private void showFileDetails() {
        String message = "Original type: " + originalSource.kind
                + "\nOriginal URI: " + originalSource.uri
                + "\nOriginal size: " + RecordingUi.formatBytes(originalSource.bytes)
                + "\nFolder: " + originalSource.folderId;
        if (studioActive) {
            message += "\n\nCurrent playback: studio WAV " + formatSpeed(studioSpeed)
                    + "\nStudio size: " + RecordingUi.formatBytes(activeSource.bytes);
        }
        new AlertDialog.Builder(this).setTitle(originalSource.title)
                .setMessage(message).setPositiveButton("Back", null).show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;Uri target=data.getData();FileOperations.persistPermissions(this, target, data.getFlags());
        if(requestCode==REQUEST_MOVE){PlayerSource moving=originalSource;fileExecutor.execute(()->{try{Uri moved=FileOperations.move(this,moving.uri,target,moving.title);PlayerSource updated=new PlayerSource(moved,moving.title,moving.kind,moving.bytes,moving.sessionId,moving.folderId,target);runOnUiThread(()->openSource(updated,false));}catch(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}});}
        else if(requestCode==REQUEST_EXPORT){PlayerSource exporting=pendingExportSource;pendingExportSource=null;if(exporting==null)return;fileExecutor.execute(()->{try{FileOperations.copy(this,exporting.uri,target);runOnUiThread(()->Toast.makeText(this,"Export complete",Toast.LENGTH_LONG).show());}catch(Exception failure){runOnUiThread(()->Toast.makeText(this,failure.getMessage(),Toast.LENGTH_LONG).show());}});}
    }

    private void clearStudioCache() {
        if (studioActive) switchToOriginal();
        cancelStudio();
        fileExecutor.execute(() -> {
            try {
                ThorStudioClient.clearCache(this);
                runOnUiThread(() -> Toast.makeText(this, "Studio cache cleared", Toast.LENGTH_LONG).show());
            } catch (Exception failure) {
                runOnUiThread(() -> Toast.makeText(this, failure.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openLibrary() { startActivity(new Intent(this, AudioLibraryActivity.class)); }
    private void home() { Intent intent=new Intent(this,MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent); }

    private void loadQueue(Intent intent) {
        queueUris=list(intent.getStringArrayListExtra(EXTRA_QUEUE_URIS));queueTitles=list(intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES));queueKinds=list(intent.getStringArrayListExtra(EXTRA_QUEUE_KINDS));queueSessions=list(intent.getStringArrayListExtra(EXTRA_QUEUE_SESSIONS));queueFolders=list(intent.getStringArrayListExtra(EXTRA_QUEUE_FOLDERS));
        long[] raw=intent.getLongArrayExtra(EXTRA_QUEUE_BYTES);queueBytes=new ArrayList<>();if(raw!=null)for(long value:raw)queueBytes.add(value);queueIndex=intent.getIntExtra(EXTRA_QUEUE_INDEX,-1);updateQueueButtons();
    }
    private static ArrayList<String> list(ArrayList<String> input){return input==null?new ArrayList<>():input;}
    private void changeQueue(int delta){if(delta<0)player.previous();else player.next();}
    private void updateQueueButtons(){if(previousButton!=null){previousButton.setEnabled(playerSnapshot.queueIndex>0);nextButton.setEnabled(playerSnapshot.queueIndex>=0&&playerSnapshot.queueIndex+1<playerSnapshot.queueSize);}}

    @Override public void onPlayerSnapshot(PlayerPlaybackService.Snapshot value) {
        runOnUiThread(() -> applyPlayerSnapshot(value));
    }

    private void requestRestoreAndPlay() {
        stateText.setText("Restoring last file");
        ContextCompat.startForegroundService(this,
                new Intent(this, PlayerPlaybackService.class)
                        .setAction(PlayerPlaybackService.ACTION_RESTORE_PLAY));
        if (!playerBound) {
            bindService(new Intent(this, PlayerPlaybackService.class),
                    playerConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void applyPlayerSnapshot(PlayerPlaybackService.Snapshot value) {
        if (value == null) return;
        VoiceButtonLocalTrace.log(this, "ui.player.snapshot",
                "state", value.state,
                "playing", value.playing,
                "time", value.physicalTimeMs,
                "length", value.physicalLengthMs,
                "rate", value.rate,
                "source", value.originalSource == null ? "" : value.originalSource.title,
                "uri", value.activeSource == null ? "" : value.activeSource.uri,
                "error", value.error,
                "engine", value.engineSummary);
        if (value.originalSource == null && originalSource != null
                && player.hasPendingOpen()) {
            stateText.setText("Opening " + originalSource.kind);
            return;
        }
        PlayerSource previous = originalSource;
        playerSnapshot = value;
        originalSource = value.originalSource;
        activeSource = value.activeSource;
        studioActive = value.studioActive;
        studioSpeed = value.studioSpeed;
        logicalDurationMs = value.logicalLengthMs();
        if (originalSource != null) {
            titleText.setText(originalSource.title);
            if (previous == null || !previous.uri.equals(originalSource.uri)) {
                int generation = ++sourceGeneration;
                if (waveformFuture != null) waveformFuture.cancel(true);
                if (waveformBitmap != null) {
                    waveformBitmap.recycle();
                    waveformBitmap = null;
                }
                waveformBitmapBytes = 0L;
                waveformView.setImageDrawable(null);
                loadWaveform(originalSource, generation);
            }
        }
        if (!value.error.isEmpty() || "stopped".equals(value.state)
                || "ended".equals(value.state)) {
            userPlaybackIntent = false;
            userPlaybackIntentElapsedMs = 0L;
        } else if (value.playing) {
            userPlaybackIntent = true;
            userPlaybackIntentElapsedMs = 0L;
        } else if (userPlaybackIntent && userPlaybackIntentElapsedMs > 0L
                && android.os.SystemClock.elapsedRealtime()
                - userPlaybackIntentElapsedMs > 8000L) {
            userPlaybackIntent = false;
            userPlaybackIntentElapsedMs = 0L;
        }
        boolean optimisticPlayback = userPlaybackIntent
                && value.error.isEmpty()
                && (PlayerTerminalPolicy.startIsPending(value.state)
                || "ready".equals(value.state)
                || "paused".equals(value.state));
        String visibleState = value.error.isEmpty()
                ? optimisticPlayback ? "Starting playback" : value.state
                : value.error;
        stateText.setText(visibleState);
        playButton.setText((value.playing || optimisticPlayback) ? "Pause" : "Play");
        previousButton.setEnabled(value.queueIndex > 0);
        nextButton.setEnabled(value.queueIndex >= 0
                && value.queueIndex + 1 < value.queueSize);
        if (!value.error.isEmpty() && !value.error.equals(lastPlayerError)) {
            lastPlayerError = value.error;
            playerDiagnostic(PhoneDiagnostics.ERROR, "player.error", value.error,
                    PhoneDiagnostics.fields("source",
                            originalSource == null ? "" : originalSource.title,
                            "engine", value.engineSummary));
            Toast.makeText(this, value.error, Toast.LENGTH_LONG).show();
        }
        updatePosition();
    }

    private void updatePosition(){long logical=logicalPosition(),length=logicalDuration();timeText.setText(formatTime(logical)+" / "+formatTime(length));if(!userSeeking)seek.setProgress(PlayerTimeline.progress(logical,length));boolean optimistic=userPlaybackIntent&&playerSnapshot.error.isEmpty()&&(PlayerTerminalPolicy.startIsPending(playerSnapshot.state)||"ready".equals(playerSnapshot.state)||"paused".equals(playerSnapshot.state));playButton.setText(player.isPlaying()||optimistic?"Pause":"Play");}
    private long logicalPosition(){return playerSnapshot.logicalTimeMs();}
    private long logicalDuration(){long value=playerSnapshot.logicalLengthMs();return value>0?value:logicalDurationMs;}
    private void updateLabels(){speedText.setText(formatSpeed(settings.speed));backSkipButton.setText("−"+formatSeconds(settings.skipBack));forwardSkipButton.setText("+"+formatSeconds(settings.skipForward));}
    private void scheduleSleepTimer(){main.removeCallbacks(sleepStop);if(settings.sleepMinutes>0)main.postDelayed(sleepStop,settings.sleepMinutes*60_000L);}

    private List<PlayerSource> queueSources() {
        ArrayList<PlayerSource> result = new ArrayList<>();
        for (int i = 0; i < queueUris.size(); i++) {
            result.add(new PlayerSource(Uri.parse(queueUris.get(i)),
                    i < queueTitles.size() ? queueTitles.get(i) : "Audio",
                    i < queueKinds.size() ? queueKinds.get(i)
                            : PlayerSource.KIND_DOCUMENT,
                    i < queueBytes.size() ? queueBytes.get(i) : 0L,
                    i < queueSessions.size() ? queueSessions.get(i) : "",
                    i < queueFolders.size() ? queueFolders.get(i) : "", null));
        }
        return result;
    }

    private final class PlayerBridge {
        private PlayerPlaybackService service;
        private boolean pendingOpen;
        private boolean pendingAutoplay;

        void attach(PlayerPlaybackService value) {
            VoiceButtonLocalTrace.log(PlayerActivity.this, "ui.player.service_attach",
                    "pending_open", pendingOpen,
                    "source", originalSource == null ? "" : originalSource.title,
                    "active_uri", activeSource == null ? "" : activeSource.uri);
            service = value;
            if (pendingOpen) {
                boolean autoplay = pendingAutoplay;
                pendingOpen = false;
                open(activeSource == null ? null : activeSource.uri,
                        autoplay, settings.speed, settings.volume,
                        settings.muted, settings.loop);
            }
        }

        void detach() {
            VoiceButtonLocalTrace.log(PlayerActivity.this, "ui.player.service_detach",
                    "source", originalSource == null ? "" : originalSource.title,
                    "state", playerSnapshot.state);
            service = null;
        }
        boolean hasPendingOpen() { return pendingOpen; }
        boolean isAttached() { return service != null; }
        boolean hasSource() { return service != null && service.hasSource(); }

        void open(Uri uri, boolean autoplay, float speed, int volume,
                  boolean muted, boolean loop) {
            if (originalSource == null || activeSource == null) {
                VoiceButtonLocalTrace.log(PlayerActivity.this, "ui.player.bridge_open_no_source",
                        "autoplay", autoplay,
                        "service_bound", service != null,
                        "pending_open", pendingOpen);
                return;
            }
            if (service == null) {
                VoiceButtonLocalTrace.log(PlayerActivity.this, "ui.player.bridge_open_pending",
                        "source", originalSource.title,
                        "uri", activeSource.uri,
                        "autoplay", autoplay);
                pendingOpen = true;
                pendingAutoplay = autoplay;
                ContextCompat.startForegroundService(PlayerActivity.this,
                        new Intent(PlayerActivity.this,
                                PlayerPlaybackService.class)
                                .setAction(PlayerPlaybackService.ACTION_ACTIVATE));
                if (!playerBound) {
                    bindService(new Intent(PlayerActivity.this,
                                    PlayerPlaybackService.class),
                            playerConnection, Context.BIND_AUTO_CREATE);
                }
                return;
            }
            ContextCompat.startForegroundService(PlayerActivity.this,
                    new Intent(PlayerActivity.this,
                            PlayerPlaybackService.class)
                            .setAction(PlayerPlaybackService.ACTION_ACTIVATE));
            VoiceButtonLocalTrace.log(PlayerActivity.this, "ui.player.bridge_service_open",
                    "source", originalSource.title,
                    "uri", activeSource.uri,
                    "autoplay", autoplay,
                    "speed", speed,
                    "queue_index", queueIndex);
            service.open(originalSource, activeSource, queueSources(), queueIndex,
                    0L, autoplay, studioActive, studioSpeed, speed,
                    volume, muted, loop, settings.skipBack,
                    settings.skipForward, settings.autoplay);
        }

        void playPause(){
            VoiceButtonLocalTrace.log(PlayerActivity.this, "ui.player.bridge_play_pause",
                    "source", originalSource == null ? "" : originalSource.title,
                    "active_uri", activeSource == null ? "" : activeSource.uri,
                    "service_bound", service != null,
                    "has_source", service != null && service.hasSource(),
                    "state", playerSnapshot.state,
                    "playing", playerSnapshot.playing);
            if (originalSource == null || activeSource == null) {
                requestRestoreAndPlay();
                return;
            }
            if (service == null || !service.hasSource()) {
                open(activeSource.uri, true, settings.speed, settings.volume,
                        settings.muted, settings.loop);
            } else service.playPause();
        }
        void play(){
            if (originalSource == null || activeSource == null) {
                requestRestoreAndPlay();
                return;
            }
            if (service == null || !service.hasSource()) {
                open(activeSource.uri, true, settings.speed, settings.volume,
                        settings.muted, settings.loop);
            } else service.play();
        }
        void pause(){if(service!=null)service.pause();}
        void stop(){if(service!=null)service.stopPlayback();}
        void seek(long value){if(service!=null)service.seekPhysical(value);}
        void skip(float value){if(service!=null)service.skip(value);}
        void setSpeed(float value){if(service!=null)service.setSpeed(value);}
        void setVolume(int value,boolean muted){if(service!=null)service.setVolume(value,muted);}
        void setLoop(boolean value){if(service!=null)service.setLoop(value);}
        void updateSkipValues(float back,float forward){if(service!=null)service.updateSkipValues(back,forward);}
        void previous(){if(service!=null)service.previous();}
        void next(){if(service!=null)service.next();}
        boolean isPlaying(){return playerSnapshot.playing;}
        boolean isSeekable(){return playerSnapshot.seekable;}
        long time(){return playerSnapshot.physicalTimeMs;}
        long length(){return playerSnapshot.physicalLengthMs;}
        float rate(){return playerSnapshot.rate;}
        String technicalSummary(){return playerSnapshot.engineSummary;}
    }

    private void playerDiagnostic(String level, String event, String message,
                                  org.json.JSONObject fields) {
        PhoneDiagnostics value = PhoneDiagnostics.get();
        if (value != null) value.log(level, event, null, message, fields);
    }

    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER);return row;}
    private LinearLayout.LayoutParams weighted(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,AndroidUi.dp(this,52),1f);p.setMargins(AndroidUi.dp(this,2),AndroidUi.dp(this,2),AndroidUi.dp(this,2),AndroidUi.dp(this,2));return p;}
    private Button compact(String text){return AndroidUi.toolbarButton(this,text);}
    private EditText field(LinearLayout box,String label,float value){return textField(box,label,String.format(Locale.US,"%g",value));}
    private EditText textField(LinearLayout box,String label,String value){box.addView(AndroidUi.small(this,label));EditText input=new EditText(this);input.setSingleLine(true);input.setText(value);box.addView(input);return input;}
    private RadioButton radio(String label,boolean checked){RadioButton b=new RadioButton(this);b.setId(View.generateViewId());b.setText(label);b.setChecked(checked);return b;}
    private CheckBox check(String label,boolean checked){CheckBox b=new CheckBox(this);b.setText(label);b.setChecked(checked);return b;}
    private static float parse(EditText input)throws Exception{return Float.parseFloat(input.getText().toString().trim().replace(',','.'));}
    private static String stripExtension(String value){int index=value==null?-1:value.lastIndexOf('.');return index>0?value.substring(0,index):String.valueOf(value);}
    private static String formatSpeed(float speed){return String.format(Locale.US,"%.2f×",speed);}
    private static String formatSeconds(float seconds){return Math.abs(seconds-Math.round(seconds))<.001f?Math.round(seconds)+"s":String.format(Locale.US,"%.1fs",seconds);}
    private static String formatTime(long millis){long total=Math.max(0,millis/1000),hours=total/3600,minutes=(total%3600)/60,seconds=total%60;return hours>0?String.format(Locale.US,"%d:%02d:%02d",hours,minutes,seconds):String.format(Locale.US,"%02d:%02d",minutes,seconds);}
}
