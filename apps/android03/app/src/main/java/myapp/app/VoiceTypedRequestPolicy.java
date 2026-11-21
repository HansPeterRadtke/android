package myapp.app;

/** Identity matching for idempotent typed-turn retry and delayed server ACKs. */
final class VoiceTypedRequestPolicy {
  private VoiceTypedRequestPolicy() {}

  static boolean isValidId(String requestId) {
    return requestId != null && requestId.matches("[0-9a-f]{32}");
  }

  static boolean mayRetry(String pendingId, String priorText, String currentDraft) {
    return isValidId(pendingId) && priorText != null && currentDraft != null
        && priorText.equals(currentDraft.trim());
  }

  static boolean matchesAck(String pendingId, String pendingText, String serverTurn,
      String serverText) {
    return isValidId(pendingId) && serverTurn != null
        && serverTurn.equals("turn-client-" + pendingId)
        && pendingText != null && pendingText.equals(serverText);
  }
}
