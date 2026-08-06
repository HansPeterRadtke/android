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

    @Test public void responseReadHasNoFixedDeadline() {
        assertEquals(0, ReliableUploadClient.CONNECT_TIMEOUT_MS);
        assertEquals(0, ReliableUploadClient.READ_TIMEOUT_MS);
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
}
