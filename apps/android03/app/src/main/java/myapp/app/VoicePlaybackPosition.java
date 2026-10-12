package myapp.app;

/** Convert the device's rendered frames to an absolute, bounded media position. */
final class VoicePlaybackPosition {
  static int renderedBytes(int base, int written, long frames, int frameBytes) {
    if(base<0 || written<base || frames<0 || frameBytes<=0)
      throw new IllegalArgumentException("invalid playback timeline");
    long available=(written-(long)base)/frameBytes;
    return base+(int)(Math.min(frames,available)*frameBytes);
  }
}
