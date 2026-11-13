package myapp.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in TLS loopback test, with recorded real speech and no SWAAG submission. */
@RunWith(AndroidJUnit4.class)
public final class PlaybackReceiptReconnectTest {
  @Rule public ServiceTestRule services=new ServiceTestRule();

  @Test public void completedOfflineAudioIsConfirmedAfterFreshGreetedSocket() throws Exception {
    String enabled=InstrumentationRegistry.getArguments().getString("voiceReceiptChain","");
    Assume.assumeTrue("Only an explicitly isolated fixture",BuildConfig.GUIDELINE_TEST && "1".equals(enabled));
    String receiptMode=InstrumentationRegistry.getArguments().getString("voiceReceiptAckMode","legacy");
    assertTrue("Only declared isolated modes are accepted",
        "legacy".equals(receiptMode) || "confirmed".equals(receiptMode)
            || "drop_ack_once".equals(receiptMode));
    String encoded=InstrumentationRegistry.getArguments().getString("voiceReceiptCert","");
    assertFalse("Expected the fixture's pinned public certificate",encoded.isEmpty());
    assertFalse("No production credential is used by this test",BuildConfig.VOICE_AUTH_TOKEN.isEmpty());

    byte[] cert=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);
    KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null);
    store.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509")
        .generateCertificate(new ByteArrayInputStream(cert)));
    TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    X509TrustManager trust=(X509TrustManager)factory.getTrustManagers()[0];
    SSLContext tls=SSLContext.getInstance("TLS");
    tls.init(null,new javax.net.ssl.TrustManager[]{trust},null);
    OkHttpClient client=new OkHttpClient.Builder().sslSocketFactory(tls.getSocketFactory(),trust)
        .connectTimeout(4000,TimeUnit.MILLISECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS).build();

    Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    String conversation="receipt-fixture-"+SystemClock.elapsedRealtime();
    assertTrue(app.getSharedPreferences("voice_agent_history",Context.MODE_PRIVATE).edit()
        .putString("conversation_id_v2",conversation)
        .putBoolean("transcribe_automatically_v1",false)
        .putBoolean("send_automatically_v3",false).commit());
    VoiceForegroundService service=((VoiceForegroundService.LocalBinder)
        services.bindService(new Intent(app,VoiceForegroundService.class))).service();
    set(get(service,"config"),"websocketUrl","wss://127.0.0.1:15486/ws");
    OkHttpClient originalClient=(OkHttpClient)get(service,"http");
    set(service,"http",client);
    AtomicInteger connections=new AtomicInteger();
    WebSocketListener listener=new WebSocketListener() {
      @Override public void onOpen(WebSocket socket,Response response) { connections.incrementAndGet(); }
    };
    try {
      service.attach(listener,null,1L,conversation);
      VoicePcmPlayback player=service.assistantPlayback();
      // Wait for the real WebSocket to deliver the full local recorded speech.
      await(() -> player.availableMs()>=3000 && !player.isStreamOpen(),16000,
          "Fixture speech did not arrive or end correctly");
      // Force a real transport drop while the AudioTrack is still rendering.
      // The fixture server also closes its first socket, but OkHttp may delay the
      // remote close callback; a local cancellation makes this timing deterministic.
      // This changes only the bound isolated test service, not production settings.
      assertTrue("Audio had already finished before interruption",player.currentMs()<player.availableMs());
      set(service,"connectionDesired",false);
      service.requestReconnect("fixture_drop_during_playback");
      assertFalse("The live socket did not close",service.isConnected());
      VoicePlaybackReceipts receipts=(VoicePlaybackReceipts)get(service,"playbackReceipts");
      await(() -> receipts.pendingCount()==1,14000,"Rendered speech lost its offline confirmation");
      assertEquals("Rendered AudioTrack must reach the last sample",player.availableMs(),player.currentMs());
      assertEquals("Only the first socket should be open",1,connections.get());
      assertFalse("Test must never activate microphone",service.isCapturing());
      set(service,"connectionDesired",true);
      service.requestReconnect("fixture_reopen_after_playback");
      int expectedConnections="drop_ack_once".equals(receiptMode)?3:2;
      await(() -> connections.get()==expectedConnections && receipts.pendingCount()==0,22000,
          "Server acknowledgement or reconnect did not clear the playback confirmation");
      assertFalse("No voice capture is permitted",service.isCapturing());
    } finally {
      set(service,"connectionDesired",false);
      service.requestReconnect("fixture_shutdown");
      service.detach(listener);
      originalClient.dispatcher().executorService().shutdown();
      client.dispatcher().executorService().shutdown();
      client.connectionPool().evictAll();
    }
  }

  private static Object get(Object target,String name) {
    try { Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target); }
    catch(ReflectiveOperationException e) { throw new AssertionError(e); }
  }
  private static void set(Object target,String name,Object value) {
    try { Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value); }
    catch(ReflectiveOperationException e) { throw new AssertionError(e); }
  }
  private static void await(BooleanSupplier condition,long timeoutMs,String error) {
    long until=SystemClock.elapsedRealtime()+timeoutMs;
    do { if(condition.getAsBoolean())return;SystemClock.sleep(50); }
    while(SystemClock.elapsedRealtime()<until);
    fail(error);
  }
}
