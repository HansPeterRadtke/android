package myapp.app;

import java.util.LinkedHashMap;
import java.util.Map;

/** Connection-local delivery queue for playback confirmations. No audio is replayed here. */
final class VoicePlaybackReceipts {
  private final LinkedHashMap<String, String> pending = new LinkedHashMap<>();

  synchronized void remember(String turn, int generation, String stream, String json) {
    if (turn.isEmpty() || stream.isEmpty() || generation < 0) return;
    String key=turn+"\u0000"+generation+"\u0000"+stream;
    pending.put(key,json);
    // Never silently discard an unconfirmed receipt to make space.
  }

  synchronized void flush(java.util.function.Function<String,Boolean> sender) {
    java.util.Iterator<Map.Entry<String,String>> iterator=pending.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String,String> entry=iterator.next();
      if (!Boolean.TRUE.equals(sender.apply(entry.getValue()))) break;
      iterator.remove();
    }
  }

  synchronized int pendingCount() { return pending.size(); }
  synchronized void clear() { pending.clear(); }
}
