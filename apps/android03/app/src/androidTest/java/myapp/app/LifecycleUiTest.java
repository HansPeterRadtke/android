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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleUiTest {
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);

  @Before public void resetState() { VoiceUiTestState.reset(); }

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
        .getUiAutomation().executeShellCommand("am kill " + androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName())) {
      // Closing the descriptor only releases the shell command pipe.
    }
    Thread.sleep(300L);
    try (ActivityScenario<MainActivity> second = ActivityScenario.launch(MainActivity.class)) {
      awaitRecording(second, 32000);
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
    Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    target.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
        .putBoolean("transcribe_automatically_v1", false)
        .putBoolean("send_automatically_v3", false)
        .commit();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
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
      });
    } finally {
      target.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
          .putBoolean("transcribe_automatically_v1", true)
          .putBoolean("send_automatically_v3", true)
          .commit();
    }
  }

  @Test public void unsentManualRecordingSurvivesActivityRecreation() throws Exception {
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
        awaitRecording(scenario, 32000);
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

  private static void awaitRecording(ActivityScenario<MainActivity> scenario, int expectedBytes)
      throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 20000L;
    java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      scenario.onActivity(activity -> {
        try {
          java.lang.reflect.Field field = MainActivity.class.getDeclaredField("manualRecordingBuffer");
          field.setAccessible(true);
          ready.set(((java.io.ByteArrayOutputStream) field.get(activity)).size() == expectedBytes);
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      if (ready.get()) return;
      Thread.sleep(50L);
    }
    throw new AssertionError("Saved recording did not finish recovering");
  }

  @Test public void slowSavedRecordingCannotBlockUiOrReplaceNewerAudio() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    java.io.File older = new java.io.File(context.getCacheDir(), "recovery-older.pcm");
    java.io.File newer = new java.io.File(context.getCacheDir(), "recovery-newer.pcm");
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(older)) { out.write(new byte[32000]); }
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(newer)) { out.write(new byte[64000]); }
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Thread> reader = new java.util.concurrent.atomic.AtomicReference<>();
    java.io.File delayed = new java.io.File(older.getAbsolutePath()) {
      @Override public long length() {
        reader.set(Thread.currentThread());
        entered.countDown();
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
          throw new AssertionError("Recording recovery performed file I/O on the UI thread");
        try {
          if (!release.await(120, java.util.concurrent.TimeUnit.SECONDS))
            throw new AssertionError("Test did not release recording reader");
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
        return super.length();
      }
    };
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> activity.restoreManualRecording(delayed));
      assertTrue(entered.await(20, java.util.concurrent.TimeUnit.SECONDS));
      // This main-thread round trip must succeed while the old read is blocked.
      scenario.onActivity(activity -> {
        ((com.google.android.material.textfield.TextInputEditText) activity.findViewById(R.id.voice_draft))
            .setText("editing continues during recovery");
        activity.restoreManualRecording(newer);
      });
      awaitRecording(scenario, 64000);
      release.countDown();
      reader.get().join(20000L);
      assertTrue("Old read did not finish", !reader.get().isAlive());
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      awaitRecording(scenario, 64000);
      scenario.onActivity(activity -> assertEquals("editing continues during recovery",
          ((com.google.android.material.textfield.TextInputEditText) activity.findViewById(R.id.voice_draft))
              .getText().toString()));
    } finally {
      release.countDown();
      older.delete(); newer.delete();
    }
  }

}
