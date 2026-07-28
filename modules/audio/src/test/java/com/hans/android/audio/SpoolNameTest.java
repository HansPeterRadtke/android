package com.hans.android.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpoolNameTest {
    @Test public void roundTripsPendingFinalName() {
        SpoolName original = new SpoolName(42, true, SpoolName.State.PENDING);
        assertEquals("chunk_000042_final.pending.wav", original.fileName());
        SpoolName parsed = SpoolName.parse(original.fileName());
        assertEquals(42, parsed.getSequence());
        assertTrue(parsed.isFinalChunk());
        assertEquals(SpoolName.State.PENDING, parsed.getState());
    }

    @Test public void changesClaimStateWithoutChangingIdentity() {
        SpoolName data = new SpoolName(7, false, SpoolName.State.PENDING);
        SpoolName uploading = data.withState(SpoolName.State.UPLOADING);
        assertEquals("chunk_000007_data.uploading.wav", uploading.fileName());
        assertFalse(uploading.isFinalChunk());
    }

    @Test public void rejectsUnrelatedFiles() {
        assertNull(SpoolName.parse("recording.wav"));
    }
}
