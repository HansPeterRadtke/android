package com.hans.android.voicebutton;

final class PrimaryActionPolicy {
    private PrimaryActionPolicy() {}

    static boolean isEnabled(boolean recording, String state, boolean hasOpenSession,
                             boolean paused, boolean interrupted, boolean hasMicrophone) {
        if (recording) return true;
        if ("CLEANING".equals(state) || "PREPARING".equals(state)
                || "PAUSING".equals(state)) return false;
        if (paused || interrupted) return true;
        if (hasOpenSession) return false;
        return hasMicrophone;
    }
}
