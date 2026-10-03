package com.hans.android.network.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ReliableUploadClientTest {
    @Test public void clampsAdaptiveDurableParts() {
        assertEquals(4096, ReliableUploadClient.MIN_UPLOAD_PART_BYTES);
        assertEquals(65536, ReliableUploadClient.INITIAL_UPLOAD_PART_BYTES);
        assertEquals(1024 * 1024, ReliableUploadClient.MAX_UPLOAD_PART_BYTES);
        assertEquals(65536,
                ReliableUploadClient.nextPartLength(0L, 1_000_000L, 65536));
        assertEquals(848,
                ReliableUploadClient.nextPartLength(48304L, 49152L, 65536));
        assertEquals(0,
                ReliableUploadClient.nextPartLength(49152L, 49152L, 65536));
    }

    @Test public void uploadRequestsHaveBoundedMobileDeadlines() {
        assertEquals(2_500, ReliableUploadClient.CONNECT_TIMEOUT_MS);
        assertEquals(6_000, ReliableUploadClient.READ_TIMEOUT_MS);
    }

    @Test public void parsesRetryAfterSeconds() {
        assertEquals(120_000L, ReliableUploadClient.parseRetryAfterMs("120"));
        assertEquals(0L, ReliableUploadClient.parseRetryAfterMs("invalid"));
    }

    @Test public void parsesCompactDurableStatusAndCanonicalFinalTranscript()
            throws Exception {
        JSONObject response = new JSONObject();
        response.put("committed", true);
        response.put("server_id", "jetson");
        response.put("manifest_revision", 17L);
        JSONArray received = new JSONArray();
        received.put(new JSONArray().put(0).put(100L).put("hash-0")
                .put(11L).put(12L));
        received.put(new JSONArray().put(1).put(200L).put("hash-1")
                .put(21L).put(22L));
        response.put("received_compact", received);
        response.put("provisional_transcript_complete", 2);
        response.put("provisional_transcript_total", 2);
        response.put("final_transcript", new JSONObject()
                .put("state", "COMPLETE")
                .put("text", "canonical words")
                .put("engine", "large-v3")
                .put("created_at_ms", 30L));

        ReliableUploadClient.Status status =
                ReliableUploadClient.parseStatus(response);
        assertTrue(status.committed);
        assertEquals(2, status.received.size());
        assertEquals(100L, status.received.get(0).bytes);
        assertEquals(2, status.transcripts.size());
        assertEquals("canonical words", status.transcripts.get(0).text);
        assertEquals("", status.transcripts.get(1).text);
        assertEquals("COMPLETE", status.finalTranscriptState);
    }

    @Test public void pendingFinalTranscriptIsNotMarkedComplete() throws Exception {
        JSONObject response = new JSONObject();
        response.put("committed", true);
        response.put("received_compact", new JSONArray().put(
                new JSONArray().put(0).put(100L).put("hash-0")
                        .put(11L).put(12L)));
        response.put("final_transcript", new JSONObject()
                .put("state", "RETRY"));
        ReliableUploadClient.Status status =
                ReliableUploadClient.parseStatus(response);
        assertFalse(status.transcripts.containsKey(0));
        assertEquals("RETRY", status.finalTranscriptState);
    }

    @Test public void parsesAggregateTranscriptionProgress() throws Exception {
        JSONObject response = new JSONObject()
                .put("total_committed_count", 84)
                .put("complete_count", 83)
                .put("not_transcribed_count", 1)
                .put("overall_percent", 98)
                .put("committed_session_ids", new JSONArray()
                        .put("session-1").put("already-done"))
                .put("pending", new JSONArray()
                        .put(new JSONObject()
                                .put("session_id", "session-1")
                                .put("display_name", "Recording example")
                                .put("folder_name", "agents")
                                .put("state", "RUNNING")
                                .put("engine", "openai-whisper-large-v3")
                                .put("phase", "transcribing")
                                .put("percent", 42)
                                .put("frames_done", 420L)
                                .put("frames_total", 1000L)
                                .put("duration_ms", 123000L)
                                .put("updated_at_ms", 456L))
                        .put(new JSONObject()
                                .put("session_id", "queued-2")
                                .put("display_name", "Queued recording")
                                .put("folder_name", "agents")
                                .put("state", "QUEUED")
                                .put("phase", "queued")
                                .put("percent", 0)))
                .put("current", new JSONObject()
                        .put("session_id", "session-1")
                        .put("display_name", "Recording example")
                        .put("folder_name", "agents")
                        .put("state", "RUNNING")
                        .put("engine", "openai-whisper-large-v3")
                        .put("phase", "transcribing")
                        .put("percent", 42)
                        .put("frames_done", 420L)
                        .put("frames_total", 1000L)
                        .put("duration_ms", 123000L)
                        .put("updated_at_ms", 456L));
        ReliableUploadClient.TranscriptionStatus status =
                ReliableUploadClient.parseTranscriptionStatus(response);
        assertEquals(84, status.totalCommittedCount);
        assertEquals(83, status.completeCount);
        assertEquals(1, status.notTranscribedCount);
        assertEquals(98, status.overallPercent);
        assertEquals("session-1", status.current.sessionId);
        assertEquals("Recording example", status.current.displayName);
        assertEquals(42, status.current.percent);
        assertEquals("transcribing", status.current.phase);
        assertEquals(420L, status.current.framesDone);
        assertEquals(1000L, status.current.framesTotal);
        assertEquals(2, status.pending.size());
        assertEquals("session-1", status.pending.get(0).sessionId);
        assertEquals("queued-2", status.pending.get(1).sessionId);
        assertTrue(status.committedSessionIds.contains("session-1"));
        assertTrue(status.committedSessionIds.contains("already-done"));
    }

    @Test public void parsesIdleTranscriptionStatusWithoutCurrentFile() throws Exception {
        ReliableUploadClient.TranscriptionStatus status =
                ReliableUploadClient.parseTranscriptionStatus(new JSONObject()
                        .put("total_committed_count", 10)
                        .put("complete_count", 10)
                        .put("not_transcribed_count", 0)
                        .put("overall_percent", 100));
        assertEquals(100, status.overallPercent);
        assertTrue(status.current == null);
    }
    @org.junit.Test public void pendingWorkWithoutTotalIsNotReportedComplete() throws Exception {
        ReliableUploadClient.TranscriptionStatus status=ReliableUploadClient.parseTranscriptionStatus(
                new JSONObject().put("not_transcribed_count",30));
        assertEquals(30,status.totalCommittedCount);
        assertEquals(0,status.overallPercent);
    }
    @org.junit.Test public void disabledTranscriptionRequiresExplicitServerAcknowledgement() throws Exception {
        for(JSONObject reply:new JSONObject[]{new JSONObject(),new JSONObject().put("auto_transcribe",true),new JSONObject().put("auto_transcribe","false")}) {
            try{ReliableUploadClient.verifyTranscriptionPolicy(false,reply);org.junit.Assert.fail("Missing or wrong policy accepted");}
            catch(java.io.IOException expected){org.junit.Assert.assertTrue(expected.getMessage().contains("Audio remains"));}
        }
        ReliableUploadClient.verifyTranscriptionPolicy(false,new JSONObject().put("auto_transcribe",false));
        ReliableUploadClient.verifyTranscriptionPolicy(true,new JSONObject());
    }
}
