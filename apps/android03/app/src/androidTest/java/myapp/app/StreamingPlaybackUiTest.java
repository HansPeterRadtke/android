package myapp.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Rect;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.google.android.material.button.MaterialButton;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class StreamingPlaybackUiTest {
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);

  private static void assertDisplayed(View view) {
    assertEquals(View.VISIBLE, view.getVisibility());
    Rect visible = new Rect();
    assertTrue("view has no global visible rectangle", view.getGlobalVisibleRect(visible));
    assertTrue("visible rectangle is empty", visible.width() > 0 && visible.height() > 0);
  }

  @Test public void liveReplyAutoplaysOnFirstFrameAndStopHidesImmediately() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.startStream(true, true, true);
        byte[] pcm = new byte[16000 * 2 * 2];
        player.append(pcm);
      });
      Thread.sleep(300L);
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        MaterialButton playPause = player.findViewById(R.id.voice_player_play_pause);
        MaterialButton stop = player.findViewById(R.id.voice_player_stop);
        assertDisplayed(player);
        assertDisplayed(playPause);
        assertEquals(activity.getString(R.string.pause), playPause.getText().toString());
        assertDisplayed(stop);
        assertDisplayed(player.findViewById(R.id.voice_player_back));
        assertDisplayed(player.findViewById(R.id.voice_player_forward));
        assertDisplayed(player.findViewById(R.id.voice_player_seek));
        assertDisplayed(player.findViewById(R.id.voice_player_current));
        assertDisplayed(player.findViewById(R.id.voice_player_remaining));
        assertDisplayed(player.findViewById(R.id.voice_player_total));
        assertDisplayed(activity.findViewById(R.id.voice_conversation));
        assertDisplayed(activity.findViewById(R.id.voice_draft));
        assertDisplayed(activity.findViewById(R.id.voice_send));
        assertTrue("Stop click was not accepted", stop.performClick());
        assertEquals(View.GONE, player.getVisibility());
      });
    }
  }

  @Test public void exposedPlayerControlsHaveAccessibleNames() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.startStream(false, false, false);
        player.append(new byte[3200]);
        int[] ids = {
            R.id.voice_player_play_pause, R.id.voice_player_stop, R.id.voice_player_back,
            R.id.voice_player_forward, R.id.voice_player_seek
        };
        for (int id : ids) {
          View view = player.findViewById(id);
          CharSequence description = view.getContentDescription();
          CharSequence text = view instanceof android.widget.TextView
              ? ((android.widget.TextView) view).getText() : null;
          assertTrue(
              "player control has no accessible name: " + id,
              (description != null && description.length() > 0)
                  || (text != null && text.length() > 0));
        }
      });
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
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        assertEquals(View.GONE, player.getVisibility());
      });

      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.startStream(true, false, false);
        player.append(new byte[3200]);
        player.endStream();
      });
      Thread.sleep(500L);
      scenario.onActivity(activity -> assertDisplayed(activity.findViewById(R.id.voice_assistant_player)));
    }
  }
  @Test public void localVoiceActivityHintDoesNotStopAssistantPlayback() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        player.startStream(true, true, true);
        player.append(new byte[16000 * 2]);
        activity.handleLocalVoiceActivityStarted(1, true);
      });
      Thread.sleep(250L);
      scenario.onActivity(activity -> {
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_assistant_player);
        MaterialButton playPause = player.findViewById(R.id.voice_player_play_pause);
        assertDisplayed(player);
        assertEquals(activity.getString(R.string.pause), playPause.getText().toString());
      });
    }
  }

}
