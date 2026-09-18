package myapp.app;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

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
}
