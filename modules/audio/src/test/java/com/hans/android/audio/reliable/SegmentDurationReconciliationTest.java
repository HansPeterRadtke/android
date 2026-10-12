package com.hans.android.audio.reliable;

import static org.junit.Assert.*;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;

public class SegmentDurationReconciliationTest {
    @Test public void inflatedLegacyDurationIsCorrectedFromRealMp3() throws Exception {
        File source = File.createTempFile("old-metadata-", ".mp3");
        try {
            byte[] frame = new byte[384];
            frame[0]=(byte)0xff;frame[1]=(byte)0xfb;frame[2]=(byte)0x94;
            try (FileOutputStream out=new FileOutputStream(source)) {
                for (int i=0;i<500;i++)out.write(frame);
            }
            ReliableSessionManifest.Segment segment =
                    new ReliableSessionManifest.Segment();
            segment.startSample=0;segment.endSample=500*1152;
            segment.sampleRate=48000;
            segment.durationMs=36000; // Historical incorrect 3x for 12 s.
            assertTrue(SegmentDurationReconciliation.repairIfInflated(
                    segment,source));
            assertEquals(12000L,segment.durationMs);
            assertFalse(SegmentDurationReconciliation.repairIfInflated(
                    segment,source));
        } finally {
            source.delete();
        }
    }

    @Test public void correctCaptureDurationDoesNotMisinterpretEncoderPadding()
            throws Exception {
        ReliableSessionManifest.Segment segment =
                new ReliableSessionManifest.Segment();
        segment.startSample=0;segment.endSample=48000*6;
        segment.sampleRate=48000;segment.durationMs=6000;
        assertFalse(SegmentDurationReconciliation.repairIfInflated(
                segment,null));
        assertEquals(6000L,segment.durationMs);
    }

    @Test public void missingLocalMp3StillAllowsExactSampleBasedCorrection()
            throws Exception {
        ReliableSessionManifest.Segment segment =
                new ReliableSessionManifest.Segment();
        segment.startSample=0;segment.endSample=44100*7;
        segment.sampleRate=44100;segment.durationMs=21000L;
        assertTrue(SegmentDurationReconciliation.repairIfInflated(segment,null));
        assertEquals(7000L,segment.durationMs);
    }

    @Test public void unknownSampleRateNeverInventsADuration() throws Exception {
        ReliableSessionManifest.Segment segment =
                new ReliableSessionManifest.Segment();
        segment.durationMs=9000;segment.sampleRate=0;
        assertFalse(SegmentDurationReconciliation.repairIfInflated(
                segment,null));
        assertEquals(9000L,segment.durationMs);
    }
}
