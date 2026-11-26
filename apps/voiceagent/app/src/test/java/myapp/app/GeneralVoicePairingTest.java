package myapp.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeneralVoicePairingTest {
  @Test public void approvalLinkIncludesOnlyPublicId() {
    String id="0123456789abcdef0123456789abcdef";
    assertEquals("https://raspi.jonnyontherun.org/explorer/voice-agent/pair/approve?id="+id,
        GeneralVoicePairing.approvalUrl(id));
    for(String wrong:new String[]{null,"","../../secret","123",id+"&secret=x","ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"}) {
      try {GeneralVoicePairing.approvalUrl(wrong);fail(String.valueOf(wrong));}
      catch(IllegalArgumentException expected){}
    }
  }
  @Test public void privateProofIsHashedNotPublishedToBrowser() {
    byte[] bytes=new byte[32];
    for(int i=0;i<32;i++)bytes[i]=(byte)i;
    String secret=GeneralVoicePairing.hex(bytes);
    assertTrue(GeneralVoicePairing.validProof(secret));
    String digest=GeneralVoicePairing.hashProof(secret);
    assertEquals("630dcd2966c4336691125448bbb25b4ff412a49c732db2c8abc1b8581bd710dd",digest);
    assertNotEquals(secret,digest);
    assertFalse(GeneralVoicePairing.APPROVAL.contains(secret));
  }
  @Test public void pairingSessionHasValidPrivateState() {
    GeneralVoicePairing.Session session=new GeneralVoicePairing.Session(
        "0123456789abcdef0123456789abcdef", "a".repeat(64), System.currentTimeMillis()+50000);
    assertTrue(GeneralVoicePairing.validId(session.pairId));
    assertTrue(GeneralVoicePairing.validProof(session.secret));
  }
}
