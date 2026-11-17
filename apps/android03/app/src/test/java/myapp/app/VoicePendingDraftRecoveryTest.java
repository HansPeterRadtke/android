package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VoicePendingDraftRecoveryTest {
  @Test public void incompleteTurnMustStayReviewableEvenInAutomaticMode() {
    assertEquals("draft-safe-123",
        VoicePendingDraftRecovery.recoveredTurnId("draft-safe-123", true));
    assertEquals("draft-safe-123",
        VoicePendingDraftRecovery.recoveredTurnId("draft-safe-123", false));
  }

  @Test public void olderDraftCannotSupersedeLaterCompletedTurn() {
    assertFalse(VoicePendingDraftRecovery.isRestorableLatestDraft(
        0, 2, "draft", "Do not use folder A"));
    assertTrue(VoicePendingDraftRecovery.isRestorableLatestDraft(
        1, 2, "draft", "Use folder B"));
  }

  @Test public void emptyOrSubmittedRowsDoNotEnterManualReview() {
    assertFalse(VoicePendingDraftRecovery.isRestorableLatestDraft(
        0, 1, "draft", ""));
    assertFalse(VoicePendingDraftRecovery.isRestorableLatestDraft(
        0, 1, "completed", "Use folder A"));
    assertFalse(VoicePendingDraftRecovery.isRestorableLatestDraft(
        0, 1, "asr_unavailable", "Do not invent speech"));
    assertEquals("", VoicePendingDraftRecovery.recoveredTurnId(null,true));
  }
}
