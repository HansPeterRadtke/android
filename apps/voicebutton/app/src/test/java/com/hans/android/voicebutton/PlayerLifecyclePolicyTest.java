package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerLifecyclePolicyTest {
    @Test public void taskRemovalPersistsWhetherPlaybackWasActive() {
        assertTrue(PlayerLifecyclePolicy.resumeAfterTaskRemoval(true));
        assertFalse(PlayerLifecyclePolicy.resumeAfterTaskRemoval(false));
    }

    @Test public void leavingTheViewKeepsAnExistingSourceAlive() {
        assertTrue(PlayerLifecyclePolicy.keepPlayingWhenViewStops(true));
        assertFalse(PlayerLifecyclePolicy.keepPlayingWhenViewStops(false));
    }

    @Test public void checkpointPeriodIsFiveSeconds() {
        assertEquals(5000L, PlayerLifecyclePolicy.checkpointIntervalMs());
    }
    @Test public void closingWithoutALoadedSourcePreservesExistingCheckpoint() {
        assertFalse(PlayerLifecyclePolicy.persistCheckpointOnClose(false));
        assertTrue(PlayerLifecyclePolicy.persistCheckpointOnClose(true));
    }

}
