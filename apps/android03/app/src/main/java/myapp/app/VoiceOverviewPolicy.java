package myapp.app;

final class VoiceOverviewPolicy {
  private VoiceOverviewPolicy() {}

  static boolean showWorker(String status) {
    if (status == null) return false;
    String value = status.trim();
    return !value.isEmpty() && !"none".equals(value) && !"idle".equals(value);
  }

  static boolean showQuestions(int openQuestions) {
    return openQuestions > 0;
  }

  static boolean showForegroundProgress(String mode) {
    if (mode == null) return false;
    switch (mode.trim()) {
      case "CONNECTING":
      case "VERIFYING":
      case "TRANSCRIBING":
      case "THINKING":
      case "BUFFERING":
      case "RECONNECTING":
      case "FINISHING":
        return true;
      default:
        return false;
    }
  }

  static boolean isIntentionallyEndless(String progressKind) {
    if (progressKind == null) return false;
    String value = progressKind.trim();
    return "intentionally_endless".equals(value)
        || "contains_intentionally_endless_work".equals(value)
        || "continuous".equals(value);
  }

  static boolean showWorkerProgress(String status) {
    if (status == null) return false;
    switch (status.trim()) {
      case "queued":
      case "working":
      case "cancellation_requested":
        return true;
      default:
        return false;
    }
  }

  static boolean showTtsFallback(boolean ttsReady, boolean primaryReady, boolean fallbackReady) {
    return ttsReady && !primaryReady && fallbackReady;
  }

  static boolean showReplay(boolean userAudio, boolean assistantAudio) {
    return userAudio || assistantAudio;
  }

  static boolean showTranscript(String text, boolean waitingForManualSend) {
    return waitingForManualSend || (text != null && !text.trim().isEmpty());
  }
}
