package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordingContinuityPolicyTest {
    @Test public void serviceStaysAliveForAnyContinuityObligation() {
        assertTrue(RecordingContinuityPolicy.keepServiceAlive(true, false, false, false));
        assertTrue(RecordingContinuityPolicy.keepServiceAlive(false, true, false, false));
        assertTrue(RecordingContinuityPolicy.keepServiceAlive(false, false, true, false));
        assertTrue(RecordingContinuityPolicy.keepServiceAlive(false, false, false, true));
        assertFalse(RecordingContinuityPolicy.keepServiceAlive(false, false, false, false));
    }

    @Test public void recoveryBackoffIsBounded() {
        assertEquals(1000L, RecordingContinuityPolicy.recoveryDelayMs(0));
        assertEquals(2000L, RecordingContinuityPolicy.recoveryDelayMs(1));
        assertEquals(30000L, RecordingContinuityPolicy.recoveryDelayMs(20));
    }
    @Test public void pausedFullySynchronizedSessionDoesNotRunForever() {
        assertFalse(RecordingContinuityPolicy.sessionNeedsSynchronization(
                false, false, false, false, false));
        assertTrue(RecordingContinuityPolicy.sessionNeedsSynchronization(
                false, false, false, true, false));
        assertTrue(RecordingContinuityPolicy.sessionNeedsSynchronization(
                true, true, false, false, false));
        assertFalse(RecordingContinuityPolicy.sessionNeedsSynchronization(
                true, true, true, false, false));
    }

}
