package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionManifest;

final class RecordingStateResolver {
    private RecordingStateResolver() {}

    static ReliableSessionManifest authoritativeOpenSession(
            ReliableSessionManifest cached,
            ReliableSessionManifest live) {
        if (cached == null) return null;
        if (live == null) return cached;
        if (cached.sessionId == null || live.sessionId == null
                || !cached.sessionId.equals(live.sessionId)) return cached;
        return live.recordingFinished ? null : live;
    }

    static String normalize(String requestedState, boolean actualRecording,
                            boolean pausedOpenRecording, boolean interruptedOpenRecording) {
        String requested = requestedState == null || requestedState.isEmpty()
                ? "READY" : requestedState;
        if (isOperationState(requested) || "FAILED".equals(requested)) return requested;
        if (actualRecording) return "RECORDING";
        if (pausedOpenRecording) return "PAUSED";
        if (interruptedOpenRecording) return "RECOVERY REQUIRED";
        if ("RECORDING".equals(requested) || "PAUSED".equals(requested)
                || "RECOVERY REQUIRED".equals(requested)
                || "INTERRUPTED".equals(requested)) return "READY";
        return requested;
    }

    static String explanation(String normalizedState, String requestedExplanation) {
        if ("RECORDING".equals(normalizedState)) {
            return "Audio is journaled locally and compressed in the background";
        }
        if ("PAUSED".equals(normalizedState)) {
            return "Recording is paused and ready to play or resume";
        }
        if ("RECOVERY REQUIRED".equals(normalizedState)) {
            return "An interrupted recording needs your decision";
        }
        return requestedExplanation == null || requestedExplanation.isEmpty()
                ? normalizedState : requestedExplanation;
    }

    static String primaryAction(boolean actualRecording, boolean pausedOpenRecording,
                                boolean interruptedOpenRecording) {
        if (actualRecording) return RecordingService.ACTION_PAUSE;
        if (pausedOpenRecording) return RecordingService.ACTION_RESUME;
        if (interruptedOpenRecording) return "RECOVERY";
        return RecordingService.ACTION_START;
    }

    private static boolean isOperationState(String state) {
        return "STARTING".equals(state)
                || "PREPARING".equals(state)
                || "PAUSING".equals(state)
                || "FINISHING".equals(state)
                || "COMPRESSING".equals(state)
                || "CLEANING".equals(state);
    }
}
