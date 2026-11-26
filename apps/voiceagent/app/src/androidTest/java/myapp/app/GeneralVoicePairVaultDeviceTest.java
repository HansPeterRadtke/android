package myapp.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GeneralVoicePairVaultDeviceTest {
  @Test public void storesOnlyEncryptedPendingProofAndSurvivesReload() {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    GeneralVoicePairVault.clear(context);
    String id="0123456789abcdef0123456789abcdef";
    String proof="f".repeat(64);
    GeneralVoicePairing.Session session=new GeneralVoicePairing.Session(
        id,proof,System.currentTimeMillis()+240000);
    assertTrue(GeneralVoicePairVault.save(context,session));
    String blob=context.getSharedPreferences("voice_client_pending_pair_v1",Context.MODE_PRIVATE)
        .getString("pair_state","");
    assertFalse(blob.contains(proof));
    assertFalse(blob.contains(id));
    GeneralVoicePairing.Session loaded=GeneralVoicePairVault.load(context);
    assertNotNull(loaded);
    assertEquals(id,loaded.pairId);
    assertEquals(proof,loaded.secret);
    GeneralVoicePairVault.clear(context);
    assertNull(GeneralVoicePairVault.load(context));
  }
  @Test public void expiredPendingRequestsCannotBeRestored() {
    Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    GeneralVoicePairVault.clear(context);
    assertFalse(GeneralVoicePairVault.save(context,new GeneralVoicePairing.Session(
        "0123456789abcdef0123456789abcdef","a".repeat(64),
        System.currentTimeMillis()-1000)));
    assertNull(GeneralVoicePairVault.load(context));
  }
}
