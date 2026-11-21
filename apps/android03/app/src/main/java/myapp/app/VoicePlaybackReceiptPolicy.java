package myapp.app;

/** An ACK for an earlier stream cannot complete a different audio reply. */
final class VoicePlaybackReceiptPolicy {
  private VoicePlaybackReceiptPolicy() {}

  static boolean matches(String expectedTurn, String expectedStream, int expectedGeneration,
      String returnedTurn, String returnedStream, int returnedGeneration) {
    return expectedTurn != null && !expectedTurn.isEmpty()
        && expectedStream != null && !expectedStream.isEmpty()
        && expectedGeneration >= 0
        && expectedGeneration == returnedGeneration
        && expectedTurn.equals(returnedTurn)
        && expectedStream.equals(returnedStream);
  }
}
