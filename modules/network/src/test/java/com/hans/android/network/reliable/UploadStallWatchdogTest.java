package com.hans.android.network.reliable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class UploadStallWatchdogTest {
    @Test public void expirationMathIsDeterministic() {
        assertFalse(UploadStallWatchdog.isExpired(100L, 199L, 100L));
        assertTrue(UploadStallWatchdog.isExpired(100L, 200L, 100L));
    }

    @Test public void heartbeatPreventsPrematureTimeout() throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        try (UploadStallWatchdog watchdog = new UploadStallWatchdog(120L,
                () -> fired.set(true))) {
            Thread.sleep(70L);
            watchdog.heartbeat();
            Thread.sleep(70L);
            assertFalse(fired.get());
        }
    }

    @Test public void defaultTimeoutAllowsExtremelySlowRequests() {
        assertTrue(UploadStallWatchdog.DEFAULT_TIMEOUT_MS >= 60_000L);
        assertTrue(UploadStallWatchdog.DEFAULT_TIMEOUT_MS <= 5L * 60L * 1000L);
    }
}
