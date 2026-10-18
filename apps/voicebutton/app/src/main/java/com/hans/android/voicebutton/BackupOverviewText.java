package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import java.util.Collections;
import java.util.List;

/** A calm, truthful overview: never animate upload preparation or expose
 * speculative percentages while the durable server receipt is unsettled. */
final class BackupOverviewText {
    private BackupOverviewText() {}

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
