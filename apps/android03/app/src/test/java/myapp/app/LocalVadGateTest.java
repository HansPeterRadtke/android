package myapp.app;
import static org.junit.Assert.*;
import org.junit.Test;
public class LocalVadGateTest {
 @Test public void sustainedSpeechStartsHint(){ LocalVadGate g=new LocalVadGate(60); byte[] f=new byte[]{1,2}; for(int i=0;i<20;i++) assertFalse(g.accept(f,-80).started); LocalVadGate.Result r=null; for(int i=0;i<3;i++) r=g.accept(f,-20); assertTrue(r.started); assertTrue(g.active()); }
 @Test public void sustainedSilenceStopsHint(){ LocalVadGate g=new LocalVadGate(60); byte[] f=new byte[]{1,2}; for(int i=0;i<3;i++) g.accept(f,-20); LocalVadGate.Result r=null; for(int i=0;i<10;i++) r=g.accept(f,-80); assertFalse(g.active()); assertTrue(r.stopped); }
}
