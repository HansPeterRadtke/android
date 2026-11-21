package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoicePlaybackCompletionPolicyTest {
  @Test public void lastNetworkFrameAndAllWritesDoNotMeanAudiblyFinished() {
    assertFalse(VoicePlaybackCompletionPolicy.allFramesPlayed(3200,0,2));
    assertFalse(VoicePlaybackCompletionPolicy.allFramesPlayed(3200,1599,2));
    assertTrue(VoicePlaybackCompletionPolicy.allFramesPlayed(3200,1600,2));
  }
  @Test public void partialAudioOrInvalidFrameLayoutNeverAcknowledges() {
    assertFalse(VoicePlaybackCompletionPolicy.allFramesPlayed(0,50,2));
    assertFalse(VoicePlaybackCompletionPolicy.allFramesPlayed(-1,500,2));
    assertFalse(VoicePlaybackCompletionPolicy.allFramesPlayed(3200,500,0));
    assertFalse(VoicePlaybackCompletionPolicy.allFramesPlayed(3200,500,-2));
    assertTrue(VoicePlaybackCompletionPolicy.allFramesPlayed(3199,1600,2));
  }
  @Test public void unsignedAndroidPlaybackHeadDoesNotOverflowIntoFalseNegative() {
    // Although current buffer limits never reach wrap, compare unsigned.
    assertTrue(VoicePlaybackCompletionPolicy.allFramesPlayed(2048,-1,2));
  }
}
