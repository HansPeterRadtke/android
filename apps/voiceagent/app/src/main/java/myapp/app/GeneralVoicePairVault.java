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

/** Restart-resilient, temporary pairing state protected by Android Keystore.
 * Not backed up, never available to other apps or the browser. */
final class GeneralVoicePairVault {
  private static final String KEYSTORE = "AndroidKeyStore";
  private static final String ALIAS = "com.multiverse.voiceclient.pairing.v1";
  private static final String PREF = "voice_client_pending_pair_v1";
  private static final String FIELD = "pair_state";
  private static final int IV_BYTES = 12;
  private GeneralVoicePairVault() {}

  private static SharedPreferences prefs(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
  }
  private static SecretKey requireKey() throws Exception {
    KeyStore store=KeyStore.getInstance(KEYSTORE);
    store.load(null);
    if(!store.containsAlias(ALIAS)) {
      KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,KEYSTORE);
      gen.init(new KeyGenParameterSpec.Builder(ALIAS,
          KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setRandomizedEncryptionRequired(true).setKeySize(256).build());
      gen.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS,null);
  }
  static synchronized boolean save(Context context,GeneralVoicePairing.Session session) {
    if(session==null||System.currentTimeMillis()>=session.expiresAt)return false;
    byte[] clear=(session.pairId+":"+session.secret+":"+session.expiresAt)
        .getBytes(StandardCharsets.US_ASCII);
    try {
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,requireKey());
      cipher.updateAAD(context.getPackageName().getBytes(StandardCharsets.UTF_8));
      byte[] iv=cipher.getIV(), cipherText=cipher.doFinal(clear);
      byte[] blob=new byte[iv.length+cipherText.length];
      System.arraycopy(iv,0,blob,0,iv.length);
      System.arraycopy(cipherText,0,blob,iv.length,cipherText.length);
      boolean success=prefs(context).edit().putString(FIELD,
          Base64.encodeToString(blob,Base64.NO_WRAP)).commit();
      Arrays.fill(blob,(byte)0);Arrays.fill(cipherText,(byte)0);
      return success;
    }catch(Exception ignored){return false;}
    finally{Arrays.fill(clear,(byte)0);}
  }
  static synchronized GeneralVoicePairing.Session load(Context context) {
    String encoded=prefs(context).getString(FIELD,"");
    if(encoded==null||encoded.isEmpty())return null;
    byte[] blob=null, clear=null;
    try {
      blob=Base64.decode(encoded,Base64.NO_WRAP);
      if(blob.length<IV_BYTES+16+1 || blob.length>512)return null;
      Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,requireKey(),
          new GCMParameterSpec(128,blob,0,IV_BYTES));
      cipher.updateAAD(context.getPackageName().getBytes(StandardCharsets.UTF_8));
      clear=cipher.doFinal(blob,IV_BYTES,blob.length-IV_BYTES);
      String[] components=new String(clear,StandardCharsets.US_ASCII).split(":",-1);
      if(components.length!=3)return null;
      GeneralVoicePairing.Session session=new GeneralVoicePairing.Session(
          components[0],components[1],Long.parseLong(components[2]));
      if(System.currentTimeMillis()>=session.expiresAt){clear(context);return null;}
      return session;
    }catch(Exception ignored){return null;}
    finally{
      if(blob!=null)Arrays.fill(blob,(byte)0);
      if(clear!=null)Arrays.fill(clear,(byte)0);
    }
  }
  static synchronized void clear(Context context) {
    prefs(context).edit().remove(FIELD).commit();
  }
}
