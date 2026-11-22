package myapp.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.security.KeyStore;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Actual AndroidKeyStore tests; only an isolated offline/no-action emulator. */
@RunWith(AndroidJUnit4.class)
public class VoiceCredentialVaultTest {
  private static final String TOKEN = "test-only-keystore-never-real-server-20261122";
  private static final String PREFS = "private_voice_connection_v1";
  private static final String KEY = "protected_bearer_ciphertext_v1";

  private Context device() {
    Bundle args = InstrumentationRegistry.getArguments();
    Assume.assumeTrue("Only a fresh, isolated and offline no-action Android emulator",
        "1".equals(args.getString("offlineCredentialVaultAcceptance")));
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  @Test public void keyIsNonExportableAndCiphertextNeverContainsTheBearer() throws Exception {
    Context ctx = device();
    assertTrue(VoiceCredentialVault.clear(ctx));
    try {
      assertEquals("", VoiceCredentialVault.read(ctx));
      assertTrue(VoiceCredentialVault.save(ctx,TOKEN));
      assertEquals(TOKEN,VoiceCredentialVault.read(ctx));
      assertTrue(VoiceCredentialVault.configured(ctx));
      SharedPreferences prefs = ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
      String sealed = prefs.getString(KEY,"");
      assertFalse(sealed.isEmpty());
      assertFalse(sealed.contains(TOKEN));
      assertFalse(sealed.contains("test-only-keystore"));
      assertEquals(1,prefs.getAll().size());
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      java.security.Key key = store.getKey("myapp.app.private.voice.wss.aesgcm.v1",null);
      assertNotNull(key);
      assertNull("AndroidKeyStore key material must not be extractable",key.getEncoded());
    } finally {
      assertTrue(VoiceCredentialVault.clear(ctx));
    }
    assertEquals("", VoiceCredentialVault.read(ctx));
  }

  @Test public void freshSaveRotatesCiphertextAndUnreadableDataFailsClosed() {
    Context ctx = device();
    assertTrue(VoiceCredentialVault.clear(ctx));
    try {
      assertTrue(VoiceCredentialVault.save(ctx,TOKEN));
      String first = ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
      assertTrue(VoiceCredentialVault.save(ctx,TOKEN));
      String second = ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
      assertNotEquals("AES-GCM IV must be randomized on every save",first,second);
      assertEquals(TOKEN,VoiceCredentialVault.read(ctx));
      assertTrue(ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
          .putString(KEY,"!!tampered-not-base64!!").commit());
      assertEquals("A tampered ciphertext never authorizes voice access","",VoiceCredentialVault.read(ctx));
      assertFalse(VoiceCredentialVault.configured(ctx));
      assertTrue(VoiceCredentialVault.save(ctx,TOKEN));
      assertEquals(TOKEN,VoiceCredentialVault.read(ctx));
    } finally {
      assertTrue(VoiceCredentialVault.clear(ctx));
    }
  }

  @Test public void noCredentialCanBeManufacturedFromInvalidInputOrApkBuildConstant() {
    Context ctx = device();
    assertTrue(VoiceCredentialVault.clear(ctx));
    assertFalse(VoiceCredentialVault.save(ctx,""));
    assertFalse(VoiceCredentialVault.save(ctx," space"));
    assertFalse(VoiceCredentialVault.save(ctx,"a\r\nb"));
    assertEquals("",VoiceCredentialVault.read(ctx));
    for (java.lang.reflect.Field field : BuildConfig.class.getDeclaredFields()) {
      assertNotEquals("The distributed APK must never embed the bearer field",
          "VOICE_AGENT_AUTH_TOKEN", field.getName());
    }
  }
}
