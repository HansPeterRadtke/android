package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;

final class RecordingStartupPolicy {
    private RecordingStartupPolicy() {}

    static String requestedState(ReliableSessionManifest open) {
        if (open == null || open.recordingFinished) return "READY";
        if (open.paused) return "PAUSED";
        return "RECOVERY REQUIRED";
    }

    static String explanation(ReliableSessionManifest open) {
        if (open == null || open.recordingFinished) {
            return "Ready to create a loss-protected recording";
        }
        if (open.paused) {
            return "Recording is paused and ready to play or resume";
        }
        return "An unfinished recording is preserved and needs recovery before a new recording can start";
    }
}
