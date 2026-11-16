package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceAudioRecoveryNoticeTest {
  @Test public void historyOnlyShowsARealReplayableAsrFailure() {
    assertTrue(VoiceAudioRecoveryNotice.inHistory("asr_unavailable", true));
    assertFalse(VoiceAudioRecoveryNotice.inHistory("asr_unavailable", false));
    for (String type : new String[] {"draft", "completed", "ready", "answer_error",
                                      "discarded", "", "asr_artifact"}) {
      assertFalse(type, VoiceAudioRecoveryNotice.inHistory(type, true));
    }
  }

  @Test public void savedAudioArtifactRequiresSpecificCodeAndIdentifier() {
    assertTrue(VoiceAudioRecoveryNotice.justSaved(
        "artifact", "recognition_unavailable", true, "untranscribed-123"));
    assertFalse(VoiceAudioRecoveryNotice.justSaved(
        "artifact", "recognition_unavailable", true, ""));
    assertFalse(VoiceAudioRecoveryNotice.justSaved(
        "artifact", "recognition_unavailable", false, "untranscribed-123"));
    assertFalse(VoiceAudioRecoveryNotice.justSaved(
        "artifact", "untrusted_no_speech", true, "artifact-123"));
    assertFalse(VoiceAudioRecoveryNotice.justSaved(
        "final", "recognition_unavailable", true, "turn-123"));
  }

  @Test public void listeningAfterSavedRecordingMustKeepItsWarning() {
    assertTrue(VoiceAudioRecoveryNotice.isSavedRecordingState(
        "listening", "recognition_unavailable_recording_saved"));
    assertFalse(VoiceAudioRecoveryNotice.isSavedRecordingState(
        "listening", "empty_final"));
    assertFalse(VoiceAudioRecoveryNotice.isSavedRecordingState(
        "awaiting_send", "recognition_unavailable_recording_saved"));
    assertFalse(VoiceAudioRecoveryNotice.isSavedRecordingState("listening", ""));
  }

  @Test public void genericSttAndSubmissionErrorsCannotMasqueradeAsSavedRecording() {
    assertTrue(VoiceAudioRecoveryNotice.isSavedRecordingError(
        "stt", "speech_recognition_unavailable_recording_saved"));
    assertFalse(VoiceAudioRecoveryNotice.isSavedRecordingError(
        "stt", "recognition_capacity_exhausted"));
    assertFalse(VoiceAudioRecoveryNotice.isSavedRecordingError(
        "submit", "speech_recognition_unavailable_recording_saved"));
    assertFalse(VoiceAudioRecoveryNotice.isSavedRecordingError("stt", ""));
  }
}
