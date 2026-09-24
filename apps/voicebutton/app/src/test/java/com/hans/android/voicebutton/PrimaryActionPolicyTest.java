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
        assertFalse(PrimaryActionPolicy.isEnabled(false, "STARTING",
                false, false, false, true));
        assertFalse(PrimaryActionPolicy.isEnabled(false, "PREPARING",
                true, false, false, true));
        assertFalse(PrimaryActionPolicy.isEnabled(false, "CLEANING",
                false, false, false, true));
        assertFalse(PrimaryActionPolicy.isEnabled(false, "READY",
                false, false, false, false));
    }

    @Test public void disabledActionExplainsWhy() {
        org.junit.Assert.assertEquals("Recording is already starting.",
                PrimaryActionPolicy.disabledReason(false, "STARTING",
                        false, false, false, true, true));
        org.junit.Assert.assertEquals("Microphone permission is required to record.",
                PrimaryActionPolicy.disabledReason(false, "READY",
                        false, false, false, true, false));
        org.junit.Assert.assertEquals("No physical microphone is currently available.",
                PrimaryActionPolicy.disabledReason(false, "READY",
                        false, false, false, false, true));
        org.junit.Assert.assertEquals("Finish or recover the open recording first.",
                PrimaryActionPolicy.disabledReason(false, "READY",
                        true, false, false, true, true));
    }

    @Test public void permissionRequestMustRemainReachable() {
        assertTrue(PrimaryActionPolicy.canAttemptMicrophone(false, false, false));
        assertTrue(PrimaryActionPolicy.isEnabled(false, "READY",
                false, false, false,
                PrimaryActionPolicy.canAttemptMicrophone(false, false, false)));
    }

    @Test public void microphoneRefreshMustNotLookLikeNoMicrophone() {
        assertTrue(PrimaryActionPolicy.canAttemptMicrophone(false, true, true));
        assertTrue(PrimaryActionPolicy.isEnabled(false, "READY",
                false, false, false,
                PrimaryActionPolicy.canAttemptMicrophone(false, true, true)));
        assertFalse(PrimaryActionPolicy.canAttemptMicrophone(false, false, true));
    }
}
