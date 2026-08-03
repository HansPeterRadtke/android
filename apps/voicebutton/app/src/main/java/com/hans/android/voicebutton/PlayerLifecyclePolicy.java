package com.hans.android.voicebutton;

final class PlayerLifecyclePolicy {
    private PlayerLifecyclePolicy() {}

    static boolean resumeAfterTaskRemoval(boolean wasPlaying) {
        return wasPlaying;
    }

    static boolean keepPlayingWhenViewStops(boolean hasSource) {
        return hasSource;
    }

    static boolean persistCheckpointOnClose(boolean hasSource) {
        return hasSource;
    }

    static long checkpointIntervalMs() { return 5000L; }
}
