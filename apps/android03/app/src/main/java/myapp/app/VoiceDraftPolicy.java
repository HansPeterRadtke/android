package myapp.app;

/** Manual Send is never gated by ASR completion, model state or queued transport. */
final class VoiceDraftPolicy {
  private VoiceDraftPolicy() {}

  static boolean canSubmit(String text) {
    return text != null && !text.trim().isEmpty();
  }
}
