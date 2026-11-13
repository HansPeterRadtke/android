package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public class VoicePlaybackReceiptsTest {
  @Test public void disconnectedCompletionWaitsForReopenWithoutPlayingAgain() {
    VoicePlaybackReceipts receipts=new VoicePlaybackReceipts();
    receipts.remember("turn-one",2,"stream-one","receipt-one");
    List<String> emitted=new ArrayList<>();
    receipts.flush(value -> false);
    assertEquals(1, receipts.pendingCount());
    receipts.flush(value -> { emitted.add(value);return true; });
    receipts.flush(value -> { emitted.add(value);return true; });
    assertEquals(0, receipts.pendingCount());
    assertEquals(java.util.Arrays.asList("receipt-one"),emitted);
  }

  @Test public void failedQueueDoesNotDropLaterConfirmations() {
    VoicePlaybackReceipts receipts=new VoicePlaybackReceipts();
    receipts.remember("one",1,"a","first");
    receipts.remember("two",2,"b","second");
    List<String> sent=new ArrayList<>();
    receipts.flush(value -> { sent.add(value);return !value.equals("first"); });
    assertEquals(java.util.Arrays.asList("first"),sent);
    assertEquals(2,receipts.pendingCount());
    sent.clear();
    receipts.flush(value -> { sent.add(value);return true; });
    assertEquals(java.util.Arrays.asList("first","second"),sent);
    assertEquals(0,receipts.pendingCount());
  }

  @Test public void duplicateCompletionIsCoalescedAndNewConversationClearsOldReceipts() {
    VoicePlaybackReceipts receipts=new VoicePlaybackReceipts();
    receipts.remember("a",1,"s","receipt");
    receipts.remember("a",1,"s","receipt");
    receipts.remember("",2,"s","invalid");
    assertEquals(1,receipts.pendingCount());
    receipts.clear();
    List<String> sent=new ArrayList<>();
    receipts.flush(value -> { sent.add(value); return true; });
    assertTrue(sent.isEmpty());
  }
}
