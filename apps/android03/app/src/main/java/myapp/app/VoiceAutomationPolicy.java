package myapp.app;

final class VoiceAutomationPolicy {
  private VoiceAutomationPolicy() {}
  /**
   * Compatibility entry point used by Android03. A phone-side checkbox or
   * restored preference cannot establish the identity of the speaker.
   * The current server has no trusted owner-speaker verifier, so this remains
   * manual-only until a server-authenticated per-turn admission is integrated.
   */
  static boolean effectiveAutoSend(boolean autoTranscribe, boolean requestedAutoSend) {
    return effectiveAutoSend(autoTranscribe, requestedAutoSend, false);
  }

  /**
   * A future caller may pass true ONLY for a verified owner-speaker decision
   * originating from the authenticated SERVER on the exact current turn.
   * Never derive this from client_state, microphone confidence or a local
   * user preference. No current Android03 call has such server authority.
   */
  static boolean effectiveAutoSend(boolean autoTranscribe, boolean requestedAutoSend,
                                   boolean trustedServerVerifiedOwnerSpeaker) {
    return autoTranscribe && requestedAutoSend && trustedServerVerifiedOwnerSpeaker;
  }
  static boolean shouldStreamMic(boolean autoTranscribe) { return autoTranscribe; }
  static boolean shouldKeepLocalRecording(boolean autoTranscribe) { return !autoTranscribe; }
}
