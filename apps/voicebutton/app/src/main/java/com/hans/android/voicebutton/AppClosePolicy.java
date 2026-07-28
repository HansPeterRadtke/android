package com.hans.android.voicebutton;

final class AppClosePolicy {
    private AppClosePolicy() {}

    static boolean requiresWarning(boolean recording, String state) {
        return recording
                || "PREPARING".equals(state)
                || "PAUSING".equals(state)
                || "FINISHING".equals(state)
                || "COMPRESSING".equals(state)
                || "CLEANING".equals(state);
    }
}
