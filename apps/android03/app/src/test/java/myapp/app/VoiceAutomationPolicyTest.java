package myapp.app;
import static org.junit.Assert.*;
import org.junit.Test;
public class VoiceAutomationPolicyTest {
  @Test public void unknownSpeakerForcesManualReviewEvenWhenStoredAutoSendPreferenceIsTrue() {
    // The current client restores auto-send=true from preferences on first
    // install or a previous session. Neither is evidence of owner identity.
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,true));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(false,true));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,false));
  }
  @Test public void verifiedOwnerSpeakerMustComeFromTrustedServerPerCurrentTurn() {
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,true,false));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(false,true,true));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,false,true));
    // This is a future-only admission input. The current Android03 caller
    // has no server owner-speaker identity proof and uses the safe overload.
    assertTrue(VoiceAutomationPolicy.effectiveAutoSend(true,true,true));
  }
  @Test public void currentAndroid03TwoParameterCallCannotPromoteClientSpeakerClaim() {
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,true));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,false));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(false,true));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(false,false));
  }
  @Test public void manualModeBuffersInsteadOfStreaming() {
    assertFalse(VoiceAutomationPolicy.shouldStreamMic(false));
    assertTrue(VoiceAutomationPolicy.shouldKeepLocalRecording(false));
  }
}
