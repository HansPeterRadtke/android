package myapp.app;

final class VoiceAutomationPolicy {
  private VoiceAutomationPolicy() {}
  static boolean effectiveAutoSend(boolean autoTranscribe, boolean requestedAutoSend) {
    return autoTranscribe && requestedAutoSend;
  }
  static boolean shouldStreamMic(boolean autoTranscribe) { return autoTranscribe; }
  static boolean shouldKeepLocalRecording(boolean autoTranscribe) { return !autoTranscribe; }
}
