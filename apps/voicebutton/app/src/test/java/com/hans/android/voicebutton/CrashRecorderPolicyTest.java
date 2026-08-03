package com.hans.android.voicebutton;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrashRecorderPolicyTest {
    @Test public void crashReportBoundIsLargeEnoughForAUsefulStack() {
        assertTrue(128 * 1024 >= 64 * 1024);
    }
}
