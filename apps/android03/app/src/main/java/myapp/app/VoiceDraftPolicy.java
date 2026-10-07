package myapp.app;

/** Submission gate for the editable current-message field. */
final class VoiceDraftPolicy {
  private VoiceDraftPolicy() {}

  static boolean canSubmit(String text, boolean sendPending, boolean unstableAsrPartial) {
    return !sendPending
        && !unstableAsrPartial
        && text != null
        && !text.trim().isEmpty();
  }
}
