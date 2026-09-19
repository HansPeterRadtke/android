package myapp.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VoicePlayerMathTest {
  @Test public void pcmByteAndTimeConversionsAreBounded() {
    assertEquals(1000, VoicePlayerMath.bytesToMs(32000, 16000, 2, 1));
    assertEquals(32000, VoicePlayerMath.msToBytes(1000, 16000, 2, 1, 64000));
    assertEquals(64000, VoicePlayerMath.msToBytes(5000, 16000, 2, 1, 64000));
    assertEquals(0, VoicePlayerMath.bytesToMs(-1, 16000, 2, 1));
  }

  @Test public void seekTargetsClampToAvailableAudio() {
    assertEquals(0, VoicePlayerMath.clampMs(-5000, 30000));
    assertEquals(12000, VoicePlayerMath.clampMs(12000, 30000));
    assertEquals(30000, VoicePlayerMath.clampMs(35000, 30000));
  }

  @Test public void timeFormatStaysCompactAcrossHourBoundary() {
    assertEquals("00:00", VoicePlayerMath.formatMs(0));
    assertEquals("01:05", VoicePlayerMath.formatMs(65000));
    assertEquals("1:01:01", VoicePlayerMath.formatMs(3661000));
  }
}
