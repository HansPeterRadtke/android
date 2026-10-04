package myapp.app;

import java.util.HashMap;
import java.util.Map;

/** Deduplicates SWAAG worker events independently for each worker identity. */
final class WorkerEventSequencePolicy {
  private static final String UNKNOWN_WORKER = "<unknown>";
  private final Map<String, Integer> latestByWorker = new HashMap<>();

  synchronized boolean accept(String workerId, int sequence) {
    if (sequence <= 0) return true;
    String key = normalize(workerId);
    int previous = latestByWorker.getOrDefault(key, 0);
    if (sequence <= previous) return false;
    latestByWorker.put(key, sequence);
    return true;
  }

  private static String normalize(String workerId) {
    if (workerId == null) return UNKNOWN_WORKER;
    String clean = workerId.trim();
    return clean.isEmpty() ? UNKNOWN_WORKER : clean;
  }
}
