package myapp.app;

import org.json.JSONObject;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Fixed, HTTPS-only Raspi private-portal pairing protocol.
 * No long-lived credential is present in the APK, a URL, or browser history.
 */
final class GeneralVoicePairing {
  static final String BASE = "https://raspi.jonnyontherun.org/explorer/voice-agent/pair";
  static final String APPROVAL = BASE + "/approve?id=";
  private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
  private static final Pattern PROOF = Pattern.compile("[a-f0-9]{64}");
  private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
  private final OkHttpClient client = new OkHttpClient.Builder()
      .connectTimeout(7, TimeUnit.SECONDS)
      .readTimeout(7, TimeUnit.SECONDS)
      .callTimeout(11, TimeUnit.SECONDS)
      .followRedirects(false)
      .followSslRedirects(false)
      .build();
  private final SecureRandom random = new SecureRandom();

  static final class Session {
    final String pairId;
    final String secret;
    final long expiresAt;
    Session(String id, String secretHex, long expiry) {
      if (!validId(id) || !validProof(secretHex) || expiry <= 0) throw new IllegalArgumentException("Invalid pairing");
      this.pairId = id;
      this.secret = secretHex;
      this.expiresAt = expiry;
    }
  }

  static final class PairExpiredException extends IOException {
    PairExpiredException() { super("Pairing expired"); }
  }

  static final class Poll {
    final String status;
    final String bearer;
    Poll(String state, String credential) {
      this.status = state;
      this.bearer = credential;
    }
  }
  static boolean validId(String value) {
    return value != null && ID.matcher(value).matches();
  }
  static boolean validProof(String value) {
    return value != null && PROOF.matcher(value).matches();
  }
  static String approvalUrl(String id) {
    if (!validId(id)) throw new IllegalArgumentException("Invalid pairing ID");
    return APPROVAL + id;
  }
  static String hex(byte[] bytes) {
    final char[] alphabet = "0123456789abcdef".toCharArray();
    char[] text = new char[bytes.length * 2];
    for (int i=0;i<bytes.length;i++) {
      int value = bytes[i] & 0xff;
      text[i*2] = alphabet[value >>> 4];
      text[i*2+1] = alphabet[value & 15];
    }
    return new String(text);
  }
  static String hashProof(String privateHex) {
    if (!validProof(privateHex)) throw new IllegalArgumentException("Invalid pairing proof");
    try {
      byte[] input = new byte[32];
      for (int i=0;i<32;i++) input[i] = (byte) Integer.parseInt(privateHex.substring(i*2,i*2+2),16);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
      java.util.Arrays.fill(input,(byte)0);
      return hex(digest);
    } catch (Exception failure) {
      throw new IllegalStateException("Cryptographic provider unavailable");
    }
  }

  private JSONObject post(String path, JSONObject message) throws IOException {
    RequestBody body = RequestBody.create(message.toString(), JSON_MEDIA);
    Request request = new Request.Builder()
        .url(BASE + path)
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .post(body).build();
    try (Response response=client.newCall(request).execute()) {
      if (response.code() == 410) throw new PairExpiredException();
      if (response.code() != 200) throw new IOException("Private portal pairing returned HTTP " + response.code());
      if(response.body()==null) throw new IOException("Empty pairing response");
      String content = response.body().string();
      if(content.length()>4096) throw new IOException("Excessive pairing response");
      try {return new JSONObject(content);}
      catch (Exception invalid) {throw new IOException("Invalid pairing response");}
    }
  }

  Session begin() throws IOException {
    byte[] secret = new byte[32];
    random.nextBytes(secret);
    String value=hex(secret);
    java.util.Arrays.fill(secret,(byte)0);
    try {
      JSONObject payload = new JSONObject();
      payload.put("proof_hash",hashProof(value));
      JSONObject response=post("/start",payload);
      if(!response.optBoolean("ok",false)) throw new IOException("Pairing request declined");
      String id=response.optString("pair_id","");
      int ttl=response.optInt("expires_seconds",0);
      if(!validId(id)||ttl<30||ttl>1200) throw new IOException("Unexpected pairing lifetime");
      return new Session(id,value,System.currentTimeMillis()+ttl*1000L);
    }catch(IOException expected){throw expected;}
    catch(Exception error){throw new IOException("Could not start pairing");}
  }

  Poll poll(Session session) throws IOException {
    if(session==null||System.currentTimeMillis()>=session.expiresAt)
      throw new IOException("Pairing expired");
    try {
      JSONObject message = new JSONObject();
      message.put("pair_id",session.pairId);
      message.put("secret",session.secret);
      JSONObject response=post("/poll",message);
      if(!response.optBoolean("ok",false))throw new IOException("Pairing not accepted");
      String status=response.optString("status","");
      if("pending".equals(status))return new Poll("pending",null);
      if(!"paired".equals(status))throw new IOException("Unknown pairing response");
      String bearer=response.optString("bearer","");
      if(!VoiceWebSocketAuth.configured(bearer))throw new IOException("Pairing yielded invalid credential");
      return new Poll("paired",bearer);
    } catch(IOException expected){throw expected;}
    catch(Exception failure){throw new IOException("Invalid pairing response");}
  }
}
