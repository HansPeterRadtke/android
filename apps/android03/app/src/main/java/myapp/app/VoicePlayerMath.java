package myapp.app;

import java.util.Locale;

/** Pure deterministic math for bounded PCM player state. */
final class VoicePlayerMath {
  private VoicePlayerMath() {}

  static int bytesToMs(int bytes, int sampleRate, int sampleWidth, int channels) {
    if (bytes <= 0 || sampleRate <= 0 || sampleWidth <= 0 || channels <= 0) return 0;
    long denominator = (long) sampleRate * sampleWidth * channels;
    return (int) Math.min(Integer.MAX_VALUE, (long) bytes * 1000L / denominator);
  }

  static int msToBytes(int ms, int sampleRate, int sampleWidth, int channels, int maxBytes) {
    if (ms <= 0 || sampleRate <= 0 || sampleWidth <= 0 || channels <= 0 || maxBytes <= 0) return 0;
    long value = (long) ms * sampleRate * sampleWidth * channels / 1000L;
    return (int) Math.min(maxBytes, value);
  }

  static int clampMs(int value, int available) {
    return Math.max(0, Math.min(value, Math.max(0, available)));
  }

  static String formatMs(int ms) {
    int totalSeconds = Math.max(0, ms / 1000);
    int hours = totalSeconds / 3600;
    int minutes = (totalSeconds % 3600) / 60;
    int seconds = totalSeconds % 60;
    if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
    return String.format(Locale.US, "%02d:%02d", minutes, seconds);
  }
}
