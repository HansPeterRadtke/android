package com.hans.android.voicebutton;

import com.hans.android.network.reliable.ReliableUploadClient;
import java.util.List;

/**
 * Recording-first, truthful transcription progress. Real frame counts are
 * the denominator for the current recording; committed-record counts and
 * the server's aggregate estimate apply only to the separately named backlog.
 */
final class TranscriptionProgressPresentation {
    private TranscriptionProgressPresentation() {}

    static final class State {
        final String currentLabel;
        final int currentPermille; // -1 means unknown / phase-based.
        final boolean currentError;
        final boolean currentProcessing;
        final String overallLabel;
        final int overallPermille; // -1 means unknown.
        final boolean stale;

        State(String currentLabel, int currentPermille, boolean currentError,
              boolean currentProcessing, String overallLabel,
              int overallPermille, boolean stale) {
            this.currentLabel = currentLabel;
            this.currentPermille = currentPermille;
            this.currentError = currentError;
            this.currentProcessing = currentProcessing;
            this.overallLabel = overallLabel;
            this.overallPermille = overallPermille;
            this.stale = stale;
        }
    }

    static State from(ReliableUploadClient.TranscriptionStatus value,
                      String lastUpdatedSuffix, boolean stale) {
        if (value == null) return new State(
                "Transcription status unavailable · retrying",
                -1, true, true, "", -1, true);
        List<ReliableUploadClient.CurrentTranscription> pending = value.pending;
        ReliableUploadClient.CurrentTranscription focus = value.current;
        if (focus == null && pending != null && !pending.isEmpty()) {
            focus = pending.get(0);
            for (ReliableUploadClient.CurrentTranscription item : pending) {
                if ("RUNNING".equals(item.state)) {
                    focus = item;
                    break;
                }
            }
        }
        String age = stale && lastUpdatedSuffix != null ? lastUpdatedSuffix : "";
        String currentLabel = "";
        int currentPermille = -1;
        boolean currentError = false;
        boolean currentProcessing = true;
        if (focus != null) {
            String name = focus.displayName == null || focus.displayName.trim().isEmpty()
                    ? "Recording" : focus.displayName.trim();
            String phase = focus.phase == null ? ""
                    : focus.phase.toLowerCase(java.util.Locale.ROOT);
            if ("RUNNING".equals(focus.state)
                    && focus.framesTotal > 0L
                    && !"loading".equals(phase)
                    && !"starting".equals(phase)) {
                // Only processed/expected frames yield a per-file fraction.
                // The advertised percent alone is not a valid denominator.
                currentPermille = measuredFramePermille(focus);
                currentLabel = "Transcribing " + name + " · "
                        + ((currentPermille + 5) / 10) + "%";
            } else if ("FAILED".equals(focus.state)) {
                currentError = true;
                currentProcessing = false;
                // Keep genuinely processed frames visible even on failure,
                // but never animate a failed operation as if it were running.
                if (focus.framesTotal > 0L && focus.framesDone > 0L) {
                    currentPermille = measuredFramePermille(focus);
                }
                currentLabel = "Transcription failed: " + name
                        + (currentPermille < 0 ? ""
                        : " · " + ((currentPermille + 5) / 10) + "% processed")
                        + " · More → Transcribe recordings";
            } else if ("MANUAL".equals(focus.state)) {
                currentProcessing = false;
                currentLabel = "Manual transcription waiting: " + name
                        + " · More → Transcribe recordings";
            } else if ("loading".equals(phase)) {
                currentLabel = "Loading transcription model for " + name;
            } else if ("starting".equals(phase) || "RUNNING".equals(focus.state)) {
                currentLabel = "Starting transcription for " + name;
            } else {
                currentLabel = "Waiting to transcribe " + name;
            }
            if (pending != null && pending.size() > 1) {
                int others = pending.size() - 1;
                currentLabel += " · " + others + (others == 1
                        ? " other waiting" : " others waiting");
            }
            currentLabel += age;
        } else if (value.notTranscribedCount > 0) {
            currentLabel = "Waiting for transcription · "
                    + value.notTranscribedCount + " recordings" + age;
        }

        String overallLabel = "";
        int overallPermille = -1;
        if (value.totalCommittedCount > 0) {
            // Overall is an independent server-derived estimate. It must
            // never be used for the active file's progress or masquerade as it.
            overallPermille = Math.max(0, Math.min(1000, value.overallPercent * 10));
            if (value.notTranscribedCount == 0
                    && value.completeCount == value.totalCommittedCount) {
                overallPermille = 1000;
            }
            overallLabel = "All recordings · "
                    + Math.min(value.totalCommittedCount, value.completeCount)
                    + " of " + value.totalCommittedCount + " transcribed · "
                    + ((overallPermille + 5) / 10) + "%" + age;
        }
        return new State(currentLabel, currentPermille,
                currentError, currentProcessing, overallLabel, overallPermille, stale);
    }

    private static int measuredFramePermille(
            ReliableUploadClient.CurrentTranscription item) {
        if (item == null || item.framesTotal <= 0L) return -1;
        double fraction = (double)Math.max(0L,
                Math.min(item.framesTotal, item.framesDone))
                / (double)item.framesTotal;
        return Math.max(0, Math.min(1000,
                (int)Math.round(fraction * 1000.0)));
    }
}
