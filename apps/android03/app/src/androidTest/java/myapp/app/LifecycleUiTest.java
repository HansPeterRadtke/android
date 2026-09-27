package myapp.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleUiTest {
  @Test public void unsentDraftSurvivesActivityRecreation() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        com.google.android.material.textfield.TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        editor.setText("draftpersists");
        editor.setSelection(editor.length());
      });
      scenario.recreate();
      scenario.onActivity(activity -> {
        com.google.android.material.textfield.TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        com.google.android.material.button.MaterialButton send = activity.findViewById(R.id.voice_send);
        assertEquals("draftpersists", editor.getText().toString());
        assertTrue(send.isEnabled());
      });
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
        scenario.onActivity(activity -> {
          VoicePcmPlayerView player = activity.findViewById(R.id.voice_user_player);
          assertNotNull(player);
          assertEquals(View.VISIBLE, player.getVisibility());
          assertNotNull(player.findViewById(R.id.voice_player_play_pause));
          assertNotNull(player.findViewById(R.id.voice_player_stop));
          assertNotNull(player.findViewById(R.id.voice_player_seek));
          assertTrue(activity.getFileStreamPath("voice_unsent_manual.pcm").isFile());
          assertEquals(View.VISIBLE, activity.findViewById(R.id.voice_transcribe).getVisibility());
        });
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
