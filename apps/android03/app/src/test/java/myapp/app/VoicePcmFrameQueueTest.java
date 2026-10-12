package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoicePcmFrameQueueTest {
  @Test public void retainsExactBytesAndOrderingWithoutAliasingProducer() {
    VoicePcmFrameQueue q=new VoicePcmFrameQueue(12);
    byte[] a={1,2,3,4};
    assertTrue(q.offer(a,4));
    a[0]=99;
    assertTrue(q.offer(new byte[]{5,6},2));
    assertArrayEquals(new byte[]{1,2,3,4},q.poll());
    assertArrayEquals(new byte[]{5,6},q.poll());
    assertNull(q.poll());
    assertEquals(6,q.acceptedBytes());
  }
  @Test public void backpressureDoesNotPretendDroppedSamplesWereSent() {
    VoicePcmFrameQueue q=new VoicePcmFrameQueue(4);
    assertTrue(q.offer(new byte[]{1,2,3,4},4));
    assertFalse(q.offer(new byte[]{5,6},2));
    assertEquals(1,q.refusedFrames());
    assertEquals(6,q.capturedBytes());
    assertEquals(4,q.acceptedBytes());
    assertEquals(0,q.sentBytes());
    assertEquals(4,q.pendingBytes());
  }
  @Test public void restoresRejectedNetworkFrameAtFront() {
    VoicePcmFrameQueue q=new VoicePcmFrameQueue(10);
    q.offer(new byte[]{1,2},2);
    q.offer(new byte[]{3,4},2);
    byte[] first=q.poll();
    q.requeueFirst(first);
    assertArrayEquals(new byte[]{1,2},q.poll());
    assertArrayEquals(new byte[]{3,4},q.poll());
    q.acknowledgeSocketAccepted(4);
    assertEquals(4,q.sentBytes());
  }
  @Test public void closedQueueRejectsNewAudioButCanDrainExisting() {
    VoicePcmFrameQueue q=new VoicePcmFrameQueue(8);
    q.offer(new byte[]{1,2},2);
    q.close();
    assertFalse(q.offer(new byte[]{3,4},2));
    assertArrayEquals(new byte[]{1,2},q.poll());
    assertTrue(q.isClosed());
  }
  @Test public void networkWorkerCommitsOnlySocketAcceptedHead() {
    VoicePcmFrameQueue q=new VoicePcmFrameQueue(4);
    byte[] first={1,2};
    assertTrue(q.offer(first,2));
    byte[] held=q.peek();
    assertNotNull(held);
    assertTrue(q.offer(new byte[]{3,4},2));
    assertFalse(q.offer(new byte[]{5,6},2));
    assertEquals(4,q.pendingBytes());
    assertFalse(q.commitAcceptedHead(new byte[]{1,2}));
    assertEquals(4,q.pendingBytes());
    assertTrue(q.commitAcceptedHead(held));
    assertEquals(2,q.pendingBytes());
    assertEquals(2,q.sentBytes());
    assertArrayEquals(new byte[]{3,4},q.poll());
  }

  @Test public void rejectsInvalidInputsAndCapacity() {
    try {new VoicePcmFrameQueue(0);fail();} catch(IllegalArgumentException expected) {}
    VoicePcmFrameQueue q=new VoicePcmFrameQueue(8);
    for (byte[] v:new byte[][]{null,new byte[]{1}}) {
      try{q.offer(v,v==null?2:1);fail();}catch(IllegalArgumentException expected){}
    }
    try{q.offer(new byte[]{1,2},3);fail();}catch(IllegalArgumentException expected){}
    try{q.acknowledgeSocketAccepted(0);fail();}catch(IllegalArgumentException expected){}
  }
}
