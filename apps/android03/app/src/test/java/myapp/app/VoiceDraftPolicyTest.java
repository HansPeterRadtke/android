package myapp.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceDraftPolicyTest {
  @Test public void unfinishedSpeechCanAlwaysBeExplicitlySubmitted() {
    assertTrue(VoiceDraftPolicy.canSubmit("unfinished speech"));
  }

  @Test public void anyNonemptyTypedTextCanBeSubmitted() {
    assertTrue(VoiceDraftPolicy.canSubmit("finished speech"));
    assertTrue(VoiceDraftPolicy.canSubmit("user correction"));
  }

  @Test public void emptyTextCannotBeSubmitted() {
    assertFalse(VoiceDraftPolicy.canSubmit(null));
    assertFalse(VoiceDraftPolicy.canSubmit(""));
    assertFalse(VoiceDraftPolicy.canSubmit("   \n  "));
  }
}
