package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceDraftClaimPolicyTest {
  private static boolean claim(boolean server, boolean auto, boolean provisional,
      boolean pending, boolean already) {
    return VoiceDraftClaimPolicy.shouldClaim(server,auto,provisional,pending,already);
  }

  @Test public void ordinaryTypingNeverStopsAnActiveMicrophoneBeforeAnyAsrDraft() {
    assertFalse(claim(false,true,false,false,false));
    assertFalse(claim(false,true,false,false,true));
    assertFalse(claim(false,false,false,false,false));
  }

  @Test public void humanEditsOfSpeechPartialOrFinalClaimOnce() {
    assertTrue(claim(false,true,true,false,false));
    assertTrue(claim(false,true,false,true,false));
    assertTrue(claim(false,true,true,true,false));
    assertFalse(claim(false,true,true,true,true));
  }

  @Test public void serverTranscriptsDoNotClaimThemselves() {
    assertFalse(claim(true,true,true,false,false));
    assertFalse(claim(true,true,false,true,false));
    assertFalse(claim(true,true,true,true,false));
  }

  @Test public void manualLocalRecordingNeverClaimsAutoTranscription() {
    assertFalse(claim(false,false,true,false,false));
    assertFalse(claim(false,false,false,true,false));
  }

  @Test public void multipleBackToBackHumanEditsCannotSendDuplicateClaims() {
    assertTrue(claim(false,true,true,false,false));
    assertFalse(claim(false,true,true,false,true));
  }
}
