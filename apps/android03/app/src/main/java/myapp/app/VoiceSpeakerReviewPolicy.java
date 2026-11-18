package myapp.app;

/**
 * The network socket authenticates the PHONE, not the human speaker.
 * The app never asserts or creates an owner-speaker credential.
 */
final class VoiceSpeakerReviewPolicy {
  private VoiceSpeakerReviewPolicy() {}

  static boolean needsManualSpeakerReview(String reason) {
    return "speaker_identity_unverified".equals(reason);
  }

  static boolean canAdvertiseAutomaticCommit(boolean automaticPreference,
                                              boolean clientCommitProtocol,
                                              boolean serverRequiresSpeakerReview) {
    return automaticPreference && clientCommitProtocol && !serverRequiresSpeakerReview;
  }
}
