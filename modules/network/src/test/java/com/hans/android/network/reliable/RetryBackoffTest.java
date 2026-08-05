package com.hans.android.network.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RetryBackoffTest {
    @Test public void fullJitterStaysWithinExponentialWindow() {
        assertEquals(0L, RetryBackoff.fullJitterDelayMs(1, 0L, 0d));
        assertTrue(RetryBackoff.fullJitterDelayMs(4, 0L, 0.999d) <= 8000L);
        assertTrue(RetryBackoff.fullJitterDelayMs(30, 0L, 0.999d)
                <= RetryBackoff.MAX_DELAY_MS);
    }

    @Test public void retryAfterIsAlwaysHonored() {
        assertEquals(90_000L,
                RetryBackoff.fullJitterDelayMs(1, 90_000L, 0d));
    }
}
