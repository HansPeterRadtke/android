package com.hans.android.voicebutton;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class MainThreadWatchdogExitTest {
    @Test public void explicitStopDisablesWatchdogUntilNextRealOpen() {
        MainThreadWatchdog.stop();
        assertFalse(MainThreadWatchdog.isRunning());
        MainThreadWatchdog.start();
        try {
            assertTrue(MainThreadWatchdog.isRunning());
        } finally {
            MainThreadWatchdog.stop();
        }
        assertFalse(MainThreadWatchdog.isRunning());
        MainThreadWatchdog.start();
        try {
            assertTrue(MainThreadWatchdog.isRunning());
        } finally {
            MainThreadWatchdog.stop();
        }
    }
}
