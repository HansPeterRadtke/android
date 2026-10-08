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
