package myapp.app;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
public class VoiceCaptureJournalTest {
  @Rule public TemporaryFolder temp=new TemporaryFolder();
  @Test public void acceptedFramesSurviveReopenWithoutStopCallback() throws Exception {
    File root=temp.newFolder();byte[] pcm={1,2,3,4};
    try(VoiceCaptureJournal journal=new VoiceCaptureJournal(root,1024,64,null)) {
      journal.append(pcm);
      assertArrayEquals(pcm,Files.readAllBytes(journal.currentFile().toPath()));
    }
  }
  @Test public void quotaFailureRetainsAllAcceptedFrames() throws Exception {
    File root=temp.newFolder();byte[] pcm=new byte[64];Arrays.fill(pcm,(byte)7);
    try(VoiceCaptureJournal journal=new VoiceCaptureJournal(root,64,64,null)) {
      journal.append(pcm);
      try {journal.append(new byte[2]);fail("quota must reject");}catch(IOException expected){}
      assertArrayEquals(pcm,Files.readAllBytes(journal.currentFile().toPath()));
    }
  }
  @Test public void rotationNeverOverwritesEarlierRecording() throws Exception {
    File root=temp.newFolder();
    try(VoiceCaptureJournal journal=new VoiceCaptureJournal(root,128,4,null)) {
      journal.append(new byte[]{1,2,3,4});File first=journal.currentFile();
      journal.append(new byte[]{5,6,7,8});
      assertNotEquals(first,journal.currentFile());
      assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(first.toPath()));
    }
  }
  @Test public void newManualRecordingArchivesPreviousUnsentAudio() throws Exception {
    File root=temp.newFolder();File saved=temp.newFile();Files.write(saved.toPath(),new byte[]{1,2});
    try(VoiceCaptureJournal journal=new VoiceCaptureJournal(root,128,64,saved)) {
      journal.append(new byte[]{3,4});
      assertArrayEquals(new byte[]{1,2},Files.readAllBytes(root.listFiles()[0].toPath()));
      assertArrayEquals(new byte[]{3,4},Files.readAllBytes(saved.toPath()));
    }
  }
}
