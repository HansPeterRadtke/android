package myapp.app;

final class VoiceConnectionTracker {
  private long generation = 0L;
  private int reconnectAttempt = 0;
  private boolean open = false;
  private boolean activeAttempt = false;
  private boolean mediaConfirmed = false;
  private long openedAtMs = 0L;
  private long lastHeartbeatMs = 0L;
  private long lastMediaAckMs = 0L;

  synchronized long beginConnect() {
    generation += 1L;
    open = false;
    activeAttempt = true;
    mediaConfirmed = false;
    openedAtMs = 0L;
    lastHeartbeatMs = 0L;
    return generation;
  }

  synchronized boolean owns(long candidateGeneration) {
    return candidateGeneration == generation;
  }

  synchronized boolean onOpen(long candidateGeneration, long nowMs) {
    if (!owns(candidateGeneration)) return false;
    open = true;
    mediaConfirmed = false;
    openedAtMs = nowMs;
    lastHeartbeatMs = nowMs;
    return true;
  }

  synchronized boolean onHeartbeat(long candidateGeneration, long nowMs) {
    if (!owns(candidateGeneration) || !open) return false;
    lastHeartbeatMs = nowMs;
    return true;
  }

  synchronized boolean onMediaAck(long candidateGeneration, long nowMs) {
    if (!owns(candidateGeneration) || !open) return false;
    lastMediaAckMs = nowMs;
    lastHeartbeatMs = nowMs;
    mediaConfirmed = true;
    reconnectAttempt = 0;
    return true;
  }

  synchronized boolean onClosed(long candidateGeneration) {
    if (!owns(candidateGeneration) || !activeAttempt) return false;
    activeAttempt = false;
    open = false;
    mediaConfirmed = false;
    return true;
  }

  synchronized int nextReconnectAttempt(int maxExponent) {
    int attempt = reconnectAttempt;
    reconnectAttempt = Math.min(Math.max(0, maxExponent), reconnectAttempt + 1);
    return attempt;
  }

  synchronized boolean heartbeatStale(long nowMs, long staleMs) {
    return open && lastHeartbeatMs > 0L && nowMs - lastHeartbeatMs > staleMs;
  }

  synchronized boolean mediaPathStale(long nowMs, long staleMs) {
    if (!open || openedAtMs <= 0L) return false;
    long reference = mediaConfirmed && lastMediaAckMs > 0L ? lastMediaAckMs : openedAtMs;
    return nowMs - reference > staleMs;
  }

  synchronized boolean isOpen() { return open; }
  synchronized boolean isMediaConfirmed() { return mediaConfirmed; }
  synchronized long currentGeneration() { return generation; }
  synchronized long lastMediaAckMs() { return lastMediaAckMs; }
}
