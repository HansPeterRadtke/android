package myapp.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Rect;
import android.text.InputType;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;


@RunWith(AndroidJUnit4.class)
public class MainActivityUiTest {
  @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
      Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);

  private static void assertDisplayed(View view) {
    assertEquals(View.VISIBLE, view.getVisibility());
    Rect visible = new Rect();
    assertTrue("view has no global visible rectangle", view.getGlobalVisibleRect(visible));
    assertTrue("visible rectangle is empty", visible.width() > 0 && visible.height() > 0);
  }

  @Test public void defaultScreenIsConversationFirstAndKeepsSecondaryControlsOut() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        assertDisplayed(screen.findViewById(R.id.voice_status));
        assertDisplayed(screen.findViewById(R.id.voice_conversation));
        assertDisplayed(screen.findViewById(R.id.voice_draft));
        assertDisplayed(screen.findViewById(R.id.voice_start_stop));
        assertDisplayed(screen.findViewById(R.id.voice_send));
        assertDisplayed(screen.findViewById(R.id.voice_settings));
        assertEquals(View.GONE, screen.findViewById(R.id.voice_worker_status).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.voice_user_player).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.voice_assistant_player).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.voice_transcribe).getVisibility());
      });
    }
  }


  @Test public void currentMessageIsGenuinelyMultiline() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        TextInputEditText editor = screen.findViewById(R.id.voice_draft);
        assertTrue(editor.getMinLines() >= 2);
        assertTrue(editor.getMaxLines() >= 3);
        assertTrue((editor.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
      });
    }
  }

  @Test public void settingsButtonIsVisibleAndOpensWithoutError() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        MaterialButton settings = screen.findViewById(R.id.voice_settings);
        assertDisplayed(settings);
        assertTrue(settings.performClick());
      });
      Thread.sleep(200L);
    }
  }
}
