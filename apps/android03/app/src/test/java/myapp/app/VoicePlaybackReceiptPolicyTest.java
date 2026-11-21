package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoicePlaybackReceiptPolicyTest {
  @Test public void exactDurableServerReceiptCompletesOnlyCorrespondingReply() {
    assertTrue(VoicePlaybackReceiptPolicy.matches("turn-1","stream-a",2,
        "turn-1","stream-a",2));
    assertFalse(VoicePlaybackReceiptPolicy.matches("turn-1","stream-a",2,
        "turn-2","stream-a",2));
    assertFalse(VoicePlaybackReceiptPolicy.matches("turn-1","stream-a",2,
        "turn-1","stream-b",2));
    assertFalse(VoicePlaybackReceiptPolicy.matches("turn-1","stream-a",2,
        "turn-1","stream-a",3));
  }
  @Test public void invalidOrMissingStreamIdentityNeverAcknowledges() {
    assertFalse(VoicePlaybackReceiptPolicy.matches(null,"stream-a",2,
        "turn-1","stream-a",2));
    assertFalse(VoicePlaybackReceiptPolicy.matches("turn-1","",2,
        "turn-1","",2));
    assertFalse(VoicePlaybackReceiptPolicy.matches("turn-1","stream-a",-1,
        "turn-1","stream-a",-1));
  }
}
