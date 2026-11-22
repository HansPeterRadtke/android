package myapp.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real rendered Android UI; private offline device and fake key ONLY. */
@RunWith(AndroidJUnit4.class)
public class VoiceCredentialSettingsUiTest {
  private static final String FAKE = "test-only-ui-local-key-not-real-20261122";

  @Test public void setupAndRemoveDeviceKeyWithoutLeakingOrSendingAnyMessage() {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("Explicit isolated offline Android acceptance only",
        "1".equals(args.getString("offlineCredentialVaultAcceptance")));
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    assertTrue(VoiceCredentialVault.clear(ctx));
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      onView(withId(R.id.voice_settings)).perform(click());
      onView(withId(R.id.voice_private_key_setup)).perform(click());
      onView(withId(R.id.voice_private_key_entry)).perform(replaceText(FAKE),closeSoftKeyboard());
      onView(withText(R.string.voice_private_connection_add)).perform(click());
      assertEquals(FAKE,VoiceCredentialVault.read(ctx));
      assertFalse(ctx.getSharedPreferences("private_voice_connection_v1",Context.MODE_PRIVATE)
          .getString("protected_bearer_ciphertext_v1","").contains(FAKE));
      onView(withId(R.id.voice_settings)).perform(click());
      onView(withId(R.id.voice_private_key_remove)).perform(click());
      onView(withText(R.string.voice_private_connection_remove)).perform(click());
      assertEquals("",VoiceCredentialVault.read(ctx));
      scenario.onActivity(activity -> assertTrue(
          activity.<com.google.android.material.button.MaterialButton>findViewById(R.id.voice_settings)
              .isEnabled()));
    } finally {
      assertTrue(VoiceCredentialVault.clear(ctx));
    }
  }
}
