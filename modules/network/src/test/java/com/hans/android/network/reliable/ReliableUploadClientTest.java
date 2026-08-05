package com.hans.android.network.reliable;

import static org.junit.Assert.assertEquals;

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
}
