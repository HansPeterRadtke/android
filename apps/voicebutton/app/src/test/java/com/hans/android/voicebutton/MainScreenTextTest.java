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
    @Test public void overviewStateDoesNotExposeUploaderDebugNoise() {
        String value = MainScreenText.stateSummary("SYNCHRONIZING", false, false,
                false, false);
        assertFalse(value.contains("chunk"));
        assertFalse(value.contains("revision"));
        assertFalse(value.contains("HTTP"));
    }

    @Test public void structureKeyIgnoresVolatileExplanationText() {
        String first = MainScreenText.structureKey("RECORDING", true, false,
                false, false, true, "default", 4, 10);
        String second = MainScreenText.structureKey("RECORDING", true, false,
                false, false, true, "default", 4, 10);
        org.junit.Assert.assertEquals(first, second);
    }

    @Test public void failureIncidentTextDoesNotExposeAlarmLanguage() {
        String title = MainScreenText.stateTitle("FAILED", false, false, true);
        String summary = MainScreenText.stateSummary("FAILED", false, false,
                true, false);
        String supportKey = MainScreenText.structureKey("FAILED", false, false,
                true, false, false, "default", 0, 0);
        assertFalse(title.toLowerCase(java.util.Locale.US).contains("alarm"));
        assertFalse(summary.toLowerCase(java.util.Locale.US).contains("alarm"));
        assertFalse(supportKey.toLowerCase(java.util.Locale.US).contains("alarm"));
    }

}
