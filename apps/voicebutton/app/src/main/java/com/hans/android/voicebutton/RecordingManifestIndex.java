package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import java.util.List;

/** Extract current capture/recovery state from a single durable manifest pass. */
final class RecordingManifestIndex {
    final ReliableSessionManifest unfinished;
    final ReliableSessionManifest interrupted;

    private RecordingManifestIndex(ReliableSessionManifest unfinished,
                                   ReliableSessionManifest interrupted) {
        this.unfinished = unfinished;
        this.interrupted = interrupted;
    }

    static RecordingManifestIndex from(List<ReliableSessionManifest> sessions) {
        ReliableSessionManifest open = null;
        ReliableSessionManifest interrupted = null;
        if (sessions != null) for (ReliableSessionManifest candidate : sessions) {
            if (candidate == null || candidate.sessionId == null
                    || "UNREADABLE_METADATA".equals(candidate.state)) continue;
            if (!candidate.recordingFinished
                    && (open == null || candidate.createdAt > open.createdAt)) {
                open = candidate;
            }
            if (candidate.isInterrupted()
                    && (interrupted == null
                        || candidate.createdAt > interrupted.createdAt)) {
                interrupted = candidate;
            }
        }
        return new RecordingManifestIndex(open == null ? null : open.copy(),
                interrupted == null ? null : interrupted.copy());
    }
}
