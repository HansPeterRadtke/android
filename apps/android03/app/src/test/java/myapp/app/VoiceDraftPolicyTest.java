package myapp.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceDraftPolicyTest {
  @Test public void unstablePartialCannotBeSubmitted() {
    assertFalse(VoiceDraftPolicy.canSubmit("unfinished speech", false, true));
  }

  @Test public void stableOrUserOwnedTextCanBeSubmitted() {
    assertTrue(VoiceDraftPolicy.canSubmit("finished speech", false, false));
  }

  @Test public void emptyOrAlreadyPendingTextCannotBeSubmitted() {
    assertFalse(VoiceDraftPolicy.canSubmit("", false, false));
    assertFalse(VoiceDraftPolicy.canSubmit("   \n  ", false, false));
    assertFalse(VoiceDraftPolicy.canSubmit("ready", true, false));
  }
}
