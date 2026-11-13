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
    receipts.flush(value -> false,1,true);
    assertEquals(1, receipts.pendingCount());
    receipts.flush(value -> { emitted.add(value);return true; },2,true);
    receipts.flush(value -> { emitted.add(value);return true; },3,true);
    assertEquals(1, receipts.pendingCount());
    assertEquals(java.util.Arrays.asList("receipt-one","receipt-one"),emitted);
    assertFalse(receipts.confirm("turn-one",2,"wrong"));
    assertTrue(receipts.confirm("turn-one",2,"stream-one"));
    assertEquals(0, receipts.pendingCount());
  }

  @Test public void confirmedProtocolWaitsForServerAndRetriesAfterReconnectOnly() {
    VoicePlaybackReceipts receipts=new VoicePlaybackReceipts();
    receipts.remember("turn",11,"s","message");
    List<String> sent=new ArrayList<>();
    receipts.flush(v -> { sent.add(v); return true; },3,true);
    receipts.flush(v -> { sent.add(v); return true; },3,true);
    assertEquals(java.util.Arrays.asList("message"),sent);
    assertEquals(1,receipts.pendingCount());
    receipts.flush(v -> { sent.add(v); return true; },4,true);
    assertEquals(java.util.Arrays.asList("message","message"),sent);
    assertFalse(receipts.confirm("turn",11,"other"));
    assertFalse(receipts.confirm("turn",12,"s"));
    assertTrue(receipts.confirm("turn",11,"s"));
    assertFalse(receipts.confirm("turn",11,"s"));
    assertEquals(0,receipts.pendingCount());
    receipts.flush(v -> { sent.add(v); return true; },5,true);
    assertEquals(2,sent.size());
  }

  @Test public void legacyServerRemovesAfterLocallyAcceptedSend() {
    VoicePlaybackReceipts receipts=new VoicePlaybackReceipts();
    receipts.remember("turn",11,"s","legacy");
    List<String> sent=new ArrayList<>();
    receipts.flush(v -> { sent.add(v); return true; },2,false);
    assertEquals(0,receipts.pendingCount());
    receipts.flush(v -> { sent.add(v); return true; },3,false);
    assertEquals(java.util.Arrays.asList("legacy"),sent);
  }

  @Test public void failedQueueDoesNotDropLaterConfirmations() {
    VoicePlaybackReceipts receipts=new VoicePlaybackReceipts();
    receipts.remember("one",1,"a","first");
    receipts.remember("two",2,"b","second");
    List<String> sent=new ArrayList<>();
    receipts.flush(value -> { sent.add(value);return !value.equals("first"); },5,false);
    assertEquals(java.util.Arrays.asList("first"),sent);
    assertEquals(2,receipts.pendingCount());
    sent.clear();
    receipts.flush(value -> { sent.add(value);return true; },5,false);
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
    receipts.flush(value -> { sent.add(value); return true; },1,true);
    assertTrue(sent.isEmpty());
  }
}
