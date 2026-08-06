package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;

final class RecordingPlaybackPolicy {
    enum Action { READY, FINALIZE, PREVIEW, BLOCK_CAPTURE, NO_AUDIO }

    private RecordingPlaybackPolicy() {}

    static Action decide(ReliableSessionManifest manifest,
                         boolean finalFileExists,
                         boolean captureActive) {
        if (finalFileExists) return Action.READY;
        if (captureActive) return Action.BLOCK_CAPTURE;
        if (manifest == null || manifest.isDiscardableEmptySession()) {
            return Action.NO_AUDIO;
        }
        if (manifest.recordingFinished) return Action.FINALIZE;
        if (!manifest.segments.isEmpty()
                || manifest.totalPcmBytes > 0L
                || manifest.totalSegmentBytes > 0L) {
            return Action.PREVIEW;
        }
        return Action.NO_AUDIO;
    }
}
