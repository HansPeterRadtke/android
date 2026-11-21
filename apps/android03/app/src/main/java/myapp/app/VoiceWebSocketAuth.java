package myapp.app;

import java.net.URI;
import java.util.Locale;
import okhttp3.Request;

/**
 * Authenticated private voice WebSocket handshake. A phone-held bearer token
 * proves possession, NOT the identity of the person speaking.
 *
 * This class MUST NOT log, send in a URL, or redirect the credential to an
 * arbitrary endpoint. The verified Android03 config uses the owner WSS host.
 */
final class VoiceWebSocketAuth {
  private static final String TRUSTED_HOST = "jetson-fdx.jimmyandjonny.work";
  private VoiceWebSocketAuth() {}

  static boolean configured(String token) {
    if (token == null || token.isEmpty() || token.length() > 512) return false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c <= 0x20 || c >= 0x7f) return false;
    }
    return true;
  }

  /** Never perform a network request when the bearer or WSS target is invalid. */
  static Request request(String url, String token) {
    if (!configured(token))
      throw new IllegalStateException("Private voice credential unavailable or invalid");
    if (url == null)
      throw new IllegalArgumentException("Private WSS voice endpoint unavailable");
    final URI uri;
    try { uri = URI.create(url); }
    catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid private WSS voice endpoint");
    }
    if (!"wss".equalsIgnoreCase(uri.getScheme())
        || !TRUSTED_HOST.equalsIgnoreCase(uri.getHost())
        || !"/ws".equals(uri.getPath())
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null)
      throw new IllegalArgumentException("Untrusted private WSS voice endpoint");
    String query = uri.getRawQuery();
    if (query == null || !query.startsWith("conversation=")
        || !query.contains("&generation=")
        || query.toLowerCase(Locale.ROOT).contains("token=")
        || query.toLowerCase(Locale.ROOT).contains("authorization="))
      throw new IllegalArgumentException("Untrusted private voice handshake query");
    return new Request.Builder().url(url)
        .header("Authorization", "Bearer " + token).build();
  }
}
