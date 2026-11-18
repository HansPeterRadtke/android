package myapp.app;

import static org.junit.Assert.*;

import android.view.View;
import android.content.Context;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * No-action, test-only-package Android emulator UI acceptance.
 *
 * The GUIDELINE_TEST build hard-pins network endpoints to localhost port 9.
 * There is no real SWAAG endpoint, user account, voice capture or assistant
 * playback. We deliver the precise documented server protocol events to
 * MainActivity and inspect its actual composer/review UI.
 */
@RunWith(AndroidJUnit4.class)
public class VoiceReviewReconnectEmulatorTest {
  @Before public void reset() {
    assertTrue("Never run this instrumentation against the normal production package",
        BuildConfig.GUIDELINE_TEST);
    VoiceUiTestState.reset();
  }

  @Test public void latestUnsentDraftInAutoModeStaysVisibleWithManualSendAfterRecreation() throws Exception {
    JSONObject older = row("earlier-complete", "completed", "Older submitted command.", "");
    JSONObject latest = row("latest-voice-draft", "draft", "", "Use folder B, not folder A.");
    JSONArray history = new JSONArray().put(older).put(latest);
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        enableAutomaticPreferenceForLaterTurns(screen);
        invoke(screen, "replaceConversationHistory", new Class<?>[]{JSONArray.class}, history);
        assertLatestDraftReview(screen, "latest-voice-draft", "Use folder B, not folder A.");
        String display=((TextView) screen.findViewById(R.id.voice_conversation)).getText().toString();
        assertTrue(display.contains("Older submitted command."));
        assertFalse("Unsubmitted recognized draft became conversation history", display.contains("Use folder B"));
      });
      scenario.recreate();
      scenario.onActivity(screen -> {
        assertTrue("Automatic transcription preference was lost", (Boolean)get(screen,"autoTranscribe"));
        assertTrue("Automatic send preference was lost", (Boolean)get(screen,"autoSend"));
        assertLatestDraftReview(screen, "latest-voice-draft", "Use folder B, not folder A.");
        assertFalse("Review after a reconnect must not be marked sent",
            ((TextView) screen.findViewById(R.id.voice_conversation)).getText()
                .toString().contains("Use folder B"));
      });
    }
  }

  @Test public void olderDraftIsNotRevivedAfterMoreRecentCompletedTurn() throws Exception {
    JSONArray history = new JSONArray()
        .put(row("superseded", "draft", "", "Destroy folder A."))
        .put(row("latest-complete", "completed", "Use folder B.", ""));
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        enableAutomaticPreferenceForLaterTurns(screen);
        invoke(screen, "replaceConversationHistory", new Class<?>[]{JSONArray.class}, history);
        assertEquals("", get(screen, "pendingTurnId"));
        assertFalse(((TextInputEditText) screen.findViewById(R.id.voice_draft))
            .getText().toString().contains("Destroy folder A"));
        String display=((TextView) screen.findViewById(R.id.voice_conversation)).getText().toString();
        assertTrue(display.contains("Use folder B."));
        assertFalse(display.contains("Destroy folder A."));
      });
    }
  }

  @Test public void emptyLatestDraftCannotCreateEnabledSendAction() throws Exception {
    JSONArray history=new JSONArray().put(row("zero-words", "draft", "", " \t "));
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        enableAutomaticPreferenceForLaterTurns(screen);
        invoke(screen, "replaceConversationHistory", new Class<?>[]{JSONArray.class}, history);
        assertEquals("", get(screen, "pendingTurnId"));
        assertFalse(((MaterialButton)screen.findViewById(R.id.voice_send)).isEnabled());
      });
    }
  }

  @Test public void serverFinalReviewAndHistoryOrderingKeepsExactlyOneReviewableDraft() throws Exception {
    String turn="uncertain-voice-review";
    String words="Use folder B after thinking about the earlier folder.";
    JSONArray history=new JSONArray().put(row(turn, "draft", "", words));
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        enableAutomaticPreferenceForLaterTurns(screen);
        try {
          JSONObject finalEvent=new JSONObject().put("type","asr").put("event","final")
              .put("turn_id",turn).put("text",words).put("raw_text",words)
              .put("auto_send",false).put("restored",true).put("has_user_audio",true);
          JSONObject reviewEvent=new JSONObject().put("type","state")
              .put("state","awaiting_send").put("reason","restored_draft")
              .put("turn_id",turn);
          invoke(screen, "handleWsJson",new Class<?>[]{String.class,long.class},
              finalEvent.toString(),0L);
          invoke(screen, "handleWsJson",new Class<?>[]{String.class,long.class},
              reviewEvent.toString(),0L);
          // A late history snapshot must not clear the review draft even if
          // the owner normally uses automatic sending for different turns.
          invoke(screen, "replaceConversationHistory",new Class<?>[]{JSONArray.class},history);
          assertLatestDraftReview(screen,turn,words);
          assertFalse(((TextView) screen.findViewById(R.id.voice_conversation)).getText()
              .toString().contains(words));
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
    }
  }

  @Test public void savedRecordingOutageShowsReplayAndNoInventedSend() throws Exception {
    String original="untranscribed-1234567890abcdef";
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          enableAutomaticPreferenceForLaterTurns(screen);
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","asr").put("event","artifact")
                  .put("reason","recognition_unavailable").put("turn_id",original)
                  .put("has_user_audio",true).toString(),0L);
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","error").put("stage","stt")
                  .put("message","speech_recognition_unavailable_recording_saved")
                  .put("turn_id",original).toString(),0L);
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","state").put("state","listening")
                  .put("reason","recognition_unavailable_recording_saved").toString(),0L);
          assertEquals(original,get(screen,"savedRetryArtifactTurnId"));
          assertEquals(original,get(screen,"latestUserTurnId"));
          assertTrue((Boolean)get(screen,"replayUserAvailable"));
          assertEquals("",get(screen,"pendingTurnId"));
          assertFalse("Failed ASR must not enable Send",
              screen.findViewById(R.id.voice_send).isEnabled());
          TextView feedback=screen.findViewById(R.id.voice_status_detail);
          assertEquals(View.VISIBLE,feedback.getVisibility());
          assertTrue(feedback.getText().toString().contains("Recording saved"));
          assertTrue(feedback.getText().toString().contains("Nothing was sent"));
          assertFalse((Boolean)get(screen,"serverCanRetrySavedAudio"));
        } catch(Exception error) {throw new AssertionError(error);}
      });
    }
  }

  @Test public void restoredAudioOnlyHistoryDoesNotInventWordsAndPreservesReplay() throws Exception {
    String failed="untranscribed-1234567890abcdef";
    JSONArray history=new JSONArray().put(row("older","completed","Earlier completed message.",""))
        .put(new JSONObject().put("turn_id",failed).put("user","")
            .put("recognized_text","").put("assistant","")
            .put("status","asr_unavailable").put("has_user_audio",true));
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        enableAutomaticPreferenceForLaterTurns(screen);
        invoke(screen,"replaceConversationHistory",new Class<?>[]{JSONArray.class},history);
        assertEquals(failed,get(screen,"savedRetryArtifactTurnId"));
        assertEquals("",get(screen,"pendingTurnId"));
        assertTrue((Boolean)get(screen,"replayUserAvailable"));
        String transcript=((TextView)screen.findViewById(R.id.voice_conversation))
            .getText().toString();
        assertTrue(transcript.contains("Earlier completed message."));
        assertTrue(transcript.contains("Recording saved"));
        assertFalse(transcript.contains("You\\n\\n"));
        assertFalse(screen.findViewById(R.id.voice_send).isEnabled());
        // The legacy production server does not advertise saved-audio retry.
        // Never click a retry or Send button in this emulator test.
        assertFalse((Boolean)get(screen,"serverCanRetrySavedAudio"));
      });
    }
  }

  @Test public void newRecognizedTurnClearsSupersededFailedAudioRetryTarget() throws Exception {
    String old="untranscribed-1234567890abcdef";
    String newer="newer-recognized-speech";
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","asr").put("event","artifact")
                  .put("reason","recognition_unavailable").put("turn_id",old)
                  .put("has_user_audio",true).toString(),0L);
          assertEquals(old,get(screen,"savedRetryArtifactTurnId"));
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","asr").put("event","final")
                  .put("turn_id",newer).put("text","Use the newer folder B.")
                  .put("raw_text","Use the newer folder B.")
                  .put("auto_send",false).put("has_user_audio",true).toString(),0L);
          assertEquals(newer,get(screen,"pendingTurnId"));
          assertEquals(newer,get(screen,"latestUserTurnId"));
          assertEquals("The old recording must not remain the current retry target",
              "",get(screen,"savedRetryArtifactTurnId"));
          assertTrue(screen.findViewById(R.id.voice_send).isEnabled());
        } catch(Exception failure) {throw new AssertionError(failure);}
      });
    }
  }

  @Test public void serverWithoutVerifiedSpeakerKeepsExplicitReviewInAutoMode() throws Exception {
    try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          enableAutomaticPreferenceForLaterTurns(screen);
          String id="unverified-foreground-new-turn";
          String words="Use folder B rather than the old folder A.";
          JSONObject hello=new JSONObject().put("type","hello")
              .put("connection_generation",0).put("version","no-action-test-server")
              .put("commit_protocol",1)
              .put("automatic_voice_requires_manual_review",true)
              .put("foreground_speaker_authority","unverified");
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              hello.toString(),0L);
          assertTrue((Boolean)get(screen,"serverRequiresSpeakerManualReview"));
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","asr").put("event","final")
                  .put("turn_id",id).put("text",words).put("raw_text",words)
                  .put("auto_send",true).put("has_user_audio",true).toString(),0L);
          invoke(screen,"handleWsJson",new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","state").put("state","awaiting_send")
                  .put("reason","speaker_identity_unverified")
                  .put("turn_id",id).toString(),0L);
          assertLatestDraftReview(screen,id,words);
          TextView notice=screen.findViewById(R.id.voice_status_detail);
          assertTrue(notice.getText().toString().contains("Speaker identity is not verified"));
          assertTrue(notice.getText().toString().contains("No automatic command was sent"));
          assertFalse(((TextView) screen.findViewById(R.id.voice_conversation))
              .getText().toString().contains(words));
          // This is a test-only isolated screen. Never tap Send.
        } catch (Exception failure) {throw new AssertionError(failure);}
      });
    }
  }

  private static void assertLatestDraftReview(MainActivity activity,String id,String words) {
    assertEquals(id,get(activity,"pendingTurnId"));
    assertEquals(words,((TextInputEditText)activity.findViewById(R.id.voice_draft))
        .getText().toString());
    assertEquals(View.VISIBLE,activity.findViewById(R.id.voice_send).getVisibility());
    assertTrue("Explicit Send must remain available for reviewed draft",
        activity.findViewById(R.id.voice_send).isEnabled());
    // Do not click Send: even an isolated test should not fake execution.
  }

  private static JSONObject row(String turn,String status,String user,String recognized)
      throws Exception {
    return new JSONObject().put("turn_id",turn).put("status",status)
        .put("user",user).put("recognized_text",recognized)
        .put("has_user_audio",true).put("assistant","");
  }

  private static void enableAutomaticPreferenceForLaterTurns(MainActivity activity) {
    assertTrue(activity.getSharedPreferences("voice_agent_history",Context.MODE_PRIVATE).edit()
        .putBoolean("transcribe_automatically_v1",true)
        .putBoolean("send_automatically_v3",true).commit());
    set(activity,"autoTranscribe",true);
    set(activity,"autoSend",true);
  }

  private static Object get(Object target,String name) {
    try {
      java.lang.reflect.Field f=target.getClass().getDeclaredField(name);
      f.setAccessible(true);return f.get(target);
    } catch(Exception e) {throw new AssertionError(e);}
  }

  private static void set(Object target,String name,Object value) {
    try {
      java.lang.reflect.Field f=target.getClass().getDeclaredField(name);
      f.setAccessible(true);f.set(target,value);
    } catch(Exception e) {throw new AssertionError(e);}
  }

  private static void invoke(Object target,String name,Class<?>[] types,Object... args) {
    try {
      java.lang.reflect.Method m=target.getClass().getDeclaredMethod(name,types);
      m.setAccessible(true);m.invoke(target,args);
    } catch(Exception e) {throw new AssertionError(e);}
  }
}
