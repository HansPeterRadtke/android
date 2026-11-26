package myapp.app;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure, deterministic connection/voice configuration. No credentials. */
final class GeneralVoiceProfile {
  static final String DEFAULT_ENDPOINT = "wss://jetson-fdx.jimmyandjonny.work/ws";
  private static final Pattern VOICE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
  private GeneralVoiceProfile() {}

  static String normalizeEndpoint(String input) {
    if (input == null) throw new IllegalArgumentException("Voice URL missing");
    String value = input.trim();
    URI u;
    try { u = URI.create(value); }
    catch (Exception error) { throw new IllegalArgumentException("Invalid voice URL"); }
    if (!"wss".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
        || u.getHost().isEmpty() || u.getRawUserInfo() != null
        || u.getRawQuery() != null || u.getRawFragment() != null
        || !"/ws".equals(u.getPath())
        || (u.getPort() != -1 && u.getPort() != 443))
      throw new IllegalArgumentException("Use a secure WSS voice gateway ending in /ws");
    return "wss://" + u.getHost().toLowerCase(Locale.ROOT) + "/ws";
  }

  static String healthFor(String endpoint) {
    String normalized = normalizeEndpoint(endpoint);
    return "https://" + normalized.substring("wss://".length(), normalized.length() - "/ws".length()) + "/health";
  }

  /** Empty string means server-selected default; no invented voice IDs. */
  static String normalizeVoice(String id) {
    if (id == null || id.isEmpty()) return "";
    for (int i=0; i<id.length();i++) {
      if (Character.isISOControl(id.charAt(i)))
        throw new IllegalArgumentException("Voice ID contains a control character");
    }
    String value = id.trim();
    if (value.isEmpty()) return "";
    if (!VOICE.matcher(value).matches())
      throw new IllegalArgumentException("Voice ID must be up to 64 letters, digits, dots, hyphens or underscores");
    return value;
  }

  static int option(int value, int count) {
    return value >= 0 && value < count ? value : 0;
  }

  static boolean isAllowedSetting(String property) {
    return "server".equals(property) || "voice".equals(property)
        || "palette".equals(property) || "animation".equals(property)
        || "avatar".equals(property) || "size".equals(property);
  }
}
