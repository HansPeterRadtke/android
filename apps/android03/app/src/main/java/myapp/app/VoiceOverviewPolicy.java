package myapp.app;

final class VoiceOverviewPolicy {
  private VoiceOverviewPolicy() {}

  static boolean showWorker(String status) {
    if (status == null) return false;
    String value = status.trim();
    return !value.isEmpty() && !"none".equals(value) && !"idle".equals(value);
  }

  static boolean showReplay(boolean userAudio, boolean assistantAudio) {
    return userAudio || assistantAudio;
  }

  static boolean showTranscript(String text, boolean waitingForManualSend) {
    return waitingForManualSend || (text != null && !text.trim().isEmpty());
  }
}
