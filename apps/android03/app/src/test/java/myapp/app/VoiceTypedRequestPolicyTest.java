package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceTypedRequestPolicyTest {
  private final String id = "1234567890abcdef1234567890abcdef";

  @Test public void sameRequestIsSafeToRetryAfterDisconnectedAck() {
    assertTrue(VoiceTypedRequestPolicy.isValidId(id));
    assertTrue(VoiceTypedRequestPolicy.matchesAck(id, "Who are you?",
        "turn-client-" + id, "Who are you?"));
  }

  @Test public void changedUnacknowledgedDraftCannotAutomaticallyCreateAnotherOperation() {
    assertTrue(VoiceTypedRequestPolicy.mayRetry(id, "Use folder A.", "Use folder A."));
    assertTrue(VoiceTypedRequestPolicy.mayRetry(id, "Use folder A.", "  Use folder A. "));
    assertFalse(VoiceTypedRequestPolicy.mayRetry(id, "Use folder A.", "Use folder B."));
    assertFalse(VoiceTypedRequestPolicy.mayRetry(id, "Use folder A.", ""));
    assertFalse(VoiceTypedRequestPolicy.mayRetry("", "Use folder A.", "Use folder A."));
  }

  @Test public void lostReceiptRetryKeepsExactlyOneLogicalOperation() {
    assertTrue(VoiceTypedRequestPolicy.mayRetry(id,"Open the blue folder.",
        "  Open the blue folder.  "));
    assertTrue(VoiceTypedRequestPolicy.matchesAck(id,"Open the blue folder.",
        "turn-client-" + id,"Open the blue folder."));
    assertFalse(VoiceTypedRequestPolicy.mayRetry(id,"Open the blue folder.",
        "Open the red folder."));
    assertFalse(VoiceTypedRequestPolicy.matchesAck(id,"Open the blue folder.",
        "turn-client-" + id,"Open the red folder."));
  }

  @Test public void requestIdMustMatchTheServerHexEncoding() {
    assertFalse(VoiceTypedRequestPolicy.isValidId(null));
    assertFalse(VoiceTypedRequestPolicy.isValidId(""));
    assertFalse(VoiceTypedRequestPolicy.isValidId("1234567890ABCDEF1234567890ABCDEF"));
    assertFalse(VoiceTypedRequestPolicy.isValidId("abcdeffedcba"));
    assertFalse(VoiceTypedRequestPolicy.isValidId(id + "_other"));
    assertTrue(VoiceTypedRequestPolicy.isValidId(id));
  }

  @Test public void staleOrChangedDraftAckMustNotClearNewUserInput() {
    assertFalse(VoiceTypedRequestPolicy.matchesAck(id, "Who are you?",
        "turn-client-other", "Who are you?"));
    assertFalse(VoiceTypedRequestPolicy.matchesAck(id, "Who are you?",
        "turn-client-" + id, "What is your model?"));
    assertFalse(VoiceTypedRequestPolicy.matchesAck("", "Who are you?",
        "turn-client-" + id, "Who are you?"));
    assertFalse(VoiceTypedRequestPolicy.matchesAck(id, "Who are you?",
        "turn-client-" + id, ""));
    assertFalse(VoiceTypedRequestPolicy.isValidId("not-uuid"));
  }
}
