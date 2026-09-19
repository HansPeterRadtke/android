package myapp.app;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static org.hamcrest.Matchers.allOf;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleUiTest {
  @Test public void unsentDraftSurvivesActivityRecreation() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      onView(withId(R.id.voice_draft)).perform(replaceText("draftpersists"));
      closeSoftKeyboard();
      scenario.recreate();
      onView(withId(R.id.voice_draft)).check(matches(withText("draftpersists")));
      onView(withId(R.id.voice_send)).check(matches(isEnabled()));
    }
  }
  @Test public void unsentManualRecordingSurvivesActivityRecreation() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        activity.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
            .putBoolean("transcribe_automatically_v1", false)
            .putBoolean("send_automatically_v3", false)
            .commit();
        try {
          byte[] pcm = new byte[32000];
          java.util.Arrays.fill(pcm, (byte) 1);
          try (java.io.FileOutputStream out = activity.openFileOutput("voice_unsent_manual.pcm", Context.MODE_PRIVATE)) {
            out.write(pcm);
            out.getFD().sync();
          }
        } catch (java.io.IOException failure) {
          throw new AssertionError(failure);
        }
      });
      scenario.recreate();
      try {
        onView(withId(R.id.voice_user_player)).check(matches(withEffectiveVisibility(VISIBLE)));
        onView(allOf(withId(R.id.voice_player_play_pause), isDescendantOfA(withId(R.id.voice_user_player)))).perform(scrollTo()).check(matches(isDisplayed()));
        onView(allOf(withId(R.id.voice_player_stop), isDescendantOfA(withId(R.id.voice_user_player)))).perform(scrollTo()).check(matches(isDisplayed()));
        onView(allOf(withId(R.id.voice_player_seek), isDescendantOfA(withId(R.id.voice_user_player)))).perform(scrollTo()).check(matches(isDisplayed()));
        final boolean[] wide = {false};
        scenario.onActivity(activity -> {
          android.content.res.Configuration config = activity.getResources().getConfiguration();
          wide[0] = config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp;
        });
        if (wide[0]) onView(withId(R.id.voice_transcribe)).perform(scrollTo()).check(matches(isDisplayed()));
        else onView(withId(R.id.voice_transcribe)).check(matches(isDisplayed()));
      } finally {
        scenario.onActivity(activity -> {
          activity.deleteFile("voice_unsent_manual.pcm");
          activity.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
              .putBoolean("transcribe_automatically_v1", true)
              .putBoolean("send_automatically_v3", true)
              .commit();
        });
      }
    }
  }

}
