package myapp.app;

import java.util.LinkedHashMap;
import java.util.Map;

/** Rendered audio confirmations retained across reconnect until the server has saved them. */
final class VoicePlaybackReceipts {
  private static final class Receipt {
    final String payload;
    long lastQueuedConnection = -1;
    Receipt(String payload) { this.payload=payload; }
  }
  private final LinkedHashMap<String, Receipt> pending = new LinkedHashMap<>();

  private static String key(String turn,int generation,String stream) {
    if (turn==null || turn.isEmpty() || stream==null || stream.isEmpty() || generation<0) return "";
    return turn+"\u0000"+generation+"\u0000"+stream;
  }

  synchronized void remember(String turn,int generation,String stream,String json) {
    String id=key(turn,generation,stream);
    if (id.isEmpty()) return;
    // Duplicate completion callbacks never overwrite the delivery state.
    if (!pending.containsKey(id)) pending.put(id,new Receipt(json));
  }

  /** Legacy send-only servers do not acknowledge. New servers do, so retain until ACK. */
  synchronized void flush(java.util.function.Function<String,Boolean> sender,
                          long networkGeneration,boolean requireServerAck) {
    java.util.Iterator<Map.Entry<String,Receipt>> iterator=pending.entrySet().iterator();
    while (iterator.hasNext()) {
      Receipt receipt=iterator.next().getValue();
      if (requireServerAck && receipt.lastQueuedConnection==networkGeneration) continue;
      if (!Boolean.TRUE.equals(sender.apply(receipt.payload))) break;
      if (requireServerAck) receipt.lastQueuedConnection=networkGeneration;
      else iterator.remove();
    }
  }

  synchronized boolean confirm(String turn,int generation,String stream) {
    String id=key(turn,generation,stream);
    return !id.isEmpty() && pending.remove(id)!=null;
  }

  synchronized int pendingCount() { return pending.size(); }
  synchronized void clear() { pending.clear(); }
}
