package myapp.app;
import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;
public class VoiceMediaFrameTest {
  @Test public void decodesIdentityOffsetAndUnmodifiedPcm() {
    byte[] frame=ByteBuffer.allocate(32).put(new byte[]{'V','A','P','1'})
        .putLong(1).putLong(2).putLong(3200).put(new byte[]{1,2,3,4}).array();
    VoiceMediaFrame.Decoded value=VoiceMediaFrame.decode(frame);
    assertEquals("00000000000000010000000000000002",value.streamId);
    assertEquals(3200,value.offset);assertArrayEquals(new byte[]{1,2,3,4},value.pcm);
  }
  @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedHeader() {
    VoiceMediaFrame.decode(new byte[20]);
  }
  @Test(expected=IllegalArgumentException.class) public void rejectsOddPcm() {
    VoiceMediaFrame.decode(new byte[31]);
  }
}
