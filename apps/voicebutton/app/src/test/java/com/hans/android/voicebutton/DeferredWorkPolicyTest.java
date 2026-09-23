package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;

public class DeferredWorkPolicyTest {
    @Test public void acceptsLiveExecutorAndRejectsShutdownExecutor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(DeferredWorkPolicy.maySubmit(executor));
            executor.shutdownNow();
            assertFalse(DeferredWorkPolicy.maySubmit(executor));
            assertFalse(DeferredWorkPolicy.maySubmit(null));
        } finally {
            executor.shutdownNow();
        }
    }
}
