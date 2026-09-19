package myapp.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;

import android.Manifest;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StreamingPlaybackUiTest {
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);

  @Test public void liveReplyAutoplaysOnFirstFrameAndStopHidesImmediately() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.startStream(true, true, true);
        byte[] pcm = new byte[16000 * 2 * 2];
        player.append(pcm);
      });
      Thread.sleep(300L);
      onView(allOf(
          withId(R.id.voice_player_play_pause),
          isDescendantOfA(withId(R.id.voice_assistant_player))))
          .check(matches(isDisplayed()))
          .check(matches(withText(R.string.pause)));
      onView(withId(R.id.voice_conversation)).check(matches(isDisplayed()));
      onView(withId(R.id.voice_draft)).check(matches(isDisplayed()));
      onView(withId(R.id.voice_send)).check(matches(isDisplayed()));
      onView(allOf(
          withId(R.id.voice_player_stop),
          isDescendantOfA(withId(R.id.voice_assistant_player))))
          .check(matches(isDisplayed()))
          .perform(click());
      onView(withId(R.id.voice_assistant_player)).check(matches(withEffectiveVisibility(GONE)));
    }
  }

  @Test public void completedLiveReplyCollapsesButReplayPlayerRemains() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.setRemoteStopListener(null);
        player.startStream(true, false, true);
        player.append(new byte[3200]);
        player.endStream();
      });
      Thread.sleep(500L);
      onView(withId(R.id.voice_assistant_player)).check(matches(withEffectiveVisibility(GONE)));

      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.startStream(true, false, false);
        player.append(new byte[3200]);
        player.endStream();
      });
      Thread.sleep(500L);
      onView(withId(R.id.voice_assistant_player)).check(matches(isDisplayed()));
    }
  }
}
