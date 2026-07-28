package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrimaryActionPolicyTest {
    @Test public void allowsNewRecordingDuringOldFileSynchronization() {
        assertTrue(PrimaryActionPolicy.isEnabled(false, "SYNCHRONIZING",
                false, false, false, true));
        assertTrue(PrimaryActionPolicy.isEnabled(false, "FINISHING",
                false, false, false, true));
        assertTrue(PrimaryActionPolicy.isEnabled(false, "COMPRESSING",
                false, false, false, true));
    }

    @Test public void pauseAndRecoveryActionsRemainAvailable() {
        assertTrue(PrimaryActionPolicy.isEnabled(false, "PAUSED",
                true, true, false, true));
        assertTrue(PrimaryActionPolicy.isEnabled(false, "RECOVERY REQUIRED",
                true, false, true, true));
    }

    @Test public void blocksDuplicateStartDuringOpeningAndCleanup() {
        assertFalse(PrimaryActionPolicy.isEnabled(false, "PREPARING",
                true, false, false, true));
        assertFalse(PrimaryActionPolicy.isEnabled(false, "CLEANING",
                false, false, false, true));
        assertFalse(PrimaryActionPolicy.isEnabled(false, "READY",
                false, false, false, false));
    }
}
