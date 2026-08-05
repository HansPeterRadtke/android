package com.hans.android.network.reliable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

public class ReliableUploaderFailurePolicyTest {
    @Test public void networkFailuresRetryWhileRunning() {
        assertFalse(ReliableUploader.shouldStopAfterFailure(true,
                new InterruptedIOException("connection changed")));
        assertTrue(ReliableUploader.isRetryableFailure(
                new SocketTimeoutException("connect timed out")));
    }

    @Test public void transientHttpCodesRetryAndHonorRetryAfter() {
        ReliableUploadClient.ProtocolException failure =
                new ReliableUploadClient.ProtocolException(429, "busy", 45_000L);
        assertTrue(ReliableUploader.isRetryableFailure(failure));
        assertTrue(ReliableUploader.retryAfterMs(failure) == 45_000L);
        assertTrue(ReliableUploader.isRetryableFailure(
                new ReliableUploadClient.ProtocolException(503, "restart")));
    }

    @Test public void permanentValidationFailurePauses() {
        assertFalse(ReliableUploader.isRetryableFailure(
                new ReliableUploadClient.ProtocolException(401, "unauthorized")));
        assertFalse(ReliableUploader.isRetryableFailure(
                new IllegalStateException("local chunk missing")));
    }

    @Test public void explicitStopEndsWorker() {
        assertTrue(ReliableUploader.shouldStopAfterFailure(false,
                new InterruptedIOException("shutdown")));
    }
}
