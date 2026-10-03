package com.hans.android.voicebutton;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.File;
import static org.junit.Assert.*;
public class BoundedLogFileTest {
    @Test public void errorFloodStaysBoundedAndRetainsCompleteRecentRecords()throws Exception{
        File root=Files.createTempDirectory("bounded-log").toFile(),file=new File(root,"errors.jsonl");
        try{for(int i=0;i<500;i++){byte[] record=("{\"level\":\"ERROR\",\"event\":"+i+"}\n").getBytes(StandardCharsets.UTF_8);BoundedLogFile.append(file,record,1024,true);assertTrue(file.length()<=1024);}
            String retained=new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);assertTrue(retained.endsWith("\"event\":499}\n"));for(String line:retained.split("\n"))assertTrue(line.startsWith("{")&&line.endsWith("}"));
        }finally{file.delete();root.delete();}
    }
    @Test public void oversizedEntryLeavesExistingLogUntouched()throws Exception{
        File file=File.createTempFile("bounded-log",".jsonl");try{Files.write(file.toPath(),"original\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));try{BoundedLogFile.append(file,new byte[1025],1024,true);fail("Oversized record accepted");}catch(java.io.IOException expected){}assertEquals("original\n",new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));}finally{file.delete();}
    }
}
