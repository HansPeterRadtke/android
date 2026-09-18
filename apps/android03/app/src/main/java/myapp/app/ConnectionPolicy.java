package myapp.app;

final class ConnectionPolicy {
  private ConnectionPolicy() {}

  static long reconnectDelayMs(int attempt, long baseMs, long maxMs, int maxExponent, long jitterMs, long jitterValue) {
    int exponent = Math.max(0, Math.min(maxExponent, attempt));
    long scaled;
    if (exponent >= 62 || baseMs > Long.MAX_VALUE >> exponent) scaled = maxMs;
    else scaled = baseMs << exponent;
    long bounded = Math.min(maxMs, scaled);
    long jitter = Math.max(0L, Math.min(jitterMs, jitterValue));
    return Math.min(maxMs, bounded + jitter);
  }

  static boolean shouldSendAudio(long websocketQueueBytes, long maxQueuedAudioBytes) {
    return websocketQueueBytes <= maxQueuedAudioBytes;
  }

  static String boundedDiagnostics(String existing, String nextLine, int maxChars) {
    String joined = (existing == null ? "" : existing) + (nextLine == null ? "" : nextLine);
    if (joined.length() <= maxChars) return joined;
    return joined.substring(joined.length() - maxChars);
  }

  static boolean heartbeatStale(long nowMs, long lastHeartbeatMs, long staleMs) {
    return lastHeartbeatMs > 0 && nowMs - lastHeartbeatMs > staleMs;
  }
}
