package com.hans.android.voicebutton;

final class PrimaryActionPolicy {
    private PrimaryActionPolicy() {}

    static boolean isEnabled(boolean recording, String state, boolean hasOpenSession,
                             boolean paused, boolean interrupted, boolean hasMicrophone) {
        if (recording) return true;
        if ("STARTING".equals(state) || "CLEANING".equals(state) || "PREPARING".equals(state)
                || "PAUSING".equals(state)) return false;
        if (paused || interrupted) return true;
        if (hasOpenSession) return false;
        return hasMicrophone;
    }

    static boolean canAttemptMicrophone(boolean hasMicrophone,
                                        boolean refreshRunning,
                                        boolean hasRecordPermission) {
        return hasMicrophone || refreshRunning || !hasRecordPermission;
    }

    static String disabledReason(boolean recording, String state,
                                 boolean hasOpenSession, boolean paused,
                                 boolean interrupted, boolean hasMicrophone,
                                 boolean hasRecordPermission) {
        if (recording || paused || interrupted) return "";
        if ("STARTING".equals(state)) return "Recording is already starting.";
        if ("PREPARING".equals(state)) return "The microphone is opening.";
        if ("PAUSING".equals(state)) return "Wait for the recording to finish pausing.";
        if ("CLEANING".equals(state)) return "Protected storage cleanup is finishing.";
        if (hasOpenSession) return "Finish or recover the open recording first.";
        if (!hasRecordPermission) return "Microphone permission is required to record.";
        if (!hasMicrophone) return "No physical microphone is currently available.";
        return "Recording is temporarily unavailable.";
    }
}
