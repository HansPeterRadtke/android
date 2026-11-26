package myapp.app;

import org.junit.Test;
import okhttp3.Request;
import static org.junit.Assert.*;

public class GeneralVoiceWebSocketAuthTest {
  private static final String HOST = "wss://my.example.com/ws";
  @Test public void onlyExactConfiguredHostReceivesBearer() {
    Request request = VoiceWebSocketAuth.requestForEndpoint(
        HOST + "?conversation=voice-123&generation=4","test-token",HOST);
    assertEquals("Bearer test-token",request.header("Authorization"));
    assertEquals("my.example.com",request.url().host());
    assertEquals("/ws",request.url().encodedPath());
  }
  @Test public void neverAttachCredentialToWrongDestination() {
    String[] invalid={
      "wss://other.example.com/ws?conversation=voice-123&generation=4",
      "wss://my.example.com/other?conversation=voice-123&generation=4",
      "ws://my.example.com/ws?conversation=voice-123&generation=4",
      "wss://my.example.com/ws?conversation=voice-123&generation=4&token=leak",
      "wss://my.example.com/ws?conversation=voice-123&generation=abc",
      "wss://username@my.example.com/ws?conversation=voice-123&generation=4"
    };
    for(String url:invalid) {
      try {
        VoiceWebSocketAuth.requestForEndpoint(url,"test-token",HOST);
        fail("Allowed invalid URL");
      } catch(IllegalArgumentException expected){}
    }
  }
  @Test public void untrustedSelectedServerFailsClosed() {
    for(String selected:new String[]{
        "ws://my.example.com/ws",
        "wss://my.example.com/not-voice",
        "wss://my.example.com:444/ws",
        "wss://user:pass@my.example.com/ws"
    }) {
      try {
        VoiceWebSocketAuth.requestForEndpoint(
            HOST + "?conversation=voice-123&generation=4","test-token",selected);
        fail("Allowed invalid selected server");
      } catch(IllegalArgumentException expected){}
    }
  }
  @Test public void missingCredentialNeverOpensConnection() {
    try {
      VoiceWebSocketAuth.requestForEndpoint(
          HOST + "?conversation=voice-123&generation=4","",HOST);
      fail("Missing credential accepted");
    } catch(IllegalStateException expected){}
  }
}
