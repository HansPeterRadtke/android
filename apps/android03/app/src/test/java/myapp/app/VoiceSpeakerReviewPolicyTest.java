package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VoiceSpeakerReviewPolicyTest {
  @Test public void missingTrustedSpeakerMustCauseManualReview() {
    assertTrue(VoiceSpeakerReviewPolicy.needsManualSpeakerReview(
        "speaker_identity_unverified"));
    assertFalse(VoiceSpeakerReviewPolicy.needsManualSpeakerReview(
        "turn_classifier_unavailable"));
    assertFalse(VoiceSpeakerReviewPolicy.needsManualSpeakerReview(
        "restored_draft"));
    assertFalse(VoiceSpeakerReviewPolicy.needsManualSpeakerReview(null));
  }

  @Test public void phoneCannotAnnounceAutomaticReadinessAgainstGuardedServer() {
    assertFalse(VoiceSpeakerReviewPolicy.canAdvertiseAutomaticCommit(
        true,true,true));
    assertFalse(VoiceSpeakerReviewPolicy.canAdvertiseAutomaticCommit(
        false,true,false));
    assertFalse(VoiceSpeakerReviewPolicy.canAdvertiseAutomaticCommit(
        true,false,false));
    assertTrue(VoiceSpeakerReviewPolicy.canAdvertiseAutomaticCommit(
        true,true,false));
  }
}
