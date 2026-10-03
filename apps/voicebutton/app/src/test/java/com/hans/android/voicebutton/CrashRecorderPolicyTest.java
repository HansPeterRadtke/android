package com.hans.android.voicebutton;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class CrashRecorderPolicyTest {
    @Test public void preservesUsefulSmallReport(){String report="IOException: microphone disconnected\n at recorder.read";assertEquals(report,new String(CrashRecorder.boundedReportBytes(report),StandardCharsets.UTF_8));}
    @Test public void oversizedReportRetainsValidUtf8(){String report="Microphone failure: "+"€".repeat(100000);byte[] bytes=CrashRecorder.boundedReportBytes(report);assertTrue(bytes.length<=128*1024);String retained=new String(bytes,StandardCharsets.UTF_8);assertTrue(retained.startsWith("Microphone failure: "));assertFalse(retained.contains("\ufffd"));assertTrue(report.startsWith(retained));}
}
