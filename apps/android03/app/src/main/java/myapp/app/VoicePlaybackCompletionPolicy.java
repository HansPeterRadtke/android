package myapp.app;

/**
 * A network end or a successful AudioTrack.write is NOT audible playback.
 * A completed reply needs every written PCM frame passed by the Android
 * AudioTrack playback head after the final network frame.
 */
final class VoicePlaybackCompletionPolicy {
  private VoicePlaybackCompletionPolicy() {}

  static boolean allFramesPlayed(int writtenBytes, int playedFrames, int bytesPerFrame) {
    if (writtenBytes <= 0 || bytesPerFrame <= 0) return false;
    long needed = (writtenBytes + (long) bytesPerFrame - 1L) / bytesPerFrame;
    return Integer.toUnsignedLong(playedFrames) >= needed;
  }
}
