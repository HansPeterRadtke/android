package com.hans.android.network.reliable;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReliableUploadClientTest {
    @Test public void usesFourKilobyteDurableParts() {
        assertEquals(4096, ReliableUploadClient.UPLOAD_PART_BYTES);
        assertEquals(4096, ReliableUploadClient.nextPartLength(0L, 49152L));
        assertEquals(4096, ReliableUploadClient.nextPartLength(4096L, 49152L));
        assertEquals(848, ReliableUploadClient.nextPartLength(48304L, 49152L));
        assertEquals(0, ReliableUploadClient.nextPartLength(49152L, 49152L));
    }
}
