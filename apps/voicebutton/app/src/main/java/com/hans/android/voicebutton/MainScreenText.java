package com.hans.android.voicebutton;

final class MainScreenText {
    private MainScreenText() {}

    static String transfer(boolean recording, long totalBytes, long pendingBytes,
                           int progressPermille) {
        if (totalBytes <= 0L) {
            return recording
                    ? "Server: waiting for the first protected audio"
                    : "Server: nothing waiting";
        }
        if (pendingBytes <= 0L) return "Server: complete";
        return String.format(java.util.Locale.US, "Server: %.1f%% · %s remaining",
                progressPermille / 10.0f, RecordingUi.formatBytes(pendingBytes));
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
}
