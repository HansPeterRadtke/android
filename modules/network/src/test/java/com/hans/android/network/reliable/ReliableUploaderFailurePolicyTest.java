package com.hans.android.network.reliable;

import static org.junit.Assert.assertEquals;
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

    @Test public void permanentValidationFailureQuarantinesOnlyThatRecording() {
        assertFalse(ReliableUploader.isRetryableFailure(
                new ReliableUploadClient.ProtocolException(401, "unauthorized")));
        assertFalse(ReliableUploader.isRetryableFailure(
                new IllegalStateException("local chunk missing")));
        assertTrue(ReliableUploader.shouldQuarantineSessionFailure(
                new ReliableUploadClient.ProtocolException(400,
                        "one recording rejected")));
        assertTrue(ReliableUploader.shouldQuarantineSessionFailure(
                new IllegalStateException("local chunk missing")));
        assertFalse(ReliableUploader.shouldQuarantineSessionFailure(
                new ReliableUploadClient.ProtocolException(503,
                        "server restarting")));
    }

    @Test public void explicitStopEndsWorker() {
        assertTrue(ReliableUploader.shouldStopAfterFailure(false,
                new InterruptedIOException("shutdown")));
    }

    @Test public void retryKeepsHighestAcknowledgedDurableOffset() {
        assertEquals(12_000L, ReliableUploader.resumeDurableFloor(
                "session-a", 1, 12_000L,
                "session-a", 1, 11_000L));
        assertEquals(11_000L, ReliableUploader.resumeDurableFloor(
                "session-a", 1, 12_000L,
                "session-b", 1, 11_000L));
        assertEquals(11_000L, ReliableUploader.resumeDurableFloor(
                "session-a", 0, 12_000L,
                "session-a", 1, 11_000L));
    }

    @Test public void serverDurableOffsetMayAdvanceButNeverRegress() throws Exception {
        assertEquals(12_000L, ReliableUploader.requireNonRegressingDurable(
                11_000L, 12_000L));
        assertEquals(12_000L, ReliableUploader.requireNonRegressingDurable(
                12_000L, 12_000L));
        try {
            ReliableUploader.requireNonRegressingDurable(12_000L, 11_000L);
            throw new AssertionError("Expected durable-offset regression to fail");
        } catch (ReliableUploadClient.ProtocolException expected) {
            assertEquals(409, expected.httpCode);
        }
    }

    @org.junit.Test public void durablePcmWaitingForEncodingIsNotUnreadableAudio() {
        com.hans.android.audio.reliable.ReliableSessionManifest.Segment segment =
                new com.hans.android.audio.reliable.ReliableSessionManifest.Segment();
        segment.seq = 0;
        segment.pcmJournalName = "segment_000000_16000.pcm";
        segment.mp3Name = "";
        segment.mp3Bytes = 0L;
        org.junit.Assert.assertTrue(
                ReliableUploader.isPendingLocalEncoding(segment, true));
        org.junit.Assert.assertFalse(
                ReliableUploader.isPendingLocalEncoding(segment, false));
        segment.mp3Name = "segment_000000.mp3";
        segment.mp3Bytes = 123L;
        org.junit.Assert.assertFalse(
                ReliableUploader.isPendingLocalEncoding(segment, true));
    }
}
