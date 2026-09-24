package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hans.android.audio.reliable.ReliableSessionManifest;

import org.junit.Test;

import java.util.Arrays;

public class OverviewProgressTest {
    @Test public void countsFilesStillNeedingUpload() {
        ReliableSessionManifest done = session("done", true, true, true, 100L, 100L);
        ReliableSessionManifest pending = session("pending", true, true, false, 100L, 40L);
        ReliableSessionManifest open = session("open", false, false, false, 0L, 0L);
        assertEquals(2, OverviewProgress.uploadFilesRemaining(
                Arrays.asList(done, pending, open)));
    }

    @Test public void currentFileProgressUsesWholeRecordingNotOneChunk() {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "s";
        value.displayName = "Test recording";
        value.createdAt = 1L;
        ReliableSessionManifest.Segment first = new ReliableSessionManifest.Segment();
        first.seq = 0; first.mp3Bytes = 100L; first.remoteAccepted = true;
        ReliableSessionManifest.Segment second = new ReliableSessionManifest.Segment();
        second.seq = 1; second.mp3Bytes = 100L; second.remotePartialBytes = 50L;
        value.segments.add(first); value.segments.add(second);
        assertEquals(750, OverviewProgress.fileProgressPermille(value));
        assertEquals("Test recording.mp3", OverviewProgress.fileName(value));
    }

    @Test public void unencodedSegmentMakesCurrentFileProgressUnknown() {
        ReliableSessionManifest value = new ReliableSessionManifest();
        ReliableSessionManifest.Segment segment = new ReliableSessionManifest.Segment();
        segment.seq = 0; segment.mp3Bytes = 0L; segment.remoteAccepted = false;
        value.segments.add(segment);
        assertEquals(-1, OverviewProgress.fileProgressPermille(value));
        assertTrue(OverviewProgress.hasUnmeasuredUpload(Arrays.asList(value)));
    }

    private static ReliableSessionManifest session(String id, boolean finished,
            boolean converted, boolean committed, long total, long durable) {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = id; value.recordingFinished = finished;
        value.conversionFinished = converted; value.remoteCommitted = committed;
        ReliableSessionManifest.Segment segment = new ReliableSessionManifest.Segment();
        segment.seq = 0; segment.mp3Bytes = total;
        segment.remoteAccepted = total > 0L && durable >= total;
        segment.remotePartialBytes = durable;
        value.segments.add(segment);
        return value;
    }

    @Test public void openRecordingMakesOverallPercentageKnownBytesOnly() {
        ReliableSessionManifest open = new ReliableSessionManifest();
        open.sessionId = "open";
        open.recordingFinished = false;
        open.conversionFinished = false;
        open.remoteCommitted = false;
        ReliableSessionManifest.Segment segment = new ReliableSessionManifest.Segment();
        segment.seq = 0;
        segment.pcmJournalName = "segment_000000_48000.pcm";
        segment.pcmBytes = 96000L;
        segment.mp3Bytes = 0L;
        open.segments.add(segment);
        assertTrue(OverviewProgress.hasUnmeasuredUpload(Arrays.asList(open)));
        assertEquals(1, OverviewProgress.uploadFilesRemaining(Arrays.asList(open)));
    }
}
