package com.hans.android.network.reliable;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AdaptiveUploadPolicyTest {
    @Test public void startsAtSixtyFourKiBAndClampsTail() {
        AdaptiveUploadPolicy policy = new AdaptiveUploadPolicy();
        assertEquals(64 * 1024, policy.currentPartBytes());
        assertEquals(64 * 1024, policy.nextPartLength(0L, 1024L * 1024L));
        assertEquals(1234, policy.nextPartLength(1000L, 2234L));
    }

    @Test public void growsAfterSustainedFastSuccess() {
        AdaptiveUploadPolicy policy = new AdaptiveUploadPolicy();
        for (int i = 0; i < 4; i++) policy.onPartSuccess(64 * 1024, 1000L);
        assertEquals(128 * 1024, policy.currentPartBytes());
    }

    @Test public void shrinksOnFailureAndSlowSuccess() {
        AdaptiveUploadPolicy policy = new AdaptiveUploadPolicy();
        policy.onPartFailure();
        assertEquals(32 * 1024, policy.currentPartBytes());
        policy.onPartSuccess(32 * 1024, 180_000L);
        assertEquals(16 * 1024, policy.currentPartBytes());
        for (int i = 0; i < 10; i++) policy.onPartFailure();
        assertEquals(4 * 1024, policy.currentPartBytes());
    }
}
