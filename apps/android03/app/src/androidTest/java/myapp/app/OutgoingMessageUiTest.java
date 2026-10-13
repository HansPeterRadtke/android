package myapp.app;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.material.textfield.TextInputEditText;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OutgoingMessageUiTest {
  @Before public void reset() { VoiceUiTestState.reset(); }

  static void verifyTwoAcknowledgedMessages() {
    Fixture transport = new Fixture();
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(activity -> {
        transport.attach(activity);
        editor(activity).setText("Exact typed message /data/example.txt");
        assertTrue(activity.findViewById(R.id.voice_send).performClick());
      });
      await(() -> transport.messages.size() == 1, "First durable message was not sent");
      JSONObject first = transport.messages.get(0);
      assertEquals("text_turn", first.optString("type"));
      assertEquals(32, first.optString("client_request_id").length());
      assertEquals("Exact typed message /data/example.txt", first.optString("text"));
      screen.onActivity(activity -> {
        assertEquals(first.optString("text"), editor(activity).getText().toString());
        assertTrue(activity.findViewById(R.id.voice_send).isEnabled());
        activity.findViewById(R.id.voice_send).performClick();
        assertEquals("No duplicate while awaiting ACK", 1, transport.messages.size());
        transport.acknowledge(activity, first);
        assertEquals("", editor(activity).getText().toString());
        editor(activity).setText("Second separate typed message");
        assertTrue(activity.findViewById(R.id.voice_send).performClick());
      });
      await(() -> transport.messages.size() == 2, "Second durable message was not sent");
      JSONObject second = transport.messages.get(1);
      assertEquals("Second separate typed message", second.optString("text"));
      assertNotEquals(first.optString("client_request_id"), second.optString("client_request_id"));
      screen.onActivity(activity -> {
        editor(activity).setText("Newer unsent draft");
        transport.acknowledge(activity, second);
        assertEquals("Newer unsent draft", editor(activity).getText().toString());
      });
    }
  }

  @Test public void blockedPersistenceKeepsUiResponsiveAndSurvivesRecreation() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean wroteOnMain = new AtomicBoolean();
    AtomicInteger writes = new AtomicInteger();
    Fixture transport = new Fixture();
    ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class);
    try {
      screen.onActivity(activity -> {
        transport.attach(activity);
        interceptCommit(activity, () -> {
          writes.incrementAndGet();
          wroteOnMain.set(Looper.myLooper() == Looper.getMainLooper());
          entered.countDown();
          if (wroteOnMain.get()) return false;
          try { return release.await(180, TimeUnit.SECONDS); }
          catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
        });
        editor(activity).setText("  Keep THIS exact text.\nSecond line.  ");
        assertTrue(activity.findViewById(R.id.voice_send).performClick());
      });
      assertTrue("Persistence never started", entered.await(30, TimeUnit.SECONDS));
      assertFalse("Disk commit ran on the UI thread", wroteOnMain.get());
      screen.onActivity(activity -> {
        editor(activity).setText("A newer draft while storage is blocked");
        activity.findViewById(R.id.voice_send).performClick();
        assertTrue("Sent before persistence completed", transport.messages.isEmpty());
      });
      screen.recreate();
      screen.onActivity(activity -> {
        transport.attach(activity);
        assertEquals("A newer draft while storage is blocked", editor(activity).getText().toString());
        activity.findViewById(R.id.voice_send).performClick();
        assertTrue("Recreation bypassed persistence", transport.messages.isEmpty());
      });
      assertEquals("Retry created another writer", 1, writes.get());
      release.countDown();
      await(() -> transport.messages.size() == 1, "Saved message did not resume after recreation");
      JSONObject submitted = transport.messages.get(0);
      assertEquals("  Keep THIS exact text.\nSecond line.  ", submitted.optString("text"));
      screen.onActivity(activity -> {
        transport.acknowledge(activity, submitted);
        assertEquals("A newer draft while storage is blocked", editor(activity).getText().toString());
      });
    } finally { release.countDown(); screen.close(); }
  }

  @Test public void failedPersistenceSendsNothingAndRetryKeepsTheSameIdentity() {
    AtomicInteger attempts = new AtomicInteger();
    Fixture transport = new Fixture();
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(activity -> {
        transport.attach(activity);
        interceptCommit(activity, () -> attempts.incrementAndGet() > 1);
        editor(activity).setText("Preserve me through a storage failure");
        activity.findViewById(R.id.voice_send).performClick();
      });
      await(() -> VoiceSubmissionPersistence.SHARED.current() != null
          && VoiceSubmissionPersistence.SHARED.current().result.isDone(), "Write did not finish");
      String frozen = VoiceSubmissionPersistence.SHARED.current().payload;
      assertFalse(VoiceSubmissionPersistence.SHARED.current().durable());
      assertTrue("Failed save leaked a network request", transport.messages.isEmpty());
      screen.onActivity(activity -> {
        assertEquals("Preserve me through a storage failure", editor(activity).getText().toString());
        activity.findViewById(R.id.voice_send).performClick();
      });
      await(() -> transport.messages.size() == 1, "Retry did not send the saved message");
      assertEquals(frozen, transport.messages.get(0).toString());
      assertEquals(2, attempts.get());
      screen.onActivity(activity -> transport.acknowledge(activity, transport.messages.get(0)));
    }
  }

  private static void interceptCommit(MainActivity activity, BooleanSupplier allowCommit) {
    SharedPreferences original = (SharedPreferences) get(activity, "historyPrefs");
    SharedPreferences wrapped = (SharedPreferences) Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
          if (!"edit".equals(method.getName())) return method.invoke(original, args);
          SharedPreferences.Editor edit = original.edit();
          return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
              new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, editorMethod, editorArgs) -> {
                if ("commit".equals(editorMethod.getName()) && !allowCommit.getAsBoolean()) return false;
                Object result = editorMethod.invoke(edit, editorArgs);
                return result == edit ? editorProxy : result;
              });
        });
    set(activity, "historyPrefs", wrapped);
  }

  private static final class Fixture {
    final List<JSONObject> messages = new CopyOnWriteArrayList<>();
    long generation;
    void attach(MainActivity activity) {
      ((AtomicBoolean) get(activity, "connectionWanted")).set(false);
      VoiceForegroundService service = (VoiceForegroundService) get(activity, "voiceService");
      WebSocketListener listener = (WebSocketListener) get(activity, "boundVoiceListener");
      if (service != null && listener != null) service.detach(listener);
      invoke(activity, "closeCurrentSocket", new Class<?>[]{String.class, boolean.class}, "fixture", true);
      VoiceConnectionTracker tracker = (VoiceConnectionTracker) get(activity, "connectionTracker");
      generation = tracker.beginConnect();
      assertTrue(tracker.onOpen(generation, System.currentTimeMillis()));
      WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
          new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
            if ("send".equals(method.getName())) {
              JSONObject value = new JSONObject((String) args[0]);
              if ("text_turn".equals(value.optString("type")) || "submit".equals(value.optString("type")))
                messages.add(value);
              return true;
            }
            if ("queueSize".equals(method.getName())) return 0L;
            if ("close".equals(method.getName())) return true;
            return null;
          });
      set(activity, "webSocket", socket); set(activity, "serverHelloReady", true);
    }
    void acknowledge(MainActivity activity, JSONObject sent) {
      try {
        JSONObject ack = new JSONObject().put("type", "turn").put("event", "submitted")
            .put("turn_id", "turn-client-" + sent.getString("client_request_id"))
            .put("text", sent.getString("text"));
        invoke(activity, "handleWsJson", new Class<?>[]{String.class, long.class}, ack.toString(), generation);
      } catch (Exception failure) { throw new AssertionError(failure); }
    }
  }
  private static TextInputEditText editor(MainActivity activity) { return activity.findViewById(R.id.voice_draft); }
  private static Object get(Object target, String name) {
    try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static void set(Object target, String name, Object value) {
    try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static void invoke(MainActivity activity, String name, Class<?>[] types, Object... values) {
    try { java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod(name, types);
      method.setAccessible(true); method.invoke(activity, values); }
    catch (Exception failure) { throw new AssertionError(failure); }
  }
  private static void await(BooleanSupplier ready, String message) {
    long end = SystemClock.elapsedRealtime() + 30000;
    do { if (ready.getAsBoolean()) return; SystemClock.sleep(25); }
    while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
