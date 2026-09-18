package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class ConnectionPolicyTest {
  @Test public void reconnectIsBoundedAndJittered() {
    assertEquals(250L, ConnectionPolicy.reconnectDelayMs(0, 250, 8000, 6, 250, 0));
    assertEquals(8000L, ConnectionPolicy.reconnectDelayMs(99, 250, 8000, 6, 250, 999));
  }

  @Test public void audioQueueAdmissionIsBounded() {
    assertTrue(ConnectionPolicy.shouldSendAudio(16000, 16000));
    assertFalse(ConnectionPolicy.shouldSendAudio(16001, 16000));
  }

  @Test public void diagnosticsAreBounded() {
    assertEquals("cdef", ConnectionPolicy.boundedDiagnostics("abcd", "ef", 4));
  }

  @Test public void heartbeatStalenessIsDeterministic() {
    assertFalse(ConnectionPolicy.heartbeatStale(1000, 0, 30000));
    assertFalse(ConnectionPolicy.heartbeatStale(31000, 1000, 30000));
    assertTrue(ConnectionPolicy.heartbeatStale(31001, 1000, 30000));
  }
}
