package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;
import okhttp3.Request;

public class VoiceWebSocketAuthTest {
  private static final String GOOD_URL =
      "wss://jetson-fdx.jimmyandjonny.work/ws?conversation=localtest&generation=1";

  @Test public void bearerIsOnlyInHeaderOverPinnedTlsHost() {
    Request req = VoiceWebSocketAuth.request(GOOD_URL, "test-token-123");
    assertEquals("Bearer test-token-123", req.header("Authorization"));
    assertFalse(req.url().toString().contains("test-token-123"));
    assertTrue(req.url().isHttps());
    assertEquals("jetson-fdx.jimmyandjonny.work",req.url().host());
  }

  @Test public void missingMalformedAndHeaderInjectionCredentialsFailClosed() {
    for (String token : new String[]{null,""," "," secret","secret ","abc\r\ndef","tab\tvalue","foo\u007f"}) {
      assertFalse(VoiceWebSocketAuth.configured(token));
      try {VoiceWebSocketAuth.request(GOOD_URL,token);fail("Accepted invalid private token");}
      catch (IllegalStateException expected) {}
    }
    assertFalse(VoiceWebSocketAuth.configured("x".repeat(513)));
    assertTrue(VoiceWebSocketAuth.configured("test-token-123"));
  }

  @Test public void onlyOwnerWssEndpointMayReceivePrivateBearer() {
    for (String url : new String[]{
        "ws://jetson-fdx.jimmyandjonny.work/ws?conversation=a&generation=1",
        "http://jetson-fdx.jimmyandjonny.work/ws?conversation=a&generation=1",
        "https://jetson-fdx.jimmyandjonny.work/ws?conversation=a&generation=1",
        "wss://wrong.example/ws?conversation=a&generation=1",
        "wss://user:pass@jetson-fdx.jimmyandjonny.work/ws?conversation=a&generation=1",
        "wss://jetson-fdx.jimmyandjonny.work:8000/ws?conversation=a&generation=1",
        "wss://jetson-fdx.jimmyandjonny.work/other?conversation=a&generation=1",
        "wss://jetson-fdx.jimmyandjonny.work/ws?conversation=a&generation=1#redirect",
        "wss://jetson-fdx.jimmyandjonny.work/ws?token=wrong&generation=1",
        "wss://jetson-fdx.jimmyandjonny.work/ws?conversation=a&token=wrong&generation=1",
        "wss://jetson-fdx.jimmyandjonny.work/ws?conversation=a"}) {
      try {VoiceWebSocketAuth.request(url,"test-token");fail("Accepted untrusted WSS endpoint");}
      catch (IllegalArgumentException expected) {}
    }
  }

  @Test public void noMissingCredentialFallbackToAnonymousWebSocket() {
    assertFalse(VoiceWebSocketAuth.configured(""));
    try {VoiceWebSocketAuth.request(GOOD_URL,"");fail("A missing credential created a socket request");}
    catch (IllegalStateException expected) {}
  }
}
