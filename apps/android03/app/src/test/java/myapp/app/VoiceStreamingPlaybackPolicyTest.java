package myapp.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceStreamingPlaybackPolicyTest {
  @Test public void autoplayWaitsForFirstAudioFrame() {
    VoiceStreamingPlaybackPolicy policy = new VoiceStreamingPlaybackPolicy(true);
    assertFalse(policy.shouldStart(false));
    assertTrue(policy.shouldStart(true));
    assertFalse(policy.shouldStart(true));
  }

  @Test public void manualPlaybackNeverAutoStarts() {
    VoiceStreamingPlaybackPolicy policy = new VoiceStreamingPlaybackPolicy(false);
    assertFalse(policy.shouldStart(true));
  }
}
