package myapp.app;

import android.Manifest;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleUiTest {
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);

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

  @Test public void draftAndManualRecordingSurviveProcessDeathColdLaunch() throws Exception {
    ActivityScenario<MainActivity> first = ActivityScenario.launch(MainActivity.class);
    first.onActivity(activity -> {
      com.google.android.material.textfield.TextInputEditText editor = activity.findViewById(R.id.voice_draft);
      editor.setText("process-death-draft");
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
    first.close();
    try (ParcelFileDescriptor ignored = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation().executeShellCommand("am kill myapp.app")) {
      // Closing the descriptor only releases the shell command pipe.
    }
    Thread.sleep(300L);
    try (ActivityScenario<MainActivity> second = ActivityScenario.launch(MainActivity.class)) {
      second.onActivity(activity -> {
        com.google.android.material.textfield.TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        VoicePcmPlayerView player = activity.findViewById(R.id.voice_user_player);
        assertEquals("process-death-draft", editor.getText().toString());
        assertEquals(View.VISIBLE, player.getVisibility());
        assertTrue(player.hasAudio());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.voice_transcribe).getVisibility());
        assertTrue(activity.getFileStreamPath("voice_unsent_manual.pcm").isFile());
      });
      second.onActivity(activity -> {
        activity.deleteFile("voice_unsent_manual.pcm");
        activity.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
            .remove("current_message_v1")
            .putBoolean("transcribe_automatically_v1", true)
            .putBoolean("send_automatically_v3", true)
            .commit();
      });
    }
  }

  @Test public void manualRecordingContinuesAcrossBackgroundAndForeground() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        activity.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
            .putBoolean("transcribe_automatically_v1", false)
            .putBoolean("send_automatically_v3", false)
            .commit();
        com.google.android.material.button.MaterialButton mic = activity.findViewById(R.id.voice_start_stop);
        assertTrue(mic.performClick());
      });
      Thread.sleep(400L);
      scenario.onActivity(activity -> assertEquals(
          activity.getString(R.string.stop_recording),
          ((com.google.android.material.button.MaterialButton) activity.findViewById(R.id.voice_start_stop)).getText().toString()));
      scenario.moveToState(Lifecycle.State.CREATED);
      Thread.sleep(300L);
      scenario.moveToState(Lifecycle.State.RESUMED);
      scenario.onActivity(activity -> {
        com.google.android.material.button.MaterialButton mic = activity.findViewById(R.id.voice_start_stop);
        assertEquals(activity.getString(R.string.stop_recording), mic.getText().toString());
        assertTrue(mic.performClick());
        activity.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
            .putBoolean("transcribe_automatically_v1", true)
            .putBoolean("send_automatically_v3", true)
            .commit();
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
