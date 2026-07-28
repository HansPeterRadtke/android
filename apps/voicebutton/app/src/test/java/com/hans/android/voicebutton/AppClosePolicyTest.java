package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppClosePolicyTest {
    @Test public void warnsOnlyForRecordingOrLocalFileOperations() {
        assertTrue(AppClosePolicy.requiresWarning(true, "RECORDING"));
        assertTrue(AppClosePolicy.requiresWarning(false, "FINISHING"));
        assertTrue(AppClosePolicy.requiresWarning(false, "COMPRESSING"));
        assertFalse(AppClosePolicy.requiresWarning(false, "PAUSED"));
        assertFalse(AppClosePolicy.requiresWarning(false, "SYNCHRONIZING"));
        assertFalse(AppClosePolicy.requiresWarning(false, "READY"));
    }
}
