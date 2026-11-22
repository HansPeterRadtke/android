package myapp.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real Android Activity acceptance: no bundled bearer => no pending sends.
 * The host MUST block test-device networking. Never run on a real phone or a
 * shared emulator. The opt-in argument makes this skip during generic CI.
 */
@RunWith(AndroidJUnit4.class)
public class NoCredentialSubmitUiTest {
  private static final String PREF = "voice_agent_history";
  private static final String DRAFT = "current_message_v1";
  private static final String REQUEST_ID = "pending_typed_request_id_v1";
  private static final String REQUEST_TEXT = "pending_typed_request_text_v1";
  private static final String ORIGINAL_ID = "1234567890abcdef1234567890abcdef";

  private static SharedPreferences requirePrivateNoCredentialAcceptance() {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("Explicit isolated offline no-credential acceptance only",
        "1".equals(args.getString("offlineNoCredentialAcceptance")));
    Assume.assumeFalse("Do not risk a real authenticated WebSocket or user command",
        VoiceWebSocketAuth.configured(BuildConfig.VOICE_AGENT_AUTH_TOKEN));
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
  }

  private static void assertStillEditableAndUnsent(MainActivity activity, String expected) {
    TextInputEditText editor = activity.findViewById(R.id.voice_draft);
    MaterialButton send = activity.findViewById(R.id.voice_send);
    TextView status = activity.findViewById(R.id.voice_status);
    TextView detail = activity.findViewById(R.id.voice_status_detail);
    assertEquals(expected, editor.getText().toString());
    assertTrue("No-credential Send must not leave the composer locked", send.isEnabled());
    assertEquals(activity.getString(R.string.status_unavailable), status.getText().toString());
    assertEquals(activity.getString(R.string.voice_auth_missing), detail.getText().toString());
  }

  @Test public void typedOfflineSendShowsMissingCredentialAndPreservesDraft() {
    SharedPreferences prefs = requirePrivateNoCredentialAcceptance();
    assertTrue(prefs.edit().clear().commit());
    String value = "OfflineNoActionTyped8237";
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        editor.setText(value);
        MaterialButton send = activity.findViewById(R.id.voice_send);
        assertTrue(send.isEnabled());
        assertTrue(send.performClick());
        assertStillEditableAndUnsent(activity, value);
      });
      assertEquals(value, prefs.getString(DRAFT, ""));
      assertEquals("A request ID must not be manufactured before a real send",
          "", prefs.getString(REQUEST_ID, ""));
      scenario.recreate();
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        assertEquals(value, editor.getText().toString());
        assertTrue(activity.<MaterialButton>findViewById(R.id.voice_send).isEnabled());
      });
    } finally {
      prefs.edit().clear().commit();
    }
  }

  @Test public void priorDurableTypedIdIsRetainedButCannotAutoSendWithoutCredential() {
    SharedPreferences prefs = requirePrivateNoCredentialAcceptance();
    assertTrue(prefs.edit().clear().putString(DRAFT,"OfflinePriorTyped8237")
        .putString(REQUEST_ID,ORIGINAL_ID)
        .putString(REQUEST_TEXT,"OfflinePriorTyped8237").commit());
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        assertEquals("OfflinePriorTyped8237",editor.getText().toString());
        MaterialButton send = activity.findViewById(R.id.voice_send);
        assertTrue("Old pending request must not lock a credential-free app",send.isEnabled());
        assertTrue(send.performClick());
        assertStillEditableAndUnsent(activity,"OfflinePriorTyped8237");
      });
      // Do not delete a potentially already-executed durable ID. If the
      // original trusted client returns, it can retry the SAME operation.
      assertEquals(ORIGINAL_ID,prefs.getString(REQUEST_ID,""));
      assertEquals("OfflinePriorTyped8237",prefs.getString(REQUEST_TEXT,""));
    } finally {
      prefs.edit().clear().commit();
    }
  }
}
