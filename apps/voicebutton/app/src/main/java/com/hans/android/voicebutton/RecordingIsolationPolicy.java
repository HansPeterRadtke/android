package com.hans.android.voicebutton;

final class RecordingIsolationPolicy {
    private RecordingIsolationPolicy() {}

    static boolean mayRunDeferredWork(boolean recording,
                                      boolean exitRequested) {
        return !recording && !exitRequested;
    }

    static boolean resumeCaptureBeforeDeferredWork(
            boolean interrupted, boolean autoResumeRequested) {
        return interrupted && autoResumeRequested;
    }
}
