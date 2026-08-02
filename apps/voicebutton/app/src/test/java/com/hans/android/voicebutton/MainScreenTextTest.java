package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MainScreenTextTest {
    @Test public void defaultScreenHidesProtocolDetails() {
        String waiting = MainScreenText.transfer(true, 0L, 0L, 0);
        String value = MainScreenText.transfer(true, 1000L, 500L, 500);
        assertTrue(value.contains("50.0%"));
        assertFalse(waiting.contains("chunk"));
        assertFalse(value.contains("chunk"));
        assertFalse(value.contains("HTTP"));
        assertFalse(value.contains("sha"));
    }

    @Test public void quietSignalIsExplicitlyStillRecording() {
        assertTrue(MainScreenText.microphone(true, false).contains("recording continues"));
    }
}
