package myapp.app;

/** One-shot policy: streaming autoplay starts exactly when the first PCM frame exists. */
final class VoiceStreamingPlaybackPolicy {
  private boolean pending;
  VoiceStreamingPlaybackPolicy(boolean autoPlay) { pending = autoPlay; }
  synchronized boolean shouldStart(boolean hasAudio) {
    if (!pending || !hasAudio) return false;
    pending = false;
    return true;
  }
}
