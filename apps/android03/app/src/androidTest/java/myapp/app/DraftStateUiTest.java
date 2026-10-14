package myapp.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.textfield.TextInputEditText;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DraftStateUiTest {
  @Before public void reset() { VoiceUiTestState.reset(); }

  @Test public void recreationDoesNotClaimOrPersistProvisionalRecognition() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        invoke(screen, "showSpeechPartial", new Class<?>[]{String.class}, "unfinished recognition");
        editor(screen).setSelection(3, 10);
        assertFalse((Boolean) get(screen, "userClaimedAsrPartial"));
      });
      scenario.recreate();
      scenario.onActivity(screen -> {
        assertEquals("unfinished recognition", editor(screen).getText().toString());
        assertEquals(3, editor(screen).getSelectionStart());
        assertEquals(10, editor(screen).getSelectionEnd());
        assertFalse("Restoration was mistaken for a human edit", (Boolean) get(screen, "userClaimedAsrPartial"));
        assertTrue("Provisional provenance was lost", (Boolean) get(screen, "draftContainsUnstableAsrPartial"));
        assertFalse(screen.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE)
            .contains("user_entered_message_v2"));
        invoke(screen, "showSpeechFinal", new Class<?>[]{String.class, String.class}, "Final verified recognition.", "asr-final");
        assertEquals("Final verified recognition.", editor(screen).getText().toString());
      });
    }
  }

  @Test public void finalManualDraftKeepsItsTurnAndUserCorrectionAcrossRecreation() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        set(screen, "pendingTurnId", "manual-final-turn");
        set(screen, "lastFinalAsrTurnId", "manual-final-turn");
        set(screen, "lastFinalAsrText", "Original recognition");
        set(screen, "manualTranscriptPendingSubmission", true);
        invoke(screen, "showSpeechFinal", new Class<?>[]{String.class, String.class}, "Original recognition", "manual-final-turn");
        editor(screen).setText("  Corrected /data/Exact Path  ");
        editor(screen).setSelection(4, 12);
      });
      scenario.recreate();
      scenario.onActivity(screen -> {
        assertEquals("manual-final-turn", get(screen, "pendingTurnId"));
        assertEquals("manual-final-turn", get(screen, "lastFinalAsrTurnId"));
        assertTrue((Boolean) get(screen, "manualTranscriptPendingSubmission"));
        assertTrue((Boolean) get(screen, "userClaimedAsrPartial"));
        assertEquals("  Corrected /data/Exact Path  ", editor(screen).getText().toString());
        assertEquals(4, editor(screen).getSelectionStart());
        assertEquals(12, editor(screen).getSelectionEnd());
        invoke(screen, "showSpeechFinal", new Class<?>[]{String.class, String.class}, "Late recognition", "manual-final-turn");
        assertEquals("  Corrected /data/Exact Path  ", editor(screen).getText().toString());
      });
    }
  }

  @Test public void exactHistorySurvivesServerReplacementAndRecreation() throws Exception {
    String user = "  first line\n\tindented line  \n";
    String reply = "\n  exact reply  \n";
    JSONArray turns = new JSONArray().put(new JSONObject().put("turn_id", "exact-history")
        .put("user", user).put("assistant", reply).put("status", "completed"));
    String expected = "You\n" + user + "\n\nAgent\n" + reply + "\n\n";
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        invoke(screen, "replaceConversationHistory", new Class<?>[]{JSONArray.class}, turns);
        assertEquals(expected, ((TextView) screen.findViewById(R.id.voice_conversation)).getText().toString());
      });
      scenario.recreate();
      scenario.onActivity(screen -> assertEquals(expected,
          ((TextView) screen.findViewById(R.id.voice_conversation)).getText().toString()));
    }
  }

  @Test public void unrelatedAcknowledgementCannotEraseManualRecordingOrPendingTranscription() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        byte[] pcm = new byte[32000];
        ((java.io.ByteArrayOutputStream) get(screen, "manualRecordingBuffer")).write(pcm, 0, pcm.length);
        set(screen, "manualRecordingAvailable", true);
        set(screen, "manualTranscriptPendingSubmission", true);
        set(screen, "manualTranscribePending", true);
        set(screen, "lastFinalAsrTurnId", "current-recording");
        set(screen, "pendingTurnId", "current-recording");
        VoicePcmPlayerView player = screen.findViewById(R.id.voice_user_player);
        player.setStaticPcm(pcm);
        try {
          JSONObject ack = new JSONObject().put("type", "turn").put("event", "submitted")
              .put("turn_id", "older-message").put("text", "Previously submitted text");
          invoke(screen, "handleWsJson", new Class<?>[]{String.class, long.class}, ack.toString(), 0L);
        } catch (Exception failure) { throw new AssertionError(failure); }
        assertTrue("An unrelated ACK erased unsent audio", (Boolean) get(screen, "manualRecordingAvailable"));
        assertTrue("An unrelated ACK cancelled transcription state", (Boolean) get(screen, "manualTranscribePending"));
        assertTrue((Boolean) get(screen, "manualTranscriptPendingSubmission"));
        assertEquals("current-recording", get(screen, "pendingTurnId"));
        assertEquals(32000, ((java.io.ByteArrayOutputStream) get(screen, "manualRecordingBuffer")).size());
        assertTrue(player.hasAudio());
      });
    }
  }

  private static TextInputEditText editor(MainActivity screen) { return screen.findViewById(R.id.voice_draft); }
  private static Object get(Object target, String name) {
    try { java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static void set(Object target, String name, Object value) {
    try { java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
    try { java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
}
