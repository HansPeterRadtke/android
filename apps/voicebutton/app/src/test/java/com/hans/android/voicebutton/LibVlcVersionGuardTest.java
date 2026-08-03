package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LibVlcVersionGuardTest {
    @Test public void nativeVersionIsNeverCalledBeforeEngineInitialization() {
        assertEquals("loading", LibVlcVersionGuard.safeVersion(false,
                () -> { throw new AssertionError("must not be called"); }));
    }

    @Test public void missingNativeSymbolCannotCrashTheUiThread() {
        assertEquals("unavailable", LibVlcVersionGuard.safeVersion(true,
                () -> { throw new UnsatisfiedLinkError("native not loaded"); }));
    }

    @Test public void loadedVersionIsReturned() {
        assertEquals("3.7.0", LibVlcVersionGuard.safeVersion(true,
                () -> "3.7.0"));
    }
}
