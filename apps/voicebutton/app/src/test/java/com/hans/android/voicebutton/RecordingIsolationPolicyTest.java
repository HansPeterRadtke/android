package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordingIsolationPolicyTest {
    @Test public void deferredWorkIsForbiddenDuringCapture() {
        assertFalse(RecordingIsolationPolicy.mayRunDeferredWork(true, false));
        assertFalse(RecordingIsolationPolicy.mayRunDeferredWork(false, true));
        assertTrue(RecordingIsolationPolicy.mayRunDeferredWork(false, false));
    }

    @Test public void stickyRecoveryResumesCaptureFirst() {
        assertTrue(RecordingIsolationPolicy.resumeCaptureBeforeDeferredWork(
                true, true));
        assertFalse(RecordingIsolationPolicy.resumeCaptureBeforeDeferredWork(
                true, false));
        assertFalse(RecordingIsolationPolicy.resumeCaptureBeforeDeferredWork(
                false, true));
    }
}
