package myapp.app;

/**
 * An ASR draft may be claimed by the user only after actual speech text
 * appeared, or after the backend returned a pending speech turn ID.
 *
 * Starting the microphone while typing an ordinary unsent message is NOT
 * reason to claim: the server stops accepting further audio on a draft_claim.
 */
final class VoiceDraftClaimPolicy {
  private VoiceDraftClaimPolicy() {}

  static boolean shouldClaim(boolean serverTextUpdate, boolean autoTranscribe,
      boolean provisionalSpeechTextVisible, boolean finalSpeechTurnPending,
      boolean alreadyClaimed) {
    return !serverTextUpdate && autoTranscribe && !alreadyClaimed
        && (provisionalSpeechTextVisible || finalSpeechTurnPending);
  }
}
