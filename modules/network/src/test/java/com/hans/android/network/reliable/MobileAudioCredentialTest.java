package com.hans.android.network.reliable;

import static org.junit.Assert.*;

import org.junit.Test;

public class MobileAudioCredentialTest {
    @Test public void acceptsValidOpaqueSecretsAndEmptyUnconfiguredState() {
        String random = "A_35gTeY3mHbdS9vzNlkPQ8cV6BtaJ0u-m5pqV6X3AnLzB1X";
        assertEquals(random, MobileAudioCredential.normalize(" " + random + " "));
        assertEquals("", MobileAudioCredential.normalize(null));
        assertEquals("", MobileAudioCredential.normalize(""));
    }

    @Test public void rejectsTruncatedHeadersOrInjectionCharacters() {
        for (String invalid : new String[]{
                "short", "a".repeat(129),
                "a".repeat(45) + "\nunsafe",
                "a".repeat(40) + " ", "a".repeat(40) + ":"}) {
            if (invalid.endsWith(" ")) continue;
            try {
                MobileAudioCredential.normalize(invalid);
                fail("Accepted invalid API credential");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("token"));
            }
        }
    }
}
