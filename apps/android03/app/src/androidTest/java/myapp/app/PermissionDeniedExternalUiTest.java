package myapp.app;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class PermissionDeniedExternalUiTest {
  @Test public void typedRoundTripWorksWhenMicrophoneWasDeniedBeforeInstrumentationStarts() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      onView(withId(R.id.voice_status)).check(matches(withText(R.string.status_permission)));
      onView(withId(R.id.voice_draft)).perform(replaceText("sayseven"));
      closeSoftKeyboard();
      onView(withId(R.id.voice_send)).check(matches(isDisplayed())).check(matches(isEnabled())).perform(click());
      String seen = "";
      long deadline = System.currentTimeMillis() + 20000L;
      while (System.currentTimeMillis() < deadline) {
        AtomicReference<String> current = new AtomicReference<>("");
        scenario.onActivity(activity -> {
          TextView view = activity.findViewById(R.id.voice_conversation);
          current.set(view == null ? "" : String.valueOf(view.getText()));
        });
        seen = current.get();
        String low = seen.toLowerCase();
        if (low.contains("sayseven") && low.contains("seven")) break;
        Thread.sleep(250L);
      }
      if (!seen.toLowerCase().contains("sayseven") || !seen.toLowerCase().contains("seven")) {
        throw new AssertionError("typed round trip did not complete: " + seen);
      }
      onView(withId(R.id.voice_component_health))
          .check(matches(withText(containsString("Microphone permission required"))));
    }
  }
}
