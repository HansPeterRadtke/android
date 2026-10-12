package myapp.app;
import java.nio.ByteBuffer;
import java.util.Arrays;
/** Versioned PCM frame: magic, immutable media id, absolute byte offset, PCM16. */
final class VoiceMediaFrame {
  static final class Decoded {
    final String streamId;final long offset;final byte[] pcm;
    Decoded(String id,long position,byte[] data) {streamId=id;offset=position;pcm=data;}
  }
  static Decoded decode(byte[] data) {
    if(data==null || data.length<30 || (data.length&1)!=0 || data[0]!='V'
        || data[1]!='A' || data[2]!='P' || data[3]!='1')
      throw new IllegalArgumentException("invalid media frame");
    StringBuilder id=new StringBuilder(32);
    final char[] hex="0123456789abcdef".toCharArray();
    for(int i=4;i<20;i++) {id.append(hex[(data[i]>>4)&15]);id.append(hex[data[i]&15]);}
    long offset=ByteBuffer.wrap(data,20,8).getLong();
    if(offset<0 || (offset&1)!=0)throw new IllegalArgumentException("invalid media offset");
    return new Decoded(id.toString(),offset,Arrays.copyOfRange(data,28,data.length));
  }
}
