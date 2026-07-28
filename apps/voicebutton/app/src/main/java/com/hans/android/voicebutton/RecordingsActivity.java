package com.hans.android.voicebutton;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.common_ui.AndroidUi;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SuppressLint("SetTextI18n")
public final class RecordingsActivity extends Activity {
    private final Handler playerHandler = new Handler(Looper.getMainLooper());
    private final RecordingAdapter adapter = new RecordingAdapter();

    private TextView archiveState;
    private TextView selectedTitle;
    private TextView selectedState;
    private TextView selectedDetail;
    private TextView selectedTranscript;
    private TextView playReason;
    private Button playButton;
    private Button pauseCurrentButton;
    private SeekBar seek;
    private TextView playerTime;
    private ListView list;

    private RecordingService service;
    private boolean bound;
    private RecordingService.Snapshot snapshot = RecordingService.Snapshot.initial();
    private ReliableSessionManifest selected;
    private String selectedSessionId = "";
    private File playerFile;
    private MediaPlayer player;
    private boolean userSeeking;
    private long playerPrepareStartedMs;
    private PhoneDiagnostics diagnostics;

    private final RecordingService.StatusListener statusListener = value ->
            runOnUiThread(() -> render(value));

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RecordingService.LocalBinder) binder).getService();
            bound = true;
            diag(PhoneDiagnostics.INFO, "ui.archive.service_connected", selectedSessionId,
                    "Recordings screen connected to RecordingService", PhoneDiagnostics.fields());
            service.addStatusListener(statusListener);
            render(service.getSnapshot());
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.removeStatusListener(statusListener);
            service = null;
            bound = false;
            diag(PhoneDiagnostics.WARN, "ui.archive.service_disconnected", selectedSessionId,
                    "Recordings screen disconnected from RecordingService", PhoneDiagnostics.fields());
        }
    };

    private final Runnable playerTicker = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            try {
                updatePlayerTime(player.getCurrentPosition(), player.getDuration());
                if (player.isPlaying()) playerHandler.postDelayed(this, 200L);
            } catch (Exception failure) {
                stopPlayer("unspecified");
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnostics = PhoneDiagnostics.get();
        diag(PhoneDiagnostics.INFO, "ui.archive.create", null,
                "RecordingsActivity onCreate", PhoneDiagnostics.fields("has_saved_state", savedInstanceState != null));
        buildScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        diag(PhoneDiagnostics.INFO, "ui.archive.start", selectedSessionId,
                "RecordingsActivity onStart", PhoneDiagnostics.fields());
        bindService(new Intent(this, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        diag(PhoneDiagnostics.INFO, "ui.archive.stop", selectedSessionId,
                "RecordingsActivity onStop", PhoneDiagnostics.fields("player_active", player != null));
        stopPlayer("activity_stop");
        if (bound) {
            if (service != null) service.removeStatusListener(statusListener);
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AndroidUi.BG);
        int pad = AndroidUi.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = AndroidUi.button(this, "Back");
        back.setMinHeight(AndroidUi.dp(this, 48));
        back.setOnClickListener(v -> {
            diag(PhoneDiagnostics.INFO, "ui.archive.back", selectedSessionId,
                    "Back button was pressed on recordings screen", PhoneDiagnostics.fields());
            finish();
        });
        header.addView(back, new LinearLayout.LayoutParams(AndroidUi.dp(this, 92), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = AndroidUi.title(this, "Recordings");
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        archiveState = AndroidUi.body(this, "Loading recording archive");
        root.addView(archiveState);

        LinearLayout selectedCard = AndroidUi.card(this);
        selectedCard.addView(AndroidUi.small(this, "SELECTED RECORDING"));
        selectedTitle = AndroidUi.section(this, "No recording selected");
        selectedState = AndroidUi.body(this, "Create a recording to use the player.");
        selectedDetail = AndroidUi.small(this, "");
        selectedTranscript = AndroidUi.body(this, "No transcript chunks received yet.");
        selectedTranscript.setContentDescription("Selected recording transcript");
        selectedCard.addView(selectedTitle);
        selectedCard.addView(selectedState);
        selectedCard.addView(selectedDetail);
        selectedCard.addView(AndroidUi.small(this, "TRANSCRIPT"));
        selectedCard.addView(selectedTranscript);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout playRow = new LinearLayout(this);
        playRow.setGravity(Gravity.CENTER_VERTICAL);
        playButton = AndroidUi.button(this, "Play");
        playButton.setMinHeight(AndroidUi.dp(this, 52));
        playButton.setEnabled(false);
        playButton.setOnClickListener(v -> togglePlayback());
        playRow.addView(playButton, new LinearLayout.LayoutParams(AndroidUi.dp(this, 104), ViewGroup.LayoutParams.WRAP_CONTENT));
        playerTime = AndroidUi.small(this, "00:00:00 / 00:00:00");
        playerTime.setGravity(Gravity.END);
        playRow.addView(playerTime, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(playRow);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setEnabled(false);
        seek.setContentDescription("Seek selected recording");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    try { updatePlayerTime(progress * player.getDuration() / 1000, player.getDuration()); }
                    catch (Exception ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
                diag(PhoneDiagnostics.DEBUG, "playback.seek_start", selectedSessionId,
                        "Playback seek started", PhoneDiagnostics.fields("progress", bar.getProgress()));
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
                if (player != null) {
                    try {
                        int duration = player.getDuration();
                        int target = bar.getProgress() * duration / 1000;
                        player.seekTo(target);
                        diag(PhoneDiagnostics.INFO, "playback.seek_complete", selectedSessionId,
                                "Playback seek completed",
                                PhoneDiagnostics.fields("target_ms", target,
                                        "duration_ms", duration,
                                        "progress", bar.getProgress()));
                    } catch (Exception failure) {
                        diagError("playback.seek_failed", selectedSessionId,
                                "Seeking selected recording", failure,
                                PhoneDiagnostics.fields("progress", bar.getProgress()));
                        playReason.setText(PhoneDiagnostics.exactFailure("Seeking selected recording", failure)
                                + ". The MP3 file was not changed.");
                    }
                }
            }
        });
        controls.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        selectedCard.addView(controls);

        playReason = AndroidUi.small(this, "Select a playable recording.");
        selectedCard.addView(playReason);
        pauseCurrentButton = AndroidUi.button(this, "Pause current recording to play it");
        pauseCurrentButton.setMinHeight(AndroidUi.dp(this, 50));
        pauseCurrentButton.setVisibility(View.GONE);
        pauseCurrentButton.setOnClickListener(v -> {
            diag(PhoneDiagnostics.INFO, "ui.archive.pause_current", snapshot.currentSessionId,
                    "Pause current recording was pressed from the archive",
                    PhoneDiagnostics.fields("duration_ms", snapshot.durationMs));
            sendAction(RecordingService.ACTION_PAUSE, snapshot.currentSessionId);
        });
        selectedCard.addView(pauseCurrentButton);
        root.addView(selectedCard);

        TextView listTitle = AndroidUi.section(this, "All recordings");
        root.addView(listTitle);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setContentDescription("All local recordings");
        list.setOnItemClickListener((parent, view, position, id) -> {
            ReliableSessionManifest value = adapter.getItem(position);
            selectedSessionId = value.sessionId;
            selected = value;
            diag(PhoneDiagnostics.INFO, "ui.archive.recording_selected", value.sessionId,
                    "A recording was selected in the archive",
                    PhoneDiagnostics.fields("position", position,
                            "state", value.state,
                            "recording_finished", value.recordingFinished,
                            "paused", value.paused,
                            "remote_committed", value.remoteCommitted,
                            "duration_ms", value.totalDurationMs,
                            "segment_count", value.segments.size(),
                            "local_bytes", RecordingUi.recordingBytes(value)));
            adapter.notifyDataSetChanged();
            stopPlayer("selection_changed");
            renderSelected();
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button cleanup = AndroidUi.button(this, "Delete all local audio and cache");
        cleanup.setTextColor(AndroidUi.RED);
        cleanup.setBackground(AndroidUi.round(Color.WHITE, AndroidUi.RED, AndroidUi.dp(this, 14)));
        cleanup.setMinHeight(AndroidUi.dp(this, 52));
        cleanup.setOnClickListener(v -> confirmCleanup());
        root.addView(cleanup);

        setContentView(root);
    }

    private void render(RecordingService.Snapshot value) {
        snapshot = value == null ? RecordingService.Snapshot.initial() : value;
        List<ReliableSessionManifest> recordings = new ArrayList<>(snapshot.sessions);
        recordings.sort(Comparator
                .comparing((ReliableSessionManifest item) -> item.recordingFinished)
                .thenComparing((ReliableSessionManifest a, ReliableSessionManifest b) -> Long.compare(b.createdAt, a.createdAt)));
        adapter.setItems(recordings);

        if (recordings.isEmpty()) {
            selected = null;
            selectedSessionId = "";
            archiveState.setText("No recordings are stored on this phone.");
        } else {
            ReliableSessionManifest match = null;
            for (ReliableSessionManifest item : recordings) if (item.sessionId.equals(selectedSessionId)) match = item;
            if (match == null) match = recordings.get(0);
            selected = match;
            selectedSessionId = match.sessionId;
            archiveState.setText(recordings.size() + (recordings.size() == 1 ? " recording" : " recordings")
                    + " · " + RecordingUi.formatBytes(snapshot.localBytes) + " local storage");
        }

        if (player != null && selected != null
                && snapshot.recording && selected.sessionId.equals(snapshot.currentSessionId)) stopPlayer("selected_recording_became_active_capture");
        adapter.notifyDataSetChanged();
        renderSelected();
    }

    private void renderSelected() {
        if (selected == null) {
            selectedTitle.setText("No recording selected");
            selectedState.setText("Create a recording to use the player.");
            selectedDetail.setText("");
            selectedTranscript.setText("No transcript chunks received yet.");
            setPlayable(null, "No playable recording is available.", false);
            pauseCurrentButton.setVisibility(View.GONE);
            return;
        }

        long shownDuration = selected.sessionId.equals(snapshot.currentSessionId)
                ? Math.max(selected.totalDurationMs, snapshot.durationMs) : selected.totalDurationMs;
        String prefix = selected.recordingFinished ? RecordingUi.date(selected.createdAt) : "Current recording";
        selectedTitle.setText(prefix + " · " + RecordingUi.formatDuration(shownDuration));
        selectedState.setText(RecordingUi.humanState(selected));
        selectedDetail.setText(selected.folderName
                + " · " + RecordingUi.formatBytes(RecordingUi.recordingBytes(selected))
                + " · local chunks " + selected.segments.size()
                + " · server durable " + selected.durableRemoteChunkCount()
                + " · pending " + RecordingUi.formatBytes(selected.pendingRemoteBytes())
                + " · text chunks " + selected.transcriptChunkCount()
                + " · " + selected.selectedInput);
        String transcript = RecordingUi.transcriptText(this, selected);
        selectedTranscript.setText(transcript.isEmpty()
                ? "No transcript chunks received yet." : transcript);

        boolean activeCurrent = snapshot.recording && selected.sessionId.equals(snapshot.currentSessionId);
        File file = RecordingUi.recordingFile(this, selected);
        if (activeCurrent) {
            setPlayable(file, "Pause the current recording before playback so the phone does not record its own speaker.", false);
            pauseCurrentButton.setVisibility(View.VISIBLE);
            return;
        }
        pauseCurrentButton.setVisibility(View.GONE);
        if (file != null && file.isFile() && file.length() > 0L) {
            String reason = selected.recordingFinished
                    ? "The selected local MP3 is ready."
                    : selected.paused
                    ? "The paused recording snapshot is ready. Resume remains available on the recording screen."
                    : "Recovered audio is playable before you choose Continue or Finish.";
            setPlayable(file, reason, true);
        } else {
            String reason = selected.recordingFinished
                    ? "The final MP3 is still being prepared."
                    : selected.paused
                    ? "The paused MP3 snapshot is still being prepared."
                    : "No playable snapshot exists yet; recovery is still preparing the stored segments.";
            setPlayable(file, reason, false);
        }
    }

    private void setPlayable(File file, String reason, boolean enabled) {
        String oldPath = playerFile == null ? "" : playerFile.getAbsolutePath();
        String newPath = file == null ? "" : file.getAbsolutePath();
        if (!oldPath.equals(newPath)) stopPlayer("playable_file_changed");
        playerFile = file;
        playButton.setEnabled(enabled);
        seek.setEnabled(enabled);
        playReason.setText(reason);
        if (!enabled) {
            seek.setProgress(0);
            playerTime.setText("00:00:00 / 00:00:00");
            playButton.setText("Play");
        } else if (player == null) {
            int duration = mediaDuration(file);
            updatePlayerTime(0, duration);
        }
    }

    private void togglePlayback() {
        diag(PhoneDiagnostics.INFO, "playback.button_pressed", selectedSessionId,
                "Playback button was pressed",
                PhoneDiagnostics.fields("player_exists", player != null,
                        "file_exists", playerFile != null && playerFile.isFile(),
                        "file_bytes", playerFile != null && playerFile.isFile() ? playerFile.length() : -1L));
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    int position = player.getCurrentPosition();
                    player.pause();
                    playButton.setText("Play");
                    playerHandler.removeCallbacks(playerTicker);
                    diag(PhoneDiagnostics.INFO, "playback.paused", selectedSessionId,
                            "Playback paused", PhoneDiagnostics.fields("position_ms", position,
                                    "duration_ms", player.getDuration()));
                } else {
                    player.start();
                    playButton.setText("Pause");
                    playerHandler.post(playerTicker);
                    diag(PhoneDiagnostics.INFO, "playback.resumed", selectedSessionId,
                            "Playback resumed", PhoneDiagnostics.fields("position_ms", player.getCurrentPosition(),
                                    "duration_ms", player.getDuration()));
                }
            } catch (Exception failure) {
                String exact = PhoneDiagnostics.exactFailure("Changing playback state", failure)
                        + ". The MP3 file was not changed.";
                diagError("playback.toggle_failed", selectedSessionId,
                        "Changing playback state", failure, PhoneDiagnostics.fields());
                stopPlayer("toggle_failed");
                playReason.setText(exact);
            }
            return;
        }
        if (playerFile == null || !playerFile.isFile()) {
            String exact = "Playback start failed: FileNotFoundException: the selected local MP3 does not exist. No file was deleted.";
            diag(PhoneDiagnostics.ERROR, "playback.file_missing", selectedSessionId,
                    exact, PhoneDiagnostics.fields("path_available", playerFile != null));
            playReason.setText(exact);
            return;
        }
        player = new MediaPlayer();
        playerPrepareStartedMs = android.os.SystemClock.elapsedRealtime();
        try {
            diag(PhoneDiagnostics.INFO, "playback.prepare_start", selectedSessionId,
                    "MediaPlayer preparation started",
                    PhoneDiagnostics.fields("file_name", playerFile.getName(),
                            "bytes", playerFile.length(),
                            "path", playerFile.getAbsolutePath()));
            player.setDataSource(playerFile.getAbsolutePath());
            player.setOnPreparedListener(value -> {
                if (player != value) return;
                long prepareDuration = Math.max(0L,
                        android.os.SystemClock.elapsedRealtime() - playerPrepareStartedMs);
                playButton.setText("Pause");
                seek.setEnabled(true);
                value.start();
                playerHandler.post(playerTicker);
                diag(PhoneDiagnostics.INFO, "playback.started", selectedSessionId,
                        "MediaPlayer prepared and started",
                        PhoneDiagnostics.fields("prepare_duration_ms", prepareDuration,
                                "duration_ms", value.getDuration(),
                                "bytes", playerFile == null ? -1L : playerFile.length()));
            });
            player.setOnCompletionListener(value -> {
                int duration = 0;
                try { duration = value.getDuration(); } catch (Exception ignored) {}
                diag(PhoneDiagnostics.INFO, "playback.completed", selectedSessionId,
                        "Playback completed", PhoneDiagnostics.fields("duration_ms", duration));
                stopPlayer("completed");
                seek.setProgress(0);
                updatePlayerTime(0, mediaDuration(playerFile));
            });
            player.setOnErrorListener((value, what, extra) -> {
                String exact = "Playback failed: MediaPlayer error what=" + what + ", extra=" + extra
                        + ". The MP3 file was not deleted or changed.";
                diag(PhoneDiagnostics.ERROR, "playback.media_error", selectedSessionId,
                        exact, PhoneDiagnostics.fields("what", what, "extra", extra,
                                "prepare_duration_ms", Math.max(0L,
                                        android.os.SystemClock.elapsedRealtime() - playerPrepareStartedMs),
                                "file_bytes", playerFile == null ? -1L : playerFile.length()));
                stopPlayer("media_error");
                playReason.setText(exact);
                return true;
            });
            player.prepareAsync();
        } catch (Exception failure) {
            String exact = PhoneDiagnostics.exactFailure("Opening selected MP3 for playback", failure)
                    + ". The MP3 file was not deleted or changed.";
            diagError("playback.prepare_failed", selectedSessionId,
                    "Opening selected MP3 for playback", failure,
                    PhoneDiagnostics.fields("file_name", playerFile.getName(),
                            "bytes", playerFile.length(),
                            "prepare_duration_ms", Math.max(0L,
                                    android.os.SystemClock.elapsedRealtime() - playerPrepareStartedMs)));
            stopPlayer("prepare_failed");
            playReason.setText(exact);
        }
    }

    private void updatePlayerTime(int position, int duration) {
        if (!userSeeking && duration > 0) seek.setProgress(Math.min(1000, Math.max(0, position * 1000 / duration)));
        playerTime.setText(RecordingUi.formatPlayerTime(position, duration));
    }

    private void stopPlayer(String reason) {
        playerHandler.removeCallbacks(playerTicker);
        if (player != null) {
            int position = -1;
            try { position = player.getCurrentPosition(); } catch (Exception ignored) {}
            diag(PhoneDiagnostics.DEBUG, "playback.player_released", selectedSessionId,
                    "MediaPlayer was released",
                    PhoneDiagnostics.fields("reason", reason, "position_ms", position));
            try { player.release(); } catch (Exception failure) {
                diagError("playback.release_failed", selectedSessionId,
                        "Releasing MediaPlayer", failure, PhoneDiagnostics.fields("reason", reason));
            }
        }
        player = null;
        playButton.setText("Play");
    }

    private void confirmCleanup() {
        diag(PhoneDiagnostics.INFO, "ui.cleanup_pressed", selectedSessionId,
                "Delete all local audio and cache was pressed",
                PhoneDiagnostics.fields("recording", snapshot.recording,
                        "local_bytes", snapshot.localBytes,
                        "session_count", snapshot.sessions.size()));
        if (snapshot.recording) {
            new AlertDialog.Builder(this)
                    .setTitle("Recording is active")
                    .setMessage("Pause or finish the current recording before deleting local files.")
                    .setPositiveButton("OK", (dialog, which) ->
                            diag(PhoneDiagnostics.INFO, "ui.cleanup_blocked_acknowledged", selectedSessionId,
                                    "Cleanup blocked message was acknowledged", PhoneDiagnostics.fields()))
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete every local recording?")
                .setMessage("This permanently deletes all local MP3 recordings, transfer segments, metadata, legacy Voice Button files, and app cache from this phone. Copies already committed on the server are not deleted.")
                .setNegativeButton("Cancel", (dialog, which) ->
                        diag(PhoneDiagnostics.INFO, "ui.cleanup_cancelled", selectedSessionId,
                                "Local cleanup was cancelled", PhoneDiagnostics.fields()))
                .setPositiveButton("Delete local files", (dialog, which) -> {
                    diag(PhoneDiagnostics.WARN, "ui.cleanup_confirmed", selectedSessionId,
                            "Local cleanup was confirmed",
                            PhoneDiagnostics.fields("local_bytes", snapshot.localBytes,
                                    "session_count", snapshot.sessions.size()));
                    stopPlayer("cleanup_confirmed");
                    sendAction(RecordingService.ACTION_DELETE_LOCAL, null);
                })
                .show();
    }

    private void sendAction(String action, String sessionId) {
        diag(PhoneDiagnostics.INFO, "ui.archive.service_action_sent", sessionId,
                "Recordings screen sent a RecordingService action",
                PhoneDiagnostics.fields("action", action));
        Intent intent = new Intent(this, RecordingService.class).setAction(action);
        if (sessionId != null) intent.putExtra(RecordingService.EXTRA_SESSION_ID, sessionId);
        startService(intent);
    }

    private void diag(String level, String event, String sessionId,
                      String message, org.json.JSONObject fields) {
        PhoneDiagnostics value = diagnostics;
        if (value != null) value.log(level, event, sessionId, message, fields);
    }

    private void diagError(String event, String sessionId, String operation,
                           Throwable failure, org.json.JSONObject fields) {
        PhoneDiagnostics value = diagnostics;
        if (value != null) value.error(event, sessionId, operation, failure, fields);
    }

    private static int mediaDuration(File file) {
        if (file == null || !file.isFile()) return 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private final class RecordingAdapter extends BaseAdapter {
        private final List<ReliableSessionManifest> items = new ArrayList<>();

        void setItems(List<ReliableSessionManifest> values) {
            items.clear();
            items.addAll(values);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public ReliableSessionManifest getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                LinearLayout root = new LinearLayout(RecordingsActivity.this);
                root.setOrientation(LinearLayout.VERTICAL);
                int horizontal = AndroidUi.dp(RecordingsActivity.this, 14);
                int vertical = AndroidUi.dp(RecordingsActivity.this, 10);
                root.setPadding(horizontal, vertical, horizontal, vertical);
                TextView title = AndroidUi.body(RecordingsActivity.this, "");
                TextView detail = AndroidUi.small(RecordingsActivity.this, "");
                root.addView(title);
                root.addView(detail);
                row = new Row(root, title, detail);
                root.setTag(row);
            } else row = (Row) convertView.getTag();

            ReliableSessionManifest value = getItem(position);
            boolean selectedRow = value.sessionId.equals(selectedSessionId);
            row.root.setBackground(AndroidUi.round(
                    selectedRow ? Color.rgb(232, 240, 255) : Color.WHITE,
                    selectedRow ? AndroidUi.BLUE : Color.rgb(225, 230, 236),
                    AndroidUi.dp(RecordingsActivity.this, 12)));
            row.title.setText(value.folderName + " · "
                    + (value.recordingFinished ? RecordingUi.date(value.createdAt) : "Current recording"));
            long rowDuration = value.sessionId.equals(snapshot.currentSessionId)
                    ? Math.max(value.totalDurationMs, snapshot.durationMs) : value.totalDurationMs;
            row.detail.setText(RecordingUi.humanState(value) + " · "
                    + RecordingUi.formatDuration(rowDuration) + " · "
                    + value.durableRemoteChunkCount() + "/" + value.segments.size()
                    + " server chunks · " + value.transcriptChunkCount() + " text chunks · "
                    + RecordingUi.formatBytes(RecordingUi.recordingBytes(value)));
            row.root.setContentDescription(row.title.getText() + ", " + row.detail.getText());
            return row.root;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final TextView title;
        final TextView detail;
        Row(LinearLayout root, TextView title, TextView detail) {
            this.root = root;
            this.title = title;
            this.detail = detail;
        }
    }
}
