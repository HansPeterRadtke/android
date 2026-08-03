package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerCheckpointPolicyTest {
    @Test public void logicalStudioPositionMapsBackToPhysical() {
        assertEquals(2000L, PlayerTimeline.physicalTime(3500L, true, 1.75f));
    }

    @Test public void emptyCheckpointHasNoSourceAndNeverResumes() {
        PlayerCheckpoint value = PlayerCheckpoint.empty();
        assertFalse(value.hasSource());
        assertFalse(value.resumeOnOpen);
    }

    @Test public void schemaIsExplicit() {
        assertTrue(PlayerCheckpoint.SCHEMA_VERSION > 0);
    }
}
