package myapp.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoicePlaybackPositionTest {
  @Test public void queuedAudioIsNotReportedAsHeard() {
    assertEquals(3200,VoicePlaybackPosition.renderedBytes(0,16000,1600,2));
  }
  @Test public void resumeBeginsAtRenderedPositionWithSeekBase() {
    int heard=VoicePlaybackPosition.renderedBytes(3200,19200,1600,2);
    assertEquals(6400,heard);
    assertEquals(heard,VoicePlaybackPosition.renderedBytes(heard,heard+16000,0,2));
  }
  @Test public void staleLargeHeadNeverExceedsQueuedMedia() {
    assertEquals(19200,VoicePlaybackPosition.renderedBytes(3200,19200,0xffffffffL,2));
  }
  @Test(expected=IllegalArgumentException.class) public void invalidTimelineIsRejected() {
    VoicePlaybackPosition.renderedBytes(10,8,0,2);
  }
}
