package myapp.app;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class VoiceSubmissionPersistenceTest {
  @Test public void pendingMessageIsFrozenBeforeWriterStartsAndSharedByRetries() {
    ArrayDeque<Runnable> work = new ArrayDeque<>();
    VoiceSubmissionPersistence store = new VoiceSubmissionPersistence(work::add);
    AtomicInteger writes = new AtomicInteger();
    VoiceSubmissionPersistence.Writer writer = (payload, text) -> { writes.incrementAndGet(); return true; };
    VoiceSubmissionPersistence.Attempt first = store.retain("stable-id", " exact text \n", writer);
    assertSame(first, store.current());
    assertFalse(first.durable());
    assertFalse(store.acknowledge("stable-id"));
    assertSame(first, store.retain("stable-id", " exact text \n", writer));
    assertEquals(1, work.size());
    try { store.retain("new-id", "newer editor text", writer); fail("Overwrote pending text"); }
    catch (IllegalStateException expected) { }
    work.remove().run();
    assertTrue(first.durable());
    assertEquals(1, writes.get());
    assertFalse(store.acknowledge("wrong-id"));
    assertTrue(store.acknowledge("stable-id"));
    assertNull(store.current());
    assertNotSame(first, store.retain("new-id", "newer editor text", writer));
  }

  @Test public void failedOrInterruptedWriteCannotOpenTheNetworkBarrier() {
    for (boolean throwsException : new boolean[]{false, true}) {
      VoiceSubmissionPersistence store = new VoiceSubmissionPersistence(Runnable::run);
      VoiceSubmissionPersistence.Attempt failed = store.retain("same-id", "same exact text", (p, t) -> {
        if (throwsException) throw new IOException("disk unavailable");
        return false;
      });
      assertFalse(failed.durable());
      assertFalse(store.acknowledge("same-id"));
      VoiceSubmissionPersistence.Attempt retry = store.retain("same-id", "same exact text", (p, t) -> true);
      assertNotSame(failed, retry);
      assertEquals(failed.payload, retry.payload);
      assertTrue(retry.durable());
    }
  }

  @Test public void rejectedWriterIsObservableAndRetainsTheMessage() {
    VoiceSubmissionPersistence store = new VoiceSubmissionPersistence(task -> {
      throw new RejectedExecutionException("executor unavailable");
    });
    VoiceSubmissionPersistence.Attempt attempt = store.retain("id", "text", (p, t) -> true);
    assertTrue(attempt.result.isCompletedExceptionally());
    assertFalse(attempt.durable());
    assertSame(attempt, store.current());
  }

  @Test public void invalidInputsDoNotCreatePendingWork() {
    VoiceSubmissionPersistence store = new VoiceSubmissionPersistence(Runnable::run);
    for (String[] input : new String[][]{{null,"text"},{"","text"},{"id",null},{"id"," \n"}}) {
      try { store.retain(input[0],input[1],(p,t)->true); fail("Invalid input accepted"); }
      catch (IllegalArgumentException expected) { }
    }
    try { store.retain("id","text",null); fail("Missing writer accepted"); }
    catch (IllegalArgumentException expected) { }
    try { new VoiceSubmissionPersistence(null); fail("Missing executor accepted"); }
    catch (IllegalArgumentException expected) { }
    assertNull(store.current());
    assertFalse(store.acknowledge(null));
    assertFalse(store.acknowledge(""));
  }
}
