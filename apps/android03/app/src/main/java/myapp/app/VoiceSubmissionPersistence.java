package myapp.app;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** One process-owned, exact-message persistence barrier, independent of screens.
 * An attempt is visible before its writer starts. Network submission must wait
 * for successful completion; retries keep the same payload and request identity.
 */
final class VoiceSubmissionPersistence {
  interface Writer { boolean write(String payload, String text) throws Exception; }
  static final VoiceSubmissionPersistence SHARED = new VoiceSubmissionPersistence(
      Executors.newSingleThreadExecutor(task -> new Thread(task, "voice-outbox-writer")));

  static final class Attempt {
    final String payload;
    final String text;
    final CompletableFuture<Boolean> result = new CompletableFuture<>();
    Attempt(String payload, String text) { this.payload = payload; this.text = text; }
    boolean durable() { return result.isDone() && !result.isCompletedExceptionally()
        && Boolean.TRUE.equals(result.getNow(false)); }
  }

  private final Executor executor;
  private Attempt pending;
  VoiceSubmissionPersistence(Executor executor) {
    if (executor == null) throw new IllegalArgumentException("Missing persistence executor");
    this.executor = executor;
  }
  synchronized Attempt current() { return pending; }

  synchronized Attempt retain(String payload, String text, Writer writer) {
    if (payload == null || payload.isEmpty() || text == null || text.trim().isEmpty() || writer == null)
      throw new IllegalArgumentException("Missing outgoing message or writer");
    if (pending != null) {
      if (!pending.payload.equals(payload) || !pending.text.equals(text))
        throw new IllegalStateException("An earlier exact message is still pending");
      if (!pending.result.isDone() || pending.durable()) return pending;
    }
    Attempt attempt = new Attempt(payload, text);
    pending = attempt;
    try {
      executor.execute(() -> {
        try { attempt.result.complete(writer.write(attempt.payload, attempt.text)); }
        catch (Exception failure) { attempt.result.completeExceptionally(failure); }
      });
    } catch (RuntimeException failure) { attempt.result.completeExceptionally(failure); }
    return attempt;
  }

  synchronized boolean isDurable(String payload) {
    // No in-process attempt means this entry was recovered from preferences.
    return payload != null && !payload.isEmpty()
        && (pending == null || (pending.payload.equals(payload) && pending.durable()));
  }
  synchronized boolean acknowledge(String payload) {
    if (!isDurable(payload)) return false;
    pending = null;
    return true;
  }
}
