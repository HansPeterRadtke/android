package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceOverviewPolicyTest {
  @Test public void idleWorkerIsHidden() {
    assertFalse(VoiceOverviewPolicy.showWorker("none"));
    assertFalse(VoiceOverviewPolicy.showWorker("idle"));
    assertTrue(VoiceOverviewPolicy.showWorker("working"));
    assertTrue(VoiceOverviewPolicy.showWorker("input_required"));
    assertTrue(VoiceOverviewPolicy.showWorker("completed"));
    assertTrue(VoiceOverviewPolicy.showWorker("canceled"));
  }

  @Test public void optionalQuestionsRemainDiscoverableWithoutInterrupting() {
    assertFalse(VoiceOverviewPolicy.showQuestions(0));
    assertTrue(VoiceOverviewPolicy.showQuestions(1));
    assertTrue(VoiceOverviewPolicy.showQuestions(12));
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

  @Test public void intentionallyEndlessProgressIsExplicitlyRecognized() {
    assertTrue(VoiceOverviewPolicy.isIntentionallyEndless("intentionally_endless"));
    assertTrue(VoiceOverviewPolicy.isIntentionallyEndless("contains_intentionally_endless_work"));
    assertTrue(VoiceOverviewPolicy.isIntentionallyEndless("continuous"));
    assertFalse(VoiceOverviewPolicy.isIntentionallyEndless("finite"));
    assertFalse(VoiceOverviewPolicy.isIntentionallyEndless(""));
  }

  @Test public void progressIndicatorsFollowOnlyActiveLongRunningStates() {
    assertTrue(VoiceOverviewPolicy.showForegroundProgress("THINKING"));
    assertTrue(VoiceOverviewPolicy.showForegroundProgress("TRANSCRIBING"));
    assertTrue(VoiceOverviewPolicy.showForegroundProgress("RECONNECTING"));
    assertFalse(VoiceOverviewPolicy.showForegroundProgress("READY"));
    assertFalse(VoiceOverviewPolicy.showForegroundProgress("LISTENING"));
    assertTrue(VoiceOverviewPolicy.showWorkerProgress("working"));
    assertTrue(VoiceOverviewPolicy.showWorkerProgress("queued"));
    assertFalse(VoiceOverviewPolicy.showWorkerProgress("completed"));
    assertFalse(VoiceOverviewPolicy.showWorkerProgress("input_required"));
  }

  @Test public void ttsFallbackIsShownOnlyForUsableDegradedVoice() {
    assertTrue(VoiceOverviewPolicy.showTtsFallback(true, false, true));
    assertFalse(VoiceOverviewPolicy.showTtsFallback(true, true, true));
    assertFalse(VoiceOverviewPolicy.showTtsFallback(false, false, true));
    assertFalse(VoiceOverviewPolicy.showTtsFallback(true, false, false));
  }
}
