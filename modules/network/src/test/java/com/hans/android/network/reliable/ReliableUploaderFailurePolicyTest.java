package com.hans.android.network.reliable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

public class ReliableUploaderFailurePolicyTest {
    @Test public void interruptedIoIsRetriedWhileUploaderIsRunning() {
        assertFalse(ReliableUploader.shouldStopAfterFailure(true,
                new InterruptedIOException("request cancelled by timeout")));
    }

    @Test public void socketTimeoutIsRetriedWhileUploaderIsRunning() {
        assertFalse(ReliableUploader.shouldStopAfterFailure(true,
                new SocketTimeoutException("read timed out")));
    }

    @Test public void failureStopsAfterExplicitStopClearsRunning() {
        assertTrue(ReliableUploader.shouldStopAfterFailure(false,
                new InterruptedIOException("shutdown")));
    }
}
