package myapp.app;
import static org.junit.Assert.*;
import org.junit.Test;
public class VoiceAutomationPolicyTest {
  @Test public void autoSendRequiresAutoTranscription() {
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(false,true));
    assertTrue(VoiceAutomationPolicy.effectiveAutoSend(true,true));
    assertFalse(VoiceAutomationPolicy.effectiveAutoSend(true,false));
  }
  @Test public void manualModeBuffersInsteadOfStreaming() {
    assertFalse(VoiceAutomationPolicy.shouldStreamMic(false));
    assertTrue(VoiceAutomationPolicy.shouldKeepLocalRecording(false));
  }
}
