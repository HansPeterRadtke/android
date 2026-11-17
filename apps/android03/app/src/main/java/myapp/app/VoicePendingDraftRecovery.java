package myapp.app;

/** Pure policy for preserving the server's canonical, unsubmitted voice draft. */
final class VoicePendingDraftRecovery {
  private VoicePendingDraftRecovery() {}

  static boolean isRestorableLatestDraft(int rowIndex, int totalRows,
                                         String status, String transcript) {
    // Only the canonical LAST row is eligible: an earlier interrupted
    // request must never displace subsequent user speech or a completed turn.
    return rowIndex >= 0 && totalRows > 0 && rowIndex == totalRows - 1
        && "draft".equals(status)
        && transcript != null && !transcript.trim().isEmpty();
  }

  static String recoveredTurnId(String serverDraftId, boolean automaticMode) {
    // Global automatic-send preference governs FUTURE completed speech, not
    // the server's persisted explicit-review draft. A classifier failure
    // cannot make an unfinished command disappear from the user's composer.
    return serverDraftId == null ? "" : serverDraftId.trim();
  }
}
