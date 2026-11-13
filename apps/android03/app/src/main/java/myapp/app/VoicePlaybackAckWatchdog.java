package myapp.app;

/** Bounds reconnect attempts for a playback receipt lacking server confirmation. */
final class VoicePlaybackAckWatchdog {
  private static final int MAX_RECONNECTS = 2;
  private int reconnectAttempts;
  private long pendingConnection=-1;
  private long ticket;

  /** One timer per live connection; obsolete timers cannot act on replacements. */
  synchronized long schedule(long connection) {
    if (connection<=0 || pendingConnection==connection) return -1;
    pendingConnection=connection;
    return ++ticket;
  }

  synchronized boolean expire(long connection,long token,boolean stillPending) {
    if (pendingConnection!=connection || ticket!=token) return false;
    pendingConnection=-1;
    if (!stillPending || reconnectAttempts>=MAX_RECONNECTS) return false;
    reconnectAttempts++;
    return true;
  }

  synchronized void allConfirmed() {
    reconnectAttempts=0;
    pendingConnection=-1;
    ticket++;
  }

  synchronized int reconnectAttempts() { return reconnectAttempts; }
}
