package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceOverviewPolicyTest {
  @Test public void idleWorkerIsHidden() {
    assertFalse(VoiceOverviewPolicy.showWorker("none"));
    assertFalse(VoiceOverviewPolicy.showWorker("idle"));
    assertTrue(VoiceOverviewPolicy.showWorker("working"));
    assertTrue(VoiceOverviewPolicy.showWorker("input_required"));
    assertFalse(VoiceOverviewPolicy.showWorker("completed"));
    assertFalse(VoiceOverviewPolicy.showWorker("canceled"));
  }

  @Test public void replayRowExistsOnlyWhenAudioExists() {
    assertFalse(VoiceOverviewPolicy.showReplay(false, false));
    assertTrue(VoiceOverviewPolicy.showReplay(true, false));
    assertTrue(VoiceOverviewPolicy.showReplay(false, true));
  }

  @Test public void transcriptPanelIsContextual() {
    assertFalse(VoiceOverviewPolicy.showTranscript("", false));
    assertTrue(VoiceOverviewPolicy.showTranscript("partial speech", false));
    assertTrue(VoiceOverviewPolicy.showTranscript("", true));
  }
}
