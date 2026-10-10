package com.hans.android.audio.reliable;
import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;
public class JournalCompletenessTest {
 @Test public void exactSamplesAndBytesAreRequired() throws Exception {
  JournaledMp3Recorder.requireCompleteJournal(0, 0, 0);
  JournaledMp3Recorder.requireCompleteJournal(48000, 48000, 96000);
  for (long[] invalid : new long[][] {
    {100,99,200},{100,100,198},{100,100,202},
    {-1,-1,0},{Long.MAX_VALUE,Long.MAX_VALUE,0}}) {
   try {
    JournaledMp3Recorder.requireCompleteJournal(
      invalid[0],invalid[1],invalid[2]);
    fail("Accepted incomplete microphone data");
   } catch (IOException expected) {
    assertTrue(expected.getMessage().contains("incomplete"));
   }
  }
 }
}
