package com.hans.android.voicebutton;

final class MainScreenText {
    private MainScreenText() {}

    static String transfer(boolean recording, long totalBytes, long pendingBytes,
                           int progressPermille) {
        return transfer("READY", recording, totalBytes, pendingBytes,
                progressPermille, "idle", -1, 0L, 0L);
    }

    static String transfer(String state, boolean recording, long totalBytes,
                           long pendingBytes, int progressPermille,
                           String liveOperation, int liveSequence,
                           long liveDurableBytes, long liveTotalBytes) {
        if ("STARTING".equals(state)) {
            return "Server: checking protected storage";
        }
        String live = "";
        if ("upload_chunk".equals(liveOperation) && liveTotalBytes > 0L) {
            live = String.format(java.util.Locale.US,
                    " · chunk %d: %s/%s",
                    liveSequence + 1,
                    RecordingUi.formatBytes(liveDurableBytes),
                    RecordingUi.formatBytes(liveTotalBytes));
        }
        if (totalBytes <= 0L) {
            return recording
                    ? "Server: waiting for the first protected audio" + live
                    : "Server: nothing waiting" + live;
        }
        if (pendingBytes <= 0L) return "Server: complete" + live;
        return String.format(java.util.Locale.US, "Server: %.1f%% · %s remaining%s",
                progressPermille / 10.0f, RecordingUi.formatBytes(pendingBytes), live);
    }

    static String microphone(boolean recording, boolean signalDetected) {
        if (!recording) return "Microphone signal: not recording";
        return signalDetected
                ? "Microphone signal: detected"
                : "Microphone signal: quiet — recording continues";
    }

    static String localProtection(String folderName, boolean open) {
        return open
                ? "Local protection: active · " + folderName
                : "Local protection: ready";
    }
    static String stateTitle(String state, boolean recording, boolean paused,
                             boolean alarmActive) {
        if (alarmActive) return "NEEDS ATTENTION";
        if (recording) return "RECORDING";
        if (paused) return "PAUSED";
        if ("STARTING".equals(state)) return "STARTING";
        if ("PREPARING".equals(state)) return "STARTING";
        if ("FINISHING".equals(state) || "PAUSING".equals(state)) return "SAVING";
        if ("FAILED".equals(state)) return "FAILED";
        return "READY";
    }

    static String stateSummary(String state, boolean recording, boolean paused,
                               boolean alarmActive, boolean openRecording) {
        if (alarmActive) return "Recording stopped unexpectedly. Recovery is active.";
        if (recording) return "Audio is being protected locally while server sync continues.";
        if (paused) return "Recording is paused and safe to resume.";
        if ("STARTING".equals(state)) return "Opening protected recording storage and checking local sync state.";
        if ("PREPARING".equals(state)) return "Opening the selected microphone.";
        if ("FINISHING".equals(state) || "PAUSING".equals(state)) return "Saving the current audio safely.";
        if ("FAILED".equals(state)) return "An action failed. Open More for details and recovery.";
        if (openRecording) return "An unfinished recording is safe on this phone.";
        return "Ready to start a protected recording.";
    }

    static String structureKey(String state, boolean recording, boolean paused,
                               boolean alarmActive, boolean alarmAudible,
                               boolean openRecording, String folderId,
                               int deviceId, int sessionCount) {
        return stateTitle(state, recording, paused, alarmActive) + '|'
                + recording + '|' + paused + '|' + alarmActive + '|'
                + alarmAudible + '|' + openRecording + '|'
                + String.valueOf(folderId) + '|' + deviceId + '|' + sessionCount;
    }

}
