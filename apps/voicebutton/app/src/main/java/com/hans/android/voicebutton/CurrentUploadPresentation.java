package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;

import java.util.List;
import java.util.Set;

/** One truthful, file-scoped transfer state for the recording overview. */
final class CurrentUploadPresentation {
    private CurrentUploadPresentation() {}

    static final class State {
        final String fileName;
        final String phase;
        final int progressPermille;
        final int otherFilesWaiting;
        final boolean retrying;

        State(String fileName, String phase, int progressPermille,
              int otherFilesWaiting, boolean retrying) {
            this.fileName = fileName;
            this.phase = phase;
            this.progressPermille = progressPermille;
            this.otherFilesWaiting = otherFilesWaiting;
            this.retrying = retrying;
        }
    }

    // During fast starts the live uploader may report a new recording before
    // the session-list refresh supplies a complete MP3 manifest. Show a real
    // phase, never misleading per-chunk progress as a whole-file percentage.
    static State activeWithoutManifest(String liveSessionId, String liveOperation) {
        if (liveSessionId == null || liveSessionId.isEmpty()) return null;
        String phase;
        boolean retrying = "retry_backoff".equals(liveOperation);
        if ("upload_chunk".equals(liveOperation)) phase = "Uploading now · measuring full recording";
        else if (retrying) phase = "Connection interrupted · retrying";
        else if (liveOperation != null && liveOperation.contains("commit")) {
            phase = "Audio sent · awaiting server confirmation";
        } else if (liveOperation != null && liveOperation.contains("reconcil")) {
            phase = "Verifying the saved Jetson copy";
        } else return null;
        return new State("current recording", phase, -1, 0, retrying);
    }

    static State from(List<ReliableSessionManifest> sessions,
                      Set<String> serverCommittedIds, String liveSessionId,
                      String liveOperation, int liveSequence, long liveDurableBytes) {
        List<ReliableSessionManifest> pending =
                OverviewProgress.pendingUploads(sessions, serverCommittedIds);
        if (pending.isEmpty()) return null;
        ReliableSessionManifest session = OverviewProgress.findSession(pending, liveSessionId);
        // When nothing is actively transmitting, show the newest pending
        // recording rather than an aggregate across the entire archive.
        if (session == null) session = pending.get(pending.size() - 1);
        boolean active = session.sessionId != null
                && session.sessionId.equals(liveSessionId);
        String operation = active && liveOperation != null ? liveOperation : "";
        boolean verifying = OverviewProgress.allSegmentsAccepted(session);
        boolean retrying = "retry_backoff".equals(operation);
        int progress = session.conversionFinished
                ? OverviewProgress.fileProgressPermille(session,
                        active ? liveSequence : -1,
                        active ? liveDurableBytes : 0L) : -1;
        String phase;
        if (!session.conversionFinished) phase = "Preparing audio for upload";
        else if (verifying) {
            phase = "Audio sent · waiting for server confirmation";
            progress = 1000;
        } else if (retrying) phase = "Connection interrupted · retrying";
        else if ("upload_chunk".equals(operation)) phase = "Uploading now";
        else if (progress > 0) phase = "Waiting to continue upload";
        else phase = "Waiting to upload";
        return new State(OverviewProgress.fileName(session), phase, progress,
                pending.size() - 1, retrying);
    }
}
