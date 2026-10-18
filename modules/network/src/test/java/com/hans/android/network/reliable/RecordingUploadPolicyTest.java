package com.hans.android.network.reliable;

import static org.junit.Assert.*;
import org.junit.Test;

public class RecordingUploadPolicyTest {
    @Test public void exitAlwaysStopsOldAutomaticAndManualUploads() {
        assertTrue(RecordingUploadPolicy.permitted(true, false, 0));
        assertTrue(RecordingUploadPolicy.permitted(false, false, 123));
        assertFalse(RecordingUploadPolicy.permitted(true, true, 0));
        assertFalse(RecordingUploadPolicy.permitted(true, true, Long.MAX_VALUE));
        assertFalse(RecordingUploadPolicy.permitted(false, true, 123));
        assertFalse(RecordingUploadPolicy.permitted(false, false, 0));
    }

    @Test public void noSessionCanSlipThroughExitGate() {
        assertFalse(RecordingUploadPolicy.eligible(true, true,
                Long.MAX_VALUE, 1));
        assertFalse(RecordingUploadPolicy.eligible(false, true,
                Long.MAX_VALUE, 1));
        assertTrue(RecordingUploadPolicy.eligible(true, false, 0, 999));
        assertTrue(RecordingUploadPolicy.eligible(false, false, 150, 100));
        assertFalse(RecordingUploadPolicy.eligible(false, false, 150, 151));
    }
}
