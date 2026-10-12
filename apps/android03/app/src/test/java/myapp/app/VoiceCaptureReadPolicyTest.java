package myapp.app;
import static org.junit.Assert.*;
import org.junit.Test;
public class VoiceCaptureReadPolicyTest {
  @Test public void zeroReadHasBoundedGraceAndResetsOnSuccess() {
    VoiceCaptureReadPolicy p=new VoiceCaptureReadPolicy(3000);
    p.observe(0,1000);p.observe(0,3999);p.observe(12,4000);
    p.observe(0,8000);p.observe(0,10999);
    try{p.observe(0,11000);fail();}catch(IllegalStateException expected){}
  }
  @Test public void negativeReadFailsImmediately() {
    VoiceCaptureReadPolicy p=new VoiceCaptureReadPolicy(3000);
    try{p.observe(-3,1500);fail();}catch(IllegalStateException expected){}
  }
  @Test public void constructorBounds() {
    for (int value:new int[]{-1,0,99,60001}) {
      try{new VoiceCaptureReadPolicy(value);fail();}catch(IllegalArgumentException expected){}
    }
  }
}
