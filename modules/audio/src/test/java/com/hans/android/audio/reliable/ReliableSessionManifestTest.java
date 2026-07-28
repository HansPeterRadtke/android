package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReliableSessionManifestTest {
    @Test public void canonicalCommitIsStableAndOrdered() {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "session-1";
        value.conversationId = "conversation-1";
        value.finishReason = "normal";
        value.totalDurationMs = 5000L;
        ReliableSessionManifest.Segment second = new ReliableSessionManifest.Segment();
        second.seq = 1; second.mp3Bytes = 20; second.durationMs = 3000;
        second.startSample = 32000; second.endSample = 80000; second.sha256 = repeat('b');
        ReliableSessionManifest.Segment first = new ReliableSessionManifest.Segment();
        first.seq = 0; first.mp3Bytes = 10; first.durationMs = 2000;
        first.startSample = 0; first.endSample = 32000; first.sha256 = repeat('a');
        value.segments.add(second); value.segments.add(first);
        String json = value.canonicalCommitJson();
        assertTrue(json.indexOf("\"seq\":0") < json.indexOf("\"seq\":1"));
        assertEquals(64, value.commitSha256().length());
        assertEquals("f5e1c2755df6929ee88332ab7245a1607733c22963bf68d3879e67498bec766e", value.commitSha256());
    }

    @Test public void pausedOpenRecordingIsIntentionalNotInterrupted() throws Exception {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "session-pause";
        value.conversationId = "conversation-pause";
        value.paused = true;
        value.recordingFinished = false;
        value.totalSegmentBytes = 12345L;
        ReliableSessionManifest restored = ReliableSessionManifest.fromJson(value.toJson());
        assertTrue(restored.paused);
        assertTrue(restored.isOpen());
        org.junit.Assert.assertFalse(restored.isInterrupted());
        assertEquals(12345L, restored.totalSegmentBytes);
    }

    @Test public void emptyUnfinishedSessionIsDiscardable() {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "empty-session";
        value.conversationId = "conversation";
        org.junit.Assert.assertTrue(value.isDiscardableEmptySession());
        ReliableSessionManifest.Segment segment = new ReliableSessionManifest.Segment();
        segment.seq = 0;
        segment.mp3Bytes = 100L;
        value.segments.add(segment);
        org.junit.Assert.assertFalse(value.isDiscardableEmptySession());
    }

    @Test public void onlyExplicitInterruptedStateTriggersRecovery() {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "state-test";
        value.conversationId = "conversation";
        value.recordingFinished = false;
        value.paused = false;
        value.state = "RECORDING";
        org.junit.Assert.assertFalse(value.isInterrupted());
        value.state = "INTERRUPTED";
        org.junit.Assert.assertTrue(value.isInterrupted());
        value.paused = true;
        org.junit.Assert.assertFalse(value.isInterrupted());
    }

    @Test public void chunkLedgerAndTranscriptSurviveRoundTrip() throws Exception {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "ledger-session";
        value.conversationId = "ledger-conversation";
        value.folderId = "field-notes";
        value.folderName = "Field Notes";
        ReliableSessionManifest.Segment chunk = new ReliableSessionManifest.Segment();
        chunk.seq = 4; chunk.startSample = 128000; chunk.endSample = 160000;
        chunk.createdAtMs = 10; chunk.closedAtMs = 20; chunk.localDurableAtMs = 30;
        chunk.sendAttempts = 3; chunk.firstSendAtMs = 40; chunk.lastSendAtMs = 50;
        chunk.remoteAccepted = true; chunk.remoteServerId = "jetson-primary";
        chunk.remoteManifestRevision = 7; chunk.remoteReceivedAtMs = 60;
        chunk.remoteDurableAtMs = 70; chunk.transcriptState = "COMPLETE";
        chunk.transcriptText = "hello world"; chunk.transcriptEngine = "vosk";
        value.segments.add(chunk);
        ReliableSessionManifest restored = ReliableSessionManifest.fromJson(value.toJson());
        assertEquals("field-notes", restored.folderId);
        ReliableSessionManifest.Segment restoredChunk = restored.findSegment(4);
        assertTrue(restoredChunk.remoteAccepted);
        assertEquals(3, restoredChunk.sendAttempts);
        assertEquals(70L, restoredChunk.remoteDurableAtMs);
        assertEquals("hello world", restoredChunk.transcriptText);
    }

    @Test public void durablePcmJournalPreventsEmptySessionDeletion() {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "pcm-session";
        ReliableSessionManifest.Segment chunk = new ReliableSessionManifest.Segment();
        chunk.seq = 0; chunk.pcmJournalName = "segment_000000_16000.open.pcm";
        chunk.pcmBytes = 3200;
        value.segments.add(chunk);
        org.junit.Assert.assertFalse(value.isDiscardableEmptySession());
    }

    @Test public void automaticResumeIntentSurvivesUnexpectedInterruption() throws Exception {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "auto-resume-session";
        value.conversationId = "conversation";
        value.state = "INTERRUPTED";
        value.autoResumeRequested = true;
        ReliableSessionManifest restored = ReliableSessionManifest.fromJson(value.toJson());
        assertTrue(restored.autoResumeRequested);
        assertTrue(restored.isInterrupted());
    }

    private static String repeat(char value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 64; i++) out.append(value);
        return out.toString();
    }
}
