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
          assertEquals(View.VISIBLE, screen.findViewById(R.id.voice_status_progress).getVisibility());
          @SuppressWarnings({"unchecked", "rawtypes"})
          Object listening = Enum.valueOf((Class<? extends Enum>) enumType, "LISTENING");
          mode.invoke(screen, listening, null);
          assertEquals(View.GONE, screen.findViewById(R.id.voice_status_progress).getVisibility());
        } catch (Exception failure) { throw new AssertionError(failure); }
      });
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

  private static void callPrivate(MainActivity activity, String name,
                                  Class<?>[] signature, Object... args) {
    try {
      java.lang.reflect.Method method =
          MainActivity.class.getDeclaredMethod(name, signature);
      method.setAccessible(true);
      method.invoke(activity, args);
    } catch (Exception e) { throw new AssertionError(e); }
  }

  @Test public void explicitSendStaysEnabledOnLivePartialAndRejectedPartialIsCleared() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        MaterialButton send = activity.findViewById(R.id.voice_send);
        editor.setText("");
        callPrivate(activity, "setAsrDraftText",
            new Class<?>[] {String.class, boolean.class}, "unfinished sentence", true);
        assertEquals("unfinished sentence", editor.getText().toString());
        assertTrue("partial must never disable explicit Send", send.isEnabled());
        callPrivate(activity, "discardRejectedAsrPartial", new Class<?>[] {});
        assertEquals("suppressed ASR should not leave a stale partial", "", editor.getText().toString());
        editor.setText("correction by human");
        callPrivate(activity, "setAsrDraftText",
            new Class<?>[] {String.class, boolean.class}, "bad ASR output", true);
        callPrivate(activity, "discardRejectedAsrPartial", new Class<?>[] {});
        assertEquals("correction by human", editor.getText().toString());
        assertTrue(send.isEnabled());
      });
    }
  }

  @Test public void typedFromScratchCannotBeOverwrittenAndAckCannotDeleteNewDraft() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        MaterialButton send = activity.findViewById(R.id.voice_send);
        editor.setText("exact /data/source/File.java");
        callPrivate(activity, "setAsrDraftText",
            new Class<?>[] {String.class, boolean.class}, "wrong recognition", true);
        assertEquals("exact /data/source/File.java", editor.getText().toString());
        callPrivate(activity, "clearSubmittedDraft",
            new Class<?>[] {String.class, boolean.class}, "some independently submitted speech", false);
        assertEquals("exact /data/source/File.java", editor.getText().toString());
        assertTrue(send.isEnabled());
        callPrivate(activity, "clearSubmittedDraft",
            new Class<?>[] {String.class, boolean.class}, "exact /data/source/File.java", true);
        assertEquals("", editor.getText().toString());
        assertTrue(!send.isEnabled());
      });
    }
  }

  @Test public void submissionDispatchFromWebSocketThreadDoesNotTouchViewsOffMain() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      final MainActivity[] live = {null};
      scenario.onActivity(activity -> {
        live[0] = activity;
        ((TextInputEditText) activity.findViewById(R.id.voice_draft))
            .setText("queued status");
        try {
          java.lang.reflect.Field inFlight =
              MainActivity.class.getDeclaredField("manualSendAwaitingAck");
          inFlight.setAccessible(true);
          inFlight.setBoolean(activity, true);
        } catch (Exception e) { throw new AssertionError(e); }
      });
      final Throwable[] error = {null};
      Thread listener = new Thread(() -> {
        try {
          callPrivate(live[0], "submitDraft", new Class<?>[] {});
        } catch (Throwable e) { error[0] = e; }
      }, "fake-websocket-callback");
      listener.start();
      listener.join(2000L);
      assertTrue("callback blocked", !listener.isAlive());
      assertTrue("callback threw " + error[0], error[0] == null);
      scenario.onActivity(activity -> {
        assertEquals("queued status",
            ((TextInputEditText) activity.findViewById(R.id.voice_draft))
                .getText().toString());
        assertTrue(((MaterialButton) activity.findViewById(R.id.voice_send)).isEnabled());
      });
    }
  }

  @Test public void manualSendActuallyEmitsExactlyOneTypedTurnAndClearsOnlyOnAck() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        try {
          java.lang.reflect.Field wanted =
              MainActivity.class.getDeclaredField("connectionWanted");
          wanted.setAccessible(true);
          ((java.util.concurrent.atomic.AtomicBoolean) wanted.get(activity)).set(false);
          callPrivate(activity, "closeCurrentSocket",
              new Class<?>[] {String.class, boolean.class}, "test fixture", true);
          java.lang.reflect.Field trackerField =
              MainActivity.class.getDeclaredField("connectionTracker");
          trackerField.setAccessible(true);
          VoiceConnectionTracker tracker = (VoiceConnectionTracker) trackerField.get(activity);
          long generation = tracker.beginConnect();
          assertTrue(tracker.onOpen(generation, System.currentTimeMillis()));
          java.util.List<String> payloads = new java.util.ArrayList<>();
          okhttp3.WebSocket ws = (okhttp3.WebSocket) java.lang.reflect.Proxy.newProxyInstance(
              okhttp3.WebSocket.class.getClassLoader(),
              new Class<?>[]{okhttp3.WebSocket.class},
              (proxy, method, args) -> {
                if (method.getName().equals("send")) {
                  payloads.add((String)args[0]);
                  return true;
                }
                if (method.getName().equals("queueSize")) return 0L;
                if (method.getName().equals("close")) return true;
                return null;
              });
          java.lang.reflect.Field socket =
              MainActivity.class.getDeclaredField("webSocket");
          socket.setAccessible(true);
          socket.set(activity, ws);
          java.lang.reflect.Field hello =
              MainActivity.class.getDeclaredField("serverHelloReady");
          hello.setAccessible(true);
          hello.setBoolean(activity, true);

          TextInputEditText editor = activity.findViewById(R.id.voice_draft);
          MaterialButton send = activity.findViewById(R.id.voice_send);
          String original = "Exact typed message /data/example.txt";
          editor.setText(original);
          assertTrue(send.isEnabled());
          assertTrue(send.performClick());
          assertEquals(1, payloads.size());
          JSONObject first = new JSONObject(payloads.get(0));
          assertEquals("text_turn", first.getString("type"));
          assertEquals(original, first.getString("text"));
          String requestId = first.getString("client_request_id");
          assertEquals(32, requestId.length());
          assertEquals(original, editor.getText().toString());
          assertTrue("Send must remain visibly enabled", send.isEnabled());
          send.performClick();
          assertEquals("no duplicate during pending ACK", 1, payloads.size());

          callPrivate(activity, "handleWsJson",
              new Class<?>[]{String.class, long.class},
              new JSONObject().put("type","turn").put("event","submitted")
                  .put("turn_id","turn-client-"+requestId).put("text",original).toString(),
              generation);
          assertEquals("ACK clears only delivered text", "", editor.getText().toString());
          editor.setText("Second separate typed message");
          assertTrue(send.performClick());
          assertEquals(2, payloads.size());
          JSONObject second = new JSONObject(payloads.get(1));
          assertEquals("Second separate typed message", second.getString("text"));
          assertTrue(!requestId.equals(second.getString("client_request_id")));
        } catch (Exception e) { throw new AssertionError(e); }
      });
    }
  }

  @Test public void liveAutomaticSpeechNeverPollutesTypedComposer() {
    android.content.SharedPreferences prefs =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .getTargetContext().getSharedPreferences("voice_agent_history", android.content.Context.MODE_PRIVATE);
    prefs.edit().putBoolean("transcribe_automatically_v1", true)
        .putBoolean("send_automatically_v3", true)
        .remove("user_entered_message_v2").commit();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        android.widget.TextView conversation = activity.findViewById(R.id.voice_conversation);
        assertEquals("", editor.getText().toString());
        try {
          callPrivate(activity, "handleWsJson",
              new Class<?>[]{String.class, long.class},
              new JSONObject().put("type","asr").put("event","partial")
                  .put("text","ghost words definitely never spoken").toString(), 0L);
          assertEquals("partial must never enter the typed field", "", editor.getText().toString());
          assertTrue("live provisional speech should be visible",
              conversation.getText().toString().contains("ghost words definitely never spoken"));
          callPrivate(activity, "handleWsJson",
              new Class<?>[]{String.class, long.class},
              new JSONObject().put("type","asr").put("event","artifact")
                  .put("reason","non_speech_annotation").toString(), 0L);
          assertEquals("", editor.getText().toString());
          assertTrue("suppressed hallucination must disappear",
              !conversation.getText().toString().contains("ghost words definitely never spoken"));
          callPrivate(activity, "handleWsJson",
              new Class<?>[]{String.class, long.class},
              new JSONObject().put("type","asr").put("event","final")
                  .put("text","This is what I actually said.")
                  .put("turn_id","turn-trusted").toString(), 0L);
          assertEquals("", editor.getText().toString());
          assertTrue(conversation.getText().toString().contains("This is what I actually said."));
          callPrivate(activity, "handleWsJson",
              new Class<?>[]{String.class, long.class},
              new JSONObject().put("type","turn").put("event","submitted")
                  .put("turn_id","turn-trusted")
                  .put("text","This is what I actually said.").toString(), 0L);
          assertEquals("", editor.getText().toString());
          assertTrue(conversation.getText().toString().contains("This is what I actually said."));
        } catch (Exception e) { throw new AssertionError(e); }
      });
    }
  }

  @Test public void legacyRecognizedDraftIsBackedUpNotRestoredAsTyping() {
    android.content.SharedPreferences prefs =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .getTargetContext().getSharedPreferences("voice_agent_history", android.content.Context.MODE_PRIVATE);
    prefs.edit().remove("user_entered_message_v2").remove("previous_message_backup_v1")
        .putString("current_message_v1", "WRONG AUTOMATIC SPEECH FROM 1.7.12").commit();
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        assertEquals("", editor.getText().toString());
        assertEquals("WRONG AUTOMATIC SPEECH FROM 1.7.12",
            prefs.getString("previous_message_backup_v1",""));
        assertTrue(!prefs.contains("current_message_v1"));
      });
    } finally {
      prefs.edit().remove("previous_message_backup_v1").commit();
    }
  }

  @Test public void backendErrorRemainsVisibleAndHistoryShowsFailure() {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        android.widget.TextView conversation = activity.findViewById(R.id.voice_conversation);
        android.widget.TextView status = activity.findViewById(R.id.voice_status);
        android.widget.TextView detail = activity.findViewById(R.id.voice_status_detail);
        TextInputEditText editor = activity.findViewById(R.id.voice_draft);
        editor.setText("My real typed text");
        try {
          callPrivate(activity, "handleWsJson", new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","error").put("stage","answer")
                  .put("turn_id","failed-1").put("message","swaag_answer_failed").toString(), 0L);
          assertEquals(activity.getString(R.string.status_answer_failed), status.getText().toString());
          assertEquals(View.VISIBLE, detail.getVisibility());
          callPrivate(activity, "handleWsJson", new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","state").put("state","listening").toString(), 0L);
          assertEquals(activity.getString(R.string.status_answer_failed), status.getText().toString());
          JSONObject turn = new JSONObject().put("turn_id","failed-1")
              .put("user","Make a little Python program").put("status","answer_error")
              .put("answer_failed",true);
          callPrivate(activity, "handleWsJson", new Class<?>[]{String.class,long.class},
              new JSONObject().put("type","history")
                  .put("turns",new JSONArray().put(turn)).toString(),0L);
          assertTrue(conversation.getText().toString().contains("Make a little Python program"));
          assertTrue(conversation.getText().toString().contains(
              activity.getString(R.string.conversation_answer_failed)));
          assertEquals("My real typed text", editor.getText().toString());
        } catch(Exception e) {throw new AssertionError(e);}
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
