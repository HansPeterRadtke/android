package myapp.app;

/** Only explicit, server-supported, still-unrecognized saved audio may retry. */
final class VoiceSavedAudioRetryPolicy {
  private VoiceSavedAudioRetryPolicy() {}

  static boolean canRequest(String turnId, boolean serverSupports,
                            boolean connected, boolean capturing) {
    return serverSupports && connected && !capturing
        && turnId != null
        && turnId.matches("untranscribed-[0-9a-f]{16}");
  }

  static boolean isInProgress(String reason) {
    return "saved_recording_retry".equals(reason)
        || "saved_recording_retry_pending".equals(reason);
  }

  static boolean isReview(String reason) {
    return "saved_recording_retry_review".equals(reason);
  }

  static boolean isRetryError(String stage, String message) {
    return "stt".equals(stage) && (
        "retry_high_quality_recognizer_disabled".equals(message)
        || "retry_recognizer_unavailable".equals(message)
        || "retry_no_trustworthy_speech".equals(message)
        || "recording_not_retriable".equals(message)
        || "retry_capacity_exhausted".equals(message));
  }
}
