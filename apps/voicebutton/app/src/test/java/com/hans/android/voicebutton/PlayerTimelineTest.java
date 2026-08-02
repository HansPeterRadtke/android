package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerTimelineTest {
    @Test public void studioMapsPhysicalAndLogicalTimeWithoutDrift() {
        assertEquals(3500L, PlayerTimeline.logicalTime(2000L, true, 1.75f));
        assertEquals(2000L, PlayerTimeline.physicalTime(3500L, true, 1.75f));
    }

    @Test public void seekProgressIsBounded() {
        assertEquals(500, PlayerTimeline.progress(5000L, 10000L));
        assertEquals(10000L, PlayerTimeline.fromProgress(1000, 10000L));
    }
}
