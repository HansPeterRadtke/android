package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceSavedAudioRetryPolicyTest {
  private static final String ID = "untranscribed-1234567890abcdef";

  @Test public void onlyValidOriginalServerIdsCanBeRetranscribed() {
    assertTrue(VoiceSavedAudioRetryPolicy.canRequest(ID,true,true,false));
    for (String bad : new String[] {"", "turn-1234567890abcdef",
        "../private.wav", "untranscribed-123", "untranscribed-1234567890abcdeg",
        "untranscribed-1234567890abcdef-extra", "untranscribed-1234567890ABCDEF"}) {
      assertFalse(bad,VoiceSavedAudioRetryPolicy.canRequest(bad,true,true,false));
    }
    assertFalse(VoiceSavedAudioRetryPolicy.canRequest(null,true,true,false));
  }

  @Test public void legacyServerOfflineOrActiveRecordingForbidsRequest() {
    assertFalse(VoiceSavedAudioRetryPolicy.canRequest(ID,false,true,false));
    assertFalse(VoiceSavedAudioRetryPolicy.canRequest(ID,true,false,false));
    assertFalse(VoiceSavedAudioRetryPolicy.canRequest(ID,true,true,true));
  }

  @Test public void onlyNewProtocolReasonsCountAsRetryStates() {
    assertTrue(VoiceSavedAudioRetryPolicy.isInProgress("saved_recording_retry"));
    assertTrue(VoiceSavedAudioRetryPolicy.isInProgress("saved_recording_retry_pending"));
    assertFalse(VoiceSavedAudioRetryPolicy.isInProgress("turn_incomplete"));
    assertTrue(VoiceSavedAudioRetryPolicy.isReview("saved_recording_retry_review"));
    assertFalse(VoiceSavedAudioRetryPolicy.isReview("turn_classifier_unavailable"));
  }

  @Test public void modelFailureShowsNonDestructiveRetryError() {
    assertTrue(VoiceSavedAudioRetryPolicy.isRetryError("stt","retry_recognizer_unavailable"));
    assertTrue(VoiceSavedAudioRetryPolicy.isRetryError("stt","retry_no_trustworthy_speech"));
    assertTrue(VoiceSavedAudioRetryPolicy.isRetryError("stt","recording_not_retriable"));
    assertFalse(VoiceSavedAudioRetryPolicy.isRetryError("submit","recording_not_retriable"));
    assertFalse(VoiceSavedAudioRetryPolicy.isRetryError("stt","speech_recognition_unavailable_recording_saved"));
  }
}
