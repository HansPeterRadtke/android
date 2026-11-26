package myapp.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeneralVoiceProfileTest {
  @Test public void defaultWssEndpointIsSafe() {
    assertEquals(GeneralVoiceProfile.DEFAULT_ENDPOINT,
        GeneralVoiceProfile.normalizeEndpoint(GeneralVoiceProfile.DEFAULT_ENDPOINT));
    assertEquals("https://jetson-fdx.jimmyandjonny.work/health",
        GeneralVoiceProfile.healthFor(GeneralVoiceProfile.DEFAULT_ENDPOINT));
  }
  @Test public void acceptsSecureCompatibleServerOnly() {
    assertEquals("wss://my-agent.example/ws",
        GeneralVoiceProfile.normalizeEndpoint("wss://MY-agent.example/ws"));
    for(String endpoint:new String[]{
        "http://example.com/ws","ws://example.com/ws","wss://example.com/secret",
        "wss://user:pass@example.com/ws","wss://example.com/ws?token=a",
        "wss://example.com/ws#secret","wss://example.com:5555/ws",
        "wss://example.com:444/ws","wss://example.com/","file:///etc/passwd",
        "wss://example.com/ws?conversation=x","wss://example.com/ws/../ws"
    }) {
      try {GeneralVoiceProfile.normalizeEndpoint(endpoint);fail(endpoint);}
      catch(IllegalArgumentException expected) {}
    }
  }
  @Test public void serverVoiceDoesNotAllowUnsafeIdentifier() {
    assertEquals("",GeneralVoiceProfile.normalizeVoice(null));
    assertEquals("",GeneralVoiceProfile.normalizeVoice(""));
    assertEquals("east_on1.2",GeneralVoiceProfile.normalizeVoice("  east_on1.2  "));
    for(String voice:new String[]{ "../secret", "east on", "east/on", "\n", "\u0001",
        "-bad", "a".repeat(65), "a&secret=1", "fake?key=2"}) {
      try {GeneralVoiceProfile.normalizeVoice(voice);fail(voice);}
      catch(IllegalArgumentException expected){}
    }
  }
  @Test public void visualIndexCannotEscapePalette() {
    assertEquals(0,GeneralVoiceProfile.option(-1,4));
    assertEquals(0,GeneralVoiceProfile.option(55,4));
    assertEquals(2,GeneralVoiceProfile.option(2,4));
  }
  @Test public void onlyKnownConfigPropertiesAccepted() {
    assertTrue(GeneralVoiceProfile.isAllowedSetting("animation"));
    assertTrue(GeneralVoiceProfile.isAllowedSetting("voice"));
    assertFalse(GeneralVoiceProfile.isAllowedSetting("token"));
    assertFalse(GeneralVoiceProfile.isAllowedSetting("run_code"));
  }
}
