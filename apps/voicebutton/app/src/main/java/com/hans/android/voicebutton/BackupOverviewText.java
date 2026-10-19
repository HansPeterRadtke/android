package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import java.util.Collections;
import java.util.List;

/** A calm, truthful overview: never animate upload preparation or expose
 * speculative percentages while the durable server receipt is unsettled. */
final class BackupOverviewText {
    private BackupOverviewText() {}

    /** A value is never a forecast of future open-recording bytes.  The
     * denominator is the MP3 data already encoded and listed in the ledger. */
    static String durableProgress(long durableBytes, long knownBytes,
                                  int unfinishedFiles, boolean openRecording,
                                  boolean encodingUnknown, boolean permitted,
                                  String operation) {
        if (knownBytes <= 0L) {
            if (encodingUnknown) return "Backup · finalizing audio locally; byte total not yet known";
            if (openRecording) return "Backup · current recording safe locally; upload after pause";
            if (unfinishedFiles > 0) return "Backup · awaiting locally encoded audio";
            return "Backup · no pending audio";
        }
        long durable = Math.max(0L, Math.min(knownBytes, durableBytes));
        int percent = RecordingFeedback.uploadPermille(durable, knownBytes) / 10;
        // Keep the essential values on the first line so they cannot be
        // ellipsized behind a long, changing internal-operation description.
        String label = "Backup " + percent + "% · "
                + RecordingUi.formatBytes(durable) + " / "
                + RecordingUi.formatBytes(knownBytes) + " on Jetson";
        String state;
        if (!permitted) state = "Upload paused · audio kept on phone";
        else if ("paused_permanent".equals(operation)
                || "session_quarantined".equals(operation)) {
            state = "Server backup needs attention";
        } else if ("retry_backoff".equals(operation)) {
            state = "Retry queued · audio safe on phone";
        } else if (encodingUnknown) state = "More MP3 audio is finalizing";
        else if (durable >= knownBytes && unfinishedFiles > 0) {
            state = "Final server commit still pending";
        } else if (openRecording) state = "Current recording local until paused or finished";
        else if (unfinishedFiles > 0) {
            state = unfinishedFiles + (unfinishedFiles == 1
                    ? " recording waiting" : " recordings waiting");
        } else state = "Known audio backed up";
        return label + "\n" + state;
    }

    static String livePcmSummary(boolean paired, boolean permitted,
                                 long confirmed, long durable,
                                 String currentState) {
        if (!permitted) return "Live backup paused · PCM protected on phone";
        if (!paired) return "Live backup needs server pairing · More → Recording server access";
        if (durable <= 0) return "Live backup · waiting for first locally durable PCM block";
        long acknowledged = Math.max(0L, Math.min(confirmed, durable));
        int percent = RecordingFeedback.uploadPermille(acknowledged, durable) / 10;
        String state = currentState == null ? "" : currentState.trim();
        return "Live PCM " + percent + "% · "
                + RecordingUi.formatBytes(acknowledged) + " / "
                + RecordingUi.formatBytes(durable) + " verified on Jetson"
                + (state.isEmpty() ? "" : "\n" + state);
    }

    static String currentFile(String fileName, int progressPermille,
                              boolean stillRecording) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) return "";
        if (progressPermille < 0) return "File · " + name
                + (stillRecording ? " · recording locally; upload not started"
                        : " · preparing local MP3");
        int percent = Math.max(0, Math.min(1000, progressPermille)) / 10;
        return "File · " + name + " · " + percent + "% confirmed"
                + (stillRecording ? " of available chunks (new audio remains local)" : "");
    }

    static String summary(List<ReliableSessionManifest> sessions,
                          boolean permitted, boolean pausedByExit,
                          String liveOperation, String liveSessionId) {
        List<ReliableSessionManifest> pending = OverviewProgress.pendingUploads(
                sessions, Collections.emptySet());
        int count = pending.size();
        if (count == 0) return "";
        String suffix = count == 1 ? " recording" : " recordings";
        String saved = count + suffix + " saved on phone";
        if (pausedByExit || !permitted) {
            return "Backup paused · " + saved;
        }
        if ("retry_backoff".equals(liveOperation)) {
            return "Backup delayed · " + saved;
        }
        if ("paused_permanent".equals(liveOperation)
                || "waiting_quarantined_recordings".equals(liveOperation)
                || "session_quarantined".equals(liveOperation)) {
            return "Backup needs attention · " + saved;
        }
        boolean active = false;
        if ("upload_chunk".equals(liveOperation)
                || "upload_part".equals(liveOperation)
                || "chunk_complete".equals(liveOperation)
                || "commit".equals(liveOperation)
                || "reconcile".equals(liveOperation)) {
            if (liveSessionId != null && !liveSessionId.isEmpty()) {
                for (ReliableSessionManifest item : pending) {
                    if (liveSessionId.equals(item.sessionId)) {
                        active = true;
                        break;
                    }
                }
            }
        }
        return active ? "Backing up " + count + suffix
                : count + suffix + " awaiting backup";
    }
}
