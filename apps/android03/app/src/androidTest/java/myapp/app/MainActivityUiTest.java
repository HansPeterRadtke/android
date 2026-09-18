package myapp.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isClickable;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

import android.Manifest;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainActivityUiTest {
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);

  @Rule public ActivityScenarioRule<MainActivity> activity =
      new ActivityScenarioRule<>(MainActivity.class);

  @Test public void defaultScreenIsConversationFirstAndKeepsSecondaryControlsOut() {
    onView(withId(R.id.voice_status)).check(matches(isDisplayed()));
    onView(withId(R.id.voice_conversation)).check(matches(isDisplayed()));
    onView(withId(R.id.voice_draft)).check(matches(isDisplayed()));
    onView(withId(R.id.voice_start_stop)).check(matches(isDisplayed())).check(matches(isClickable()));
    onView(withId(R.id.voice_send)).check(matches(isDisplayed()));
    onView(withId(R.id.voice_settings)).check(matches(isDisplayed())).check(matches(isClickable()));
    onView(withId(R.id.voice_diagnostics)).check(doesNotExist());
    onView(withId(R.id.voice_replay_row)).check(doesNotExist());
    onView(withId(R.id.voice_worker_status)).check(matches(withEffectiveVisibility(GONE)));
    onView(withId(R.id.voice_user_player)).check(matches(withEffectiveVisibility(GONE)));
    onView(withId(R.id.voice_assistant_player)).check(matches(withEffectiveVisibility(GONE)));
    onView(withId(R.id.voice_transcribe)).check(matches(withEffectiveVisibility(GONE)));
  }

  @Test public void settingsExposeVersionAutomationVocabularyAndDiagnostics() {
    onView(withId(R.id.voice_settings)).perform(click());
    onView(withText(R.string.settings_title)).check(matches(isDisplayed()));
    onView(withId(R.id.voice_version)).check(matches(isDisplayed()));
    onView(withText(containsString("Voice Agent 1.4.0"))).check(matches(isDisplayed()));
    onView(withId(R.id.voice_auto_transcribe)).perform(scrollTo()).check(matches(isDisplayed()));
    onView(withId(R.id.voice_auto_send)).perform(scrollTo()).check(matches(isDisplayed()));
    onView(withId(R.id.voice_vocabulary)).perform(scrollTo()).check(matches(isDisplayed()));
    onView(withId(R.id.voice_save_vocabulary)).perform(scrollTo()).check(matches(isDisplayed()));
    onView(withId(R.id.voice_diagnostics)).perform(scrollTo()).check(matches(isDisplayed())).perform(click());
    onView(withText(R.string.diagnostics_title)).check(matches(isDisplayed()));
    onView(withText(R.string.check_connection)).check(matches(isDisplayed()));
    pressBack();
  }
}
