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
        assertEquals(1, OverviewProgress.uploadFilesRemaining(
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
        assertFalse(OverviewProgress.hasUnmeasuredUpload(Arrays.asList(value)));
    }

    @Test public void overallCountsActualServerDurableBytesIncludingLiveRecording() {
        ReliableSessionManifest done = session("done", true, true, true, 120L, 120L);
        ReliableSessionManifest open = session("live", false, false, false, 100L, 35L);
        OverviewProgress.UploadEvidence data = OverviewProgress.uploadEvidence(
                Arrays.asList(done, open), "upload_chunk", "live", 0, 60L, 100L);
        assertEquals(220L, data.totalBytes);
        assertEquals(180L, data.durableBytes);
        assertEquals(2, data.totalChunks);
        assertEquals(1, data.durableChunks);
        assertEquals(0, data.finishedPending);
        assertEquals(1, data.uncommittedSessions);
        assertTrue(data.hasOpenRecording);
        assertEquals(818, data.progressPermille());
        assertEquals(600, OverviewProgress.fileProgressPermille(open,
                "upload_chunk", "live", 0, 60L, 100L));
    }

    @Test public void idleOrDifferentChunkCannotInflateConfirmedProgress() {
        ReliableSessionManifest pending = session("one", true, true, false, 200L, 30L);
        OverviewProgress.UploadEvidence idle = OverviewProgress.uploadEvidence(
                Arrays.asList(pending), "idle", "one", 0, 200L, 200L);
        OverviewProgress.UploadEvidence wrongSession = OverviewProgress.uploadEvidence(
                Arrays.asList(pending), "upload_chunk", "different", 0, 200L, 200L);
        OverviewProgress.UploadEvidence wrongSize = OverviewProgress.uploadEvidence(
                Arrays.asList(pending), "upload_chunk", "one", 0, 200L, 201L);
        assertEquals(30L, idle.durableBytes);
        assertEquals(30L, wrongSession.durableBytes);
        assertEquals(30L, wrongSize.durableBytes);
        assertEquals(150, wrongSize.progressPermille());
    }

    @Test public void localEncodingIsUnknownButOpenPcmDoesNotInventBytes() {
        ReliableSessionManifest converting = session("converting", true, false, false, 0L, 0L);
        ReliableSessionManifest live = session("recording", false, false, false, 0L, 0L);
        OverviewProgress.UploadEvidence evidence = OverviewProgress.uploadEvidence(
                Arrays.asList(converting, live), "local_finalizing", "converting", -1, 0L, 0L);
        assertEquals(0, evidence.progressPermille());
        assertEquals(0L, evidence.totalBytes);
        assertTrue(evidence.unfinishedEncoding);
        assertTrue(evidence.hasOpenRecording);
        assertEquals(-1, OverviewProgress.fileProgressPermille(converting,
                "idle", "", -1, 0L, 0L));
    }

    @Test public void completedRecordingKeepsOneHundredPercentAfterCommit() {
        ReliableSessionManifest completed = session("committed", true, true, true, 100L, 0L);
        OverviewProgress.UploadEvidence evidence = OverviewProgress.uploadEvidence(
                Arrays.asList(completed), "idle", "", -1, 0L, 0L);
        assertEquals(100L, evidence.durableBytes);
        assertEquals(1000, evidence.progressPermille());
        assertEquals(0, evidence.finishedPending);
        assertEquals(0, evidence.uncommittedSessions);
        assertEquals(1000, OverviewProgress.fileProgressPermille(completed,
                "idle", "", -1, 0L, 0L));
    }

    @Test public void visibleFileTracksPendingUploadRatherThanStaleWorker() {
        ReliableSessionManifest done = session("done", true, true, true, 100L, 100L);
        ReliableSessionManifest first = session("first", true, true, false, 100L, 10L);
        ReliableSessionManifest next = session("next", true, true, false, 100L, 0L);
        first.createdAt = 1L; next.createdAt = 2L;
        assertEquals("first", OverviewProgress.visibleUpload(
                Arrays.asList(done, first, next), "done").sessionId);
        assertEquals("next", OverviewProgress.visibleUpload(
                Arrays.asList(done, first, next), "next").sessionId);
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
        assertFalse(OverviewProgress.hasUnmeasuredUpload(Arrays.asList(open)));
        assertEquals(0, OverviewProgress.uploadFilesRemaining(Arrays.asList(open)));
    }
    @Test public void serverCommittedSessionIsRemovedFromPendingUploads() {
        ReliableSessionManifest stale = session("stale", true, true, false, 100L, 98L);
        ReliableSessionManifest pending = session("pending", true, true, false, 100L, 40L);
        java.util.Set<String> committed = new java.util.HashSet<>();
        committed.add("stale");
        java.util.List<ReliableSessionManifest> result = OverviewProgress.pendingUploads(
                Arrays.asList(stale, pending), committed);
        assertEquals(1, result.size());
        assertEquals("pending", result.get(0).sessionId);
    }

}
