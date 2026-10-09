package myapp.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Rect;
import android.text.InputType;
import android.view.View;
import android.widget.ProgressBar;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

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
        assertEquals(View.GONE, screen.findViewById(R.id.voice_question_status).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.voice_user_player).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.voice_assistant_player).getVisibility());
        assertEquals(View.GONE, screen.findViewById(R.id.voice_transcribe).getVisibility());
      });
    }
  }


  @Test public void primaryControlsHaveAccessibleNamesAndTouchTargets() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        int minimum = Math.round(48f * screen.getResources().getDisplayMetrics().density);
        int[] ids = {
            R.id.voice_settings, R.id.voice_draft, R.id.voice_start_stop, R.id.voice_send
        };
        for (int id : ids) {
          View view = screen.findViewById(id);
          assertNotNull(view);
          CharSequence description = view.getContentDescription();
          CharSequence text = view instanceof android.widget.TextView
              ? ((android.widget.TextView) view).getText() : null;
          CharSequence hint = view instanceof android.widget.TextView
              ? ((android.widget.TextView) view).getHint() : null;
          boolean named = (description != null && description.length() > 0)
              || (text != null && text.length() > 0)
              || (hint != null && hint.length() > 0);
          assertTrue("interactive view has no accessible name: " + id, named);
          if (id != R.id.voice_draft) {
            assertTrue("touch target too short: " + id, view.getHeight() >= minimum);
          }
        }
      });
    }
  }

  @Test public void statusAndQuestionsAreAccessibilityLiveRegions() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        View status = screen.findViewById(R.id.voice_status);
        View questions = screen.findViewById(R.id.voice_question_status);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.getAccessibilityLiveRegion());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, questions.getAccessibilityLiveRegion());
      });
    }
  }

  @Test public void exactWorkerQuestionsAreInspectableFromDedicatedField() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          JSONObject snapshot = new JSONObject();
          snapshot.put("type", "worker");
          snapshot.put("event", "snapshot");
          snapshot.put("worker_id", "swaag-orchestrator");
          snapshot.put("status", "input_required");
          snapshot.put("sequence", 0);
          snapshot.put("message", "Two questions need review.");
          snapshot.put("question_inventory_complete", true);
          snapshot.put("semantic_status", new JSONObject()
              .put("open_questions", 2)
              .put("blocking_questions", 1)
              .put("major_or_critical_questions", 1));
          snapshot.put("questions", new JSONArray()
              .put(new JSONObject()
                  .put("question_id", "q-critical")
                  .put("worker_id", "worker-a")
                  .put("question", "Which deployment target should I use exactly?")
                  .put("criticality", "blocking")
                  .put("importance", "critical")
                  .put("reason", "The choice changes the result."))
              .put(new JSONObject()
                  .put("question_id", "q-optional")
                  .put("worker_id", "worker-b")
                  .put("question", "Would you prefer the compact label?")
                  .put("criticality", "optional")
                  .put("importance", "minor")
                  .put("assumption_if_unanswered", "Keep the current label.")));
          screen.handleWorkerEvent(snapshot);
        } catch (Exception failure) {
          throw new AssertionError(failure);
        }
      });
      scenario.onActivity(screen -> {
        android.widget.TextView questions = screen.findViewById(R.id.voice_question_status);
        assertDisplayed(questions);
        assertTrue(questions.isClickable());
        assertTrue(questions.isFocusable());
        int minimum = Math.round(48f * screen.getResources().getDisplayMetrics().density);
        assertTrue("question field touch target too short", questions.getHeight() >= minimum);
        assertTrue(questions.getText().toString().contains("Which deployment target should I use exactly?"));
        assertTrue("blocking questions must be visually conspicuous", questions.getTypeface().isBold());
        String details = screen.workerQuestionDetailsText();
        assertTrue(details.contains("Blocking • critical • worker-a"));
        assertTrue(details.contains("Which deployment target should I use exactly?"));
        assertTrue(details.contains("Optional • minor • worker-b"));
        assertTrue(details.contains("Would you prefer the compact label?"));
        assertTrue(details.contains("Keep the current label."));
        assertTrue(questions.performClick());
      });
      Thread.sleep(200L);
    }
  }

  @Test public void foregroundProgressTracksOnlyActiveOperationStates() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          java.lang.reflect.Method mode = MainActivity.class.getDeclaredMethod(
              "setForegroundMode",
              Class.forName("myapp.app.MainActivity$ForegroundMode"),
              String.class);
          mode.setAccessible(true);
          Class<?> enumType = Class.forName("myapp.app.MainActivity$ForegroundMode");
          @SuppressWarnings({"unchecked", "rawtypes"})
          Object transcribing = Enum.valueOf((Class<? extends Enum>) enumType, "TRANSCRIBING");
          mode.invoke(screen, transcribing, null);
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      scenario.onActivity(screen -> assertEquals(
          View.VISIBLE, screen.findViewById(R.id.voice_status_progress).getVisibility()));
      scenario.onActivity(screen -> {
        try {
          java.lang.reflect.Method mode = MainActivity.class.getDeclaredMethod(
              "setForegroundMode",
              Class.forName("myapp.app.MainActivity$ForegroundMode"),
              String.class);
          mode.setAccessible(true);
          Class<?> enumType = Class.forName("myapp.app.MainActivity$ForegroundMode");
          @SuppressWarnings({"unchecked", "rawtypes"})
          Object listening = Enum.valueOf((Class<? extends Enum>) enumType, "LISTENING");
          mode.invoke(screen, listening, null);
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      scenario.onActivity(screen -> assertEquals(
          View.GONE, screen.findViewById(R.id.voice_status_progress).getVisibility()));
    }
  }

  @Test public void endlessWorkerProgressIsExplicitlyMarkedAsContinuous() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          screen.handleWorkerEvent(new JSONObject()
              .put("type", "worker").put("event", "snapshot")
              .put("worker_id", "swaag-orchestrator").put("status", "working").put("sequence", 0)
              .put("message", "1 background worker is active; intentionally continuous work with no overall completion target.")
              .put("question_inventory_complete", true).put("questions", new JSONArray())
              .put("semantic_status", new JSONObject()
                  .put("active_workers", 1).put("open_questions", 0)
                  .put("blocking_questions", 0).put("major_or_critical_questions", 0)
                  .put("progress_percent", JSONObject.NULL)
                  .put("progress_kind", "intentionally_endless")));
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      scenario.onActivity(screen -> {
        ProgressBar progress = screen.findViewById(R.id.voice_worker_progress);
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertTrue(progress.isIndeterminate());
        assertTrue(progress.getContentDescription().toString().contains("intentionally continuous"));
        android.widget.TextView worker = screen.findViewById(R.id.voice_worker_status);
        assertTrue(worker.getText().toString().contains("intentionally continuous work"));
      });
    }
  }

  @Test public void workerProgressIsDeterminateOnlyWhenSwaagProvidesRealPercent() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(screen -> {
        try {
          screen.handleWorkerEvent(new JSONObject()
              .put("type", "worker").put("event", "snapshot")
              .put("worker_id", "swaag-orchestrator").put("status", "working").put("sequence", 0)
              .put("question_inventory_complete", true).put("questions", new JSONArray())
              .put("semantic_status", new JSONObject()
                  .put("active_workers", 1).put("open_questions", 0)
                  .put("blocking_questions", 0).put("major_or_critical_questions", 0)
                  .put("progress_percent", 42.4).put("progress_kind", "finite")));
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      scenario.onActivity(screen -> {
        ProgressBar progress = screen.findViewById(R.id.voice_worker_progress);
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertTrue(!progress.isIndeterminate());
        assertEquals(42, progress.getProgress());
      });
      scenario.onActivity(screen -> {
        try {
          screen.handleWorkerEvent(new JSONObject()
              .put("type", "worker").put("event", "snapshot")
              .put("worker_id", "swaag-orchestrator").put("status", "working").put("sequence", 0)
              .put("question_inventory_complete", true).put("questions", new JSONArray())
              .put("semantic_status", new JSONObject()
                  .put("active_workers", 2).put("open_questions", 0)
                  .put("blocking_questions", 0).put("major_or_critical_questions", 0)
                  .put("progress_percent", JSONObject.NULL).put("progress_kind", "")));
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      scenario.onActivity(screen -> {
        ProgressBar progress = screen.findViewById(R.id.voice_worker_progress);
        assertEquals(View.VISIBLE, progress.getVisibility());
        assertTrue(progress.isIndeterminate());
      });
      scenario.onActivity(screen -> {
        try {
          screen.handleWorkerEvent(new JSONObject()
              .put("type", "worker").put("event", "snapshot")
              .put("worker_id", "swaag-orchestrator").put("status", "completed").put("sequence", 0)
              .put("question_inventory_complete", true).put("questions", new JSONArray())
              .put("semantic_status", new JSONObject()
                  .put("active_workers", 0).put("open_questions", 0)
                  .put("blocking_questions", 0).put("major_or_critical_questions", 0)
                  .put("progress_percent", JSONObject.NULL).put("progress_kind", "")));
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
      scenario.onActivity(screen -> assertEquals(
          View.GONE, screen.findViewById(R.id.voice_worker_progress).getVisibility()));
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
