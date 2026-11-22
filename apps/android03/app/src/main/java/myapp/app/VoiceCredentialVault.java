package myapp.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-local private voice bearer using a non-exportable Android Keystore key.
 *
 * The ciphertext is stored only in app-private preferences and never bundled
 * in an APK, placed in a URL, printed in logs, or included in saved histories.
 * An unreadable/modified/foreign-device ciphertext always fails closed.
 * The bearer proves device possession only, never speaker identity.
 */
final class VoiceCredentialVault {
  private static final String KEYSTORE = "AndroidKeyStore";
  private static final String ALIAS = "myapp.app.private.voice.wss.aesgcm.v1";
  private static final String PREF_FILE = "private_voice_connection_v1";
  private static final String CIPHERTEXT_PREF = "protected_bearer_ciphertext_v1";
  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int IV_LENGTH = 12;

  private VoiceCredentialVault() {}

  private static SharedPreferences preferences(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
  }

  private static byte[] aad(Context context) {
    return (context.getPackageName() + ":" + CIPHERTEXT_PREF).getBytes(StandardCharsets.UTF_8);
  }

  private static KeyStore keyStore() throws Exception {
    KeyStore store = KeyStore.getInstance(KEYSTORE);
    store.load(null);
    return store;
  }

  private static SecretKey requireKey() throws Exception {
    KeyStore store = keyStore();
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
      generator.init(new KeyGenParameterSpec.Builder(ALIAS,
          KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setRandomizedEncryptionRequired(true)
          .setKeySize(256)
          .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }

  static synchronized boolean save(Context context, String token) {
    if (!VoiceWebSocketAuth.configured(token)) return false;
    try {
      SecretKey key = requireKey();
      Cipher cipher = Cipher.getInstance(TRANSFORM);
      cipher.init(Cipher.ENCRYPT_MODE, key);
      cipher.updateAAD(aad(context));
      byte[] iv = cipher.getIV();
      if (iv == null || iv.length != IV_LENGTH) return false;
      byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
      byte[] output = new byte[IV_LENGTH + ciphertext.length];
      System.arraycopy(iv, 0, output, 0, IV_LENGTH);
      System.arraycopy(ciphertext, 0, output, IV_LENGTH, ciphertext.length);
      String encoded = Base64.encodeToString(output, Base64.NO_WRAP);
      Arrays.fill(ciphertext, (byte)0);
      Arrays.fill(output, (byte)0);
      return preferences(context).edit().putString(CIPHERTEXT_PREF, encoded).commit();
    } catch (Exception ignored) {
      // Do not accidentally leak credential bytes through failure messages.
      return false;
    }
  }

  static synchronized String read(Context context) {
    String encoded = preferences(context).getString(CIPHERTEXT_PREF, null);
    if (encoded == null || encoded.isEmpty()) return "";
    try {
      byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
      if (blob.length < IV_LENGTH + 16 + 1 || blob.length > IV_LENGTH + 512 + 16)
        return "";
      KeyStore store = keyStore();
      SecretKey key = (SecretKey) store.getKey(ALIAS, null);
      if (key == null) return "";
      byte[] iv = Arrays.copyOfRange(blob, 0, IV_LENGTH);
      Cipher cipher = Cipher.getInstance(TRANSFORM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      cipher.updateAAD(aad(context));
      byte[] clear = cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
      String token = new String(clear, StandardCharsets.UTF_8);
      Arrays.fill(clear, (byte)0);
      Arrays.fill(blob, (byte)0);
      return VoiceWebSocketAuth.configured(token) ? token : "";
    } catch (Exception ignored) {
      // Reinstall, restore, decryption failure, or tampering never authorizes a call.
      return "";
    }
  }

  static boolean configured(Context context) {
    return VoiceWebSocketAuth.configured(read(context));
  }

  static synchronized boolean clear(Context context) {
    try {
      if (!preferences(context).edit().remove(CIPHERTEXT_PREF).commit()) return false;
      KeyStore store = keyStore();
      if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

}
