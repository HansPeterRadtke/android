package com.hans.android.voicebutton;

final class RecordingContinuityPolicy {
    private RecordingContinuityPolicy() {}

    static long recoveryDelayMs(int attempt) {
        if (attempt <= 0) return 1000L;
        long delay = 1000L << Math.min(5, attempt);
        return Math.min(30000L, delay);
    }

    static boolean keepServiceAlive(boolean recording, boolean backgroundWork,
                                    boolean recoveryPending, boolean alarmActive) {
        return recording || backgroundWork || recoveryPending || alarmActive;
    }
    static boolean sessionNeedsSynchronization(boolean recordingFinished,
                                               boolean conversionFinished,
                                               boolean remoteCommitted,
                                               boolean pendingAudio,
                                               boolean pendingTranscript) {
        if (pendingAudio || pendingTranscript) return true;
        return recordingFinished && (!conversionFinished || !remoteCommitted);
    }

}
