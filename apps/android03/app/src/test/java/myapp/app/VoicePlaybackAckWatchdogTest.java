package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoicePlaybackAckWatchdogTest {
  @Test public void missingAckForcesAtMostTwoReconnectionsWithoutDuplicateTimers() {
    VoicePlaybackAckWatchdog watchdog=new VoicePlaybackAckWatchdog();
    long first=watchdog.schedule(1);
    assertTrue(first>0);
    assertEquals(-1,watchdog.schedule(1));
    assertTrue(watchdog.expire(1,first,true));
    assertFalse(watchdog.expire(1,first,true));
    long second=watchdog.schedule(2);
    assertTrue(watchdog.expire(2,second,true));
    long third=watchdog.schedule(3);
    assertFalse(watchdog.expire(3,third,true));
    assertEquals(2,watchdog.reconnectAttempts());
    watchdog.allConfirmed();
    assertEquals(0,watchdog.reconnectAttempts());
    assertTrue(watchdog.expire(4,watchdog.schedule(4),true));
  }

  @Test public void staleTimerCannotReconnectNewSessionOrAfterAck() {
    VoicePlaybackAckWatchdog watchdog=new VoicePlaybackAckWatchdog();
    long first=watchdog.schedule(5);
    long second=watchdog.schedule(6);
    assertFalse(watchdog.expire(5,first,true));
    assertFalse(watchdog.expire(6,second,false));
    long third=watchdog.schedule(7);
    watchdog.allConfirmed();
    assertFalse(watchdog.expire(7,third,true));
    assertEquals(0,watchdog.reconnectAttempts());
  }
}
