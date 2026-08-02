package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MainThreadWatchdogTest {
    @Test public void detectsOnlyOutstandingProbePastThreshold() {
        assertFalse(MainThreadWatchdog.isStalled(0L, 5000L, 1500L));
        assertFalse(MainThreadWatchdog.isStalled(1000L, 2499L, 1500L));
        assertTrue(MainThreadWatchdog.isStalled(1000L, 2500L, 1500L));
    }
}
