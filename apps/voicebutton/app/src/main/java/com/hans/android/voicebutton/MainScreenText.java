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
            return "Upload: continuing in the background while recording opens";
        }
        if ("retry_backoff".equals(liveOperation) && pendingBytes > 0L) {
            return "Upload: Jetson unavailable · retrying · "
                    + RecordingUi.formatBytes(pendingBytes) + " waiting";
        }
        String live = "";
        if ("upload_chunk".equals(liveOperation) && liveTotalBytes > 0L) {
            live = String.format(java.util.Locale.US,
                    " · chunk %d: %s/%s",
                    liveSequence + 1,
                    RecordingUi.formatBytes(liveDurableBytes),
                    RecordingUi.formatBytes(liveTotalBytes));
        }
        if (recording) {
            if ("upload_chunk".equals(liveOperation) && liveTotalBytes > 0L) {
                return "Upload: closed audio syncing · current recording protected locally" + live;
            }
            return pendingBytes > 0L
                    ? "Upload: earlier audio syncing · current recording protected locally"
                    : "Upload: caught up · current recording protected locally";
        }
        if (isCurrentRecordingUnmeasured(state, liveOperation)) {
            return pendingBytes > 0L
                    ? "Upload: earlier audio syncing · current recording still finalizing locally"
                    : "Upload: current recording still finalizing locally";
        }
        if (totalBytes <= 0L) {
            return "Upload: nothing waiting" + live;
        }
        if (pendingBytes <= 0L) return "Upload: complete · acknowledged by Jetson" + live;
        return String.format(java.util.Locale.US, "Upload: %.1f%% · %s remaining%s",
                progressPermille / 10.0f, RecordingUi.formatBytes(pendingBytes), live);
    }



    static String uploadOverall(int progressPermille, int filesLeft, boolean unmeasured) {
        String scope = unmeasured ? " known bytes" : "";
        return String.format(java.util.Locale.US,
                "Upload overall: %.1f%%%s · %s",
                Math.max(0, Math.min(1000, progressPermille)) / 10.0f,
                scope, filesLeftLabel(filesLeft));
    }

    static String uploadCurrent(String fileName, int progressPermille,
                                int filesLeft, String operation) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            return filesLeft <= 0 ? "Upload current file: none"
                    : "Upload current file: waiting";
        }
        if (progressPermille < 0 || "waiting_local_encoding".equals(operation)) {
            return "Upload current file: " + name + " · preparing";
        }
        return String.format(java.util.Locale.US,
                "Upload current file: %s · %.1f%%",
                name, Math.max(0, Math.min(1000, progressPermille)) / 10.0f);
    }

    static String transcriptionOverall(int overallPercent, int filesLeft,
                                       String staleSuffix) {
        String suffix = staleSuffix == null ? "" : staleSuffix;
        return "Transcription overall: " + Math.max(0, Math.min(100, overallPercent))
                + "% · " + filesLeftLabel(filesLeft) + suffix;
    }

    static String transcriptionCurrent(String fileName, int percent, int filesLeft,
                                       String phase) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            return filesLeft <= 0 ? "Transcription current file: none"
                    : "Transcription current file: waiting";
        }
        if ("loading".equals(phase)) {
            return "Transcription current file: " + name + " · loading";
        }
        return "Transcription current file: " + name + " · "
                + Math.max(0, Math.min(100, percent)) + "%";
    }

    static String filesLeftLabel(int filesLeft) {
        int count = Math.max(0, filesLeft);
        return count + (count == 1 ? " file left" : " files left");
    }

    static String backupNotice(boolean recording, long pendingBytes,
                               int progressPermille, String operation) {
        if ("retry_backoff".equals(operation) && pendingBytes > 0L) {
            return recording
                    ? "Backup delayed · " + RecordingUi.formatBytes(pendingBytes)
                            + " waiting · recording safe on this phone"
                    : "Backup delayed · " + RecordingUi.formatBytes(pendingBytes) + " waiting";
        }
        if ("waiting_local_encoding".equals(operation)) {
            return recording
                    ? "Preparing backup · local MP3 encoding · recording safe on this phone"
                    : "Preparing backup · local MP3 encoding";
        }
        if (recording && pendingBytes <= 0L) {
            return "Backup current recording · progress becomes measurable as protected audio closes";
        }
        if (pendingBytes <= 0L) return "Backup complete · 100%";
        return String.format(java.util.Locale.US,
                recording
                        ? "Backing up · %.1f%% · %s remaining · recording safe on this phone"
                        : "Backing up · %.1f%% · %s remaining",
                progressPermille / 10.0f, RecordingUi.formatBytes(pendingBytes));
    }

    static boolean shouldShowBackupNotice(boolean recording, long pendingBytes,
                                          String operation) {
        return true;
    }

    static boolean isCurrentRecordingUnmeasured(String state, String liveOperation) {
        return "FINISHING".equals(state)
                || "PAUSING".equals(state)
                || "COMPRESSING".equals(state)
                || "local_finalizing".equals(liveOperation);
    }

    static String jetsonHealth(boolean hasSuccessfulPoll, boolean available,
                                long ageMs) {
        if (available) return "Jetson: reachable · updated now";
        if (!hasSuccessfulPoll) {
            return "Jetson: status unavailable · local recording still works";
        }
        return "Jetson: status unavailable · last seen " + formatAge(ageMs)
                + " ago · local recording still works";
    }

    private static String formatAge(long ageMs) {
        long seconds = Math.max(0L, ageMs) / 1000L;
        if (seconds < 60L) return seconds + "s";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m";
        return (minutes / 60L) + "h";
    }

    static String microphone(boolean recording, boolean signalDetected) {
        if (!recording) return "";
        return signalDetected
                ? "Input detected"
                : "Input quiet · recording continues";
    }

    static String localProtection(String folderName, boolean open) {
        return open ? "Safe on this phone · " + folderName : "";
    }
    static String stateTitle(String state, boolean recording, boolean paused,
                             boolean alarmActive) {
        if (alarmActive) return "Recording needs attention";
        if (recording) return "Recording";
        if (paused) return "Recording paused";
        if ("STARTING".equals(state)) return "Starting recording";
        if ("PREPARING".equals(state)) return "Starting recording";
        if ("FINISHING".equals(state) || "PAUSING".equals(state)
                || "COMPRESSING".equals(state)) return "Saving recording";
        if ("FAILED".equals(state)) return "Recording unavailable";
        return "Ready to record";
    }

    static String stateSummary(String state, boolean recording, boolean paused,
                               boolean alarmActive, boolean openRecording) {
        if (alarmActive) return "Recording stopped unexpectedly. Recovery is active.";
        if (recording) return "";
        if (paused) return "";
        if ("STARTING".equals(state)) return "Opening protected recording storage and checking local sync state.";
        if ("PREPARING".equals(state)) return "Opening the selected microphone.";
        if ("FINISHING".equals(state) || "PAUSING".equals(state)) return "Saving the current audio safely.";
        if ("COMPRESSING".equals(state)) return "Finalizing the local MP3 before server upload.";
        if ("FAILED".equals(state)) return "An action failed. Open More for details and recovery.";
        if (openRecording) return "An unfinished recording is safe on this phone.";
        return "Ready to start a protected recording.";
    }

    static boolean shouldShowTimer(String state, boolean recording, boolean paused,
                                   boolean openRecording) {
        return recording || paused || openRecording
                || "FINISHING".equals(state) || "PAUSING".equals(state)
                || "COMPRESSING".equals(state);
    }

    static boolean shouldShowStateDetail(String state, boolean recording, boolean paused,
                                         boolean alarmActive, boolean openRecording) {
        if (alarmActive || "FAILED".equals(state)) return true;
        if ("STARTING".equals(state) || "PREPARING".equals(state)
                || "FINISHING".equals(state) || "PAUSING".equals(state)
                || "COMPRESSING".equals(state)) return true;
        return openRecording && !recording && !paused;
    }

    static boolean shouldShowSetup(String state, boolean recording,
                                   boolean openRecording, boolean interrupted,
                                   boolean alarmActive) {
        if (recording || openRecording || interrupted || alarmActive) return false;
        return !"STARTING".equals(state) && !"PREPARING".equals(state)
                && !"FINISHING".equals(state) && !"PAUSING".equals(state)
                && !"CLEANING".equals(state);
    }

    static boolean shouldShowUploadStatus(String state, boolean recording,
                                          long pendingBytes, String operation) {
        if ("retry_backoff".equals(operation)) return true;
        if (recording) return false;
        return pendingBytes > 0L || "upload_chunk".equals(operation)
                || isCurrentRecordingUnmeasured(state, operation);
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
