package myapp.app;

/** Strict UI recognition of a durable recording without invented ASR text. */
final class VoiceAudioRecoveryNotice {
  private VoiceAudioRecoveryNotice() {}

  static boolean inHistory(String status, boolean hasOriginalAudio) {
    return "asr_unavailable".equals(status) && hasOriginalAudio;
  }

  static boolean justSaved(String event, String reason, boolean hasOriginalAudio,
                           String artifactTurnId) {
    return "artifact".equals(event)
        && "recognition_unavailable".equals(reason)
        && hasOriginalAudio
        && artifactTurnId != null
        && !artifactTurnId.isEmpty();
  }

  static boolean isSavedRecordingState(String state, String reason) {
    return "listening".equals(state)
        && "recognition_unavailable_recording_saved".equals(reason);
  }

  static boolean isSavedRecordingError(String stage, String code) {
    return "stt".equals(stage)
        && "speech_recognition_unavailable_recording_saved".equals(code);
  }
}
