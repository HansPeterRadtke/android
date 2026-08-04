package com.hans.android.voicebutton;

final class PlayerTerminalPolicy {
    private PlayerTerminalPolicy() {}

    static boolean ignoreStateAfterError(String error, String state) {
        return error != null && !error.isEmpty()
                && ("stopped".equals(state) || "ended".equals(state));
    }

    static boolean restartFromBeginning(String state, long timeMs,
                                        long lengthMs) {
        if ("ended".equals(state) || "stopped".equals(state)) return true;
        return lengthMs > 0L && timeMs >= Math.max(0L, lengthMs - 1000L);
    }

    static boolean startIsPending(String state) {
        if (state == null) return false;
        return "opening".equals(state) || "starting playback".equals(state)
                || state.startsWith("buffering");
    }
}
