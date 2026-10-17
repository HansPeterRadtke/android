package myapp.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in real Android/STT/SWAAG/TTS chain against an isolated loopback TLS tunnel.
 * The fixture certificate is the sole trusted certificate; normal hostname
 * verification remains enabled. No production network or TLS settings change.
 */
@RunWith(AndroidJUnit4.class)
public class ConnectedVoiceWorkflowTest {
  @Rule public ServiceTestRule services = new ServiceTestRule();

  @Test public void savedSpeechTravelsThroughTheAgentAndRendersOnAndroid() throws Exception {
    Assume.assumeTrue("Requires an isolated live fixture", BuildConfig.GUIDELINE_TEST
        && "1".equals(InstrumentationRegistry.getArguments().getString("voiceChain")));
    VoiceUiTestState.reset();
    Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    boolean automatic = "1".equals(InstrumentationRegistry.getArguments().getString("voiceChainAutomatic"));
    boolean naturalPause = "1".equals(InstrumentationRegistry.getArguments().getString("voiceChainNaturalPause"));
    boolean background = "1".equals(InstrumentationRegistry.getArguments().getString("voiceChainBackground"));
    File fixture = new File(app.getFilesDir(), "voice-chain");
    File recording = new File(fixture, "input.pcm");
    assertTrue("Missing synthetic speech fixture", recording.isFile() && recording.length() > 3200);
    String token = readText(new File(fixture, "token")).trim();
    assertFalse(token.isEmpty());
    String conversation = "android-chain-" + UUID.randomUUID();
    assertTrue(app.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE).edit()
        .putString("conversation_id_v2", conversation)
        .putBoolean("transcribe_automatically_v1", automatic)
        .putBoolean("send_automatically_v3", automatic).commit());

    KeyStore certificates = KeyStore.getInstance(KeyStore.getDefaultType());
    certificates.load(null);
    try (FileInputStream input = new FileInputStream(new File(fixture, "loopback.pem"))) {
      certificates.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509")
          .generateCertificate(input));
    }
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(certificates);
    X509TrustManager trust = (X509TrustManager) factory.getTrustManagers()[0];
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(null, new javax.net.ssl.TrustManager[]{trust}, null);
    OkHttpClient client = new OkHttpClient.Builder()
        .sslSocketFactory(tls.getSocketFactory(), trust)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
            .header("Authorization", "Bearer " + token).build())).build();
    VoiceForegroundService service = ((VoiceForegroundService.LocalBinder) services.bindService(
        new Intent(app, VoiceForegroundService.class))).service();
    configure(get(service, "config"));
    set(service, "http", client);
    long started = SystemClock.elapsedRealtime();
    try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(activity -> {
        configure(get(activity, "appConfig"));
        set(activity, "wsClient", client);
        if (!automatic) activity.restoreManualRecording(recording);
        else {
          // Match the real Mic entry point's started-service ownership while
          // substituting only its microphone source. A bound-only synthetic
          // uploader does not exercise the production capture lifecycle.
          try {
            java.lang.reflect.Method start = MainActivity.class.getDeclaredMethod("startVoiceForegroundService");
            start.setAccessible(true);
            assertTrue("Could not protect the automatic voice session", (Boolean) start.invoke(activity));
          } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
      });
      awaitScreen(screen, activity -> (Boolean) get(activity, "serverHelloReady")
          && (automatic || (Boolean) get(activity, "manualRecordingAvailable")), 60000,
          "Android never received hello and recovered the recording");
      if (automatic) {
        await(() -> (Boolean) get(service, "foreground"), 30000,
            "The automatic fixture never entered the real started-service lifecycle");
        // Exercise the live PCM uplink and real automatic proposal/commit path.
        // Only the microphone source is synthetic; no ASR or server event is injected.
        WebSocket socket = (WebSocket) get(service, "networkSocket");
        try (FileInputStream input = new FileInputStream(recording)) {
          byte[] frame = new byte[3200];
          int count;
          while ((count = input.read(frame)) > 0) {
            assertTrue("PCM uplink rejected", socket.send(ByteString.of(frame, 0, count)));
            SystemClock.sleep(count * 1000L / 32000L);
          }
        }
        if (naturalPause) {
          // Let real acoustic endpointing and the semantic classifier decide.
          // Do not use the explicit end-utterance escape hatch in this case.
          byte[] silence = new byte[3200];
          for (int i = 0; i < 35; i++) {
            assertTrue("Silence uplink rejected", socket.send(ByteString.of(silence)));
            SystemClock.sleep(100L);
          }
        } else {
          assertTrue(service.sendText(new JSONObject().put("type", "end_utterance")
              .put("reason", "capture_stopped").toString()));
        }
      } else {
        screen.onActivity(activity -> assertTrue(activity.findViewById(R.id.voice_transcribe).performClick()));
      }
      AtomicReference<String> recognized = new AtomicReference<>();
      if (automatic) {
        // Automatic ACK can clear a transient ASR draft before the UI poll.
        // Assert the exact fixture recognition in durable, acknowledged history.
        recognized.set("Can you hear me? Please say hello.");
      } else {
        awaitScreen(screen, activity -> !(Boolean) get(activity, "manualTranscribePending")
            && ((EditText) activity.findViewById(R.id.voice_draft)).getText().length() > 0,
            90000, "Real ASR did not reach the editable draft");
        screen.onActivity(activity -> {
          recognized.set(((EditText) activity.findViewById(R.id.voice_draft)).getText().toString());
          assertTrue(recognized.get(), recognized.get().toLowerCase(java.util.Locale.ROOT).contains("hear me"));
          assertTrue(recognized.get(), recognized.get().toLowerCase(java.util.Locale.ROOT).contains("hello"));
          assertTrue(activity.findViewById(R.id.voice_send).performClick());
        });
      }
      awaitScreen(screen, activity -> ((EditText) activity.findViewById(R.id.voice_draft)).getText().length() == 0
          && ((TextView) activity.findViewById(R.id.voice_conversation)).getText().toString().contains(recognized.get()),
          15000, "Submitted speech stayed in the editing window or never reached acknowledged history");
      long acknowledgedMs = SystemClock.elapsedRealtime() - started;
      if (background) {
        screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
        // Remove the test's binding too: only the app's started foreground
        // service may protect this pending request and play the eventual reply.
        services.unbindService();
      }
      VoicePcmPlayback playback = service.assistantPlayback();
      await(() -> playback.hasAudio() && !playback.isStreamOpen() && !playback.isPlaying()
          && playback.currentMs() == playback.availableMs() && playback.currentMs() > 0,
          150000, "The real TTS reply never finished rendering through AudioTrack");
      if (background) {
        assertFalse("Background delivery activated the microphone", service.isCapturing());
        assertFalse("The voice service died with the screen", (Boolean) get(service, "terminating"));
        screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        awaitScreen(screen, activity -> get(activity, "voiceService") == service
            && !(Boolean) get(activity, "manualSendAwaitingAck")
            && app.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE)
                .getString("send_payload_v1", "").isEmpty()
            && ((TextView) activity.findViewById(R.id.voice_conversation)).getText()
                .toString().contains("\nAgent\n"), 60000,
            "Returning screen did not reconcile the background reply and acknowledgement");
      }
      AtomicReference<String> dialogue = new AtomicReference<>();
      screen.onActivity(activity -> {
        dialogue.set(((TextView) activity.findViewById(R.id.voice_conversation)).getText().toString());
        assertTrue("The exact submitted text is missing", dialogue.get().contains(recognized.get()));
        assertFalse("Successful submission left an unacknowledged outbox",
            (Boolean) get(activity, "manualSendAwaitingAck"));
        assertEquals("", app.getSharedPreferences("voice_agent_history", Context.MODE_PRIVATE)
            .getString("send_payload_v1", ""));
      });
      JSONObject result = new JSONObject().put("conversation", conversation)
          .put("recognized", recognized.get()).put("dialogue", dialogue.get())
          .put("rendered_ms", playback.currentMs()).put("available_ms", playback.availableMs())
          .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
          .put("background_without_test_binding", background)
          .put("automatic_send", automatic).put("acknowledged_ms", acknowledgedMs)
          .put("natural_pause", naturalPause)
          .put("scope", "Synthetic speech, actual Android UI/service, live ASR/SWAAG/TTS, emulator AudioTrack");
      try (FileOutputStream output = new FileOutputStream(new File(fixture, "result.json"))) {
        output.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
      }
    } finally {
      service.assistantPlayback().cancelStream();
      app.stopService(new Intent(app, VoiceForegroundService.class));
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  private static void configure(Object config) {
    set(config, "websocketUrl", "wss://127.0.0.1:14485/ws");
    set(config, "healthUrl", "https://127.0.0.1:14485/health");
  }
  private static Object get(Object target, String name) {
    try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }
  private static void set(Object target, String name, Object value) {
    try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }
  private static String readText(File file) throws Exception {
    byte[] bytes = new byte[(int) file.length()];
    try (java.io.DataInputStream input = new java.io.DataInputStream(new FileInputStream(file))) { input.readFully(bytes); }
    return new String(bytes, StandardCharsets.UTF_8);
  }
  private static void awaitScreen(ActivityScenario<MainActivity> screen, Predicate<MainActivity> ready,
      long timeout, String failure) {
    await(() -> {
      java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean();
      screen.onActivity(activity -> result.set(ready.test(activity)));
      return result.get();
    }, timeout, failure);
  }
  private static void await(BooleanSupplier ready, long timeout, String failure) {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    do { if (ready.getAsBoolean()) return; SystemClock.sleep(100); }
    while (SystemClock.elapsedRealtime() < deadline);
    fail(failure);
  }
}
