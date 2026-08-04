package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerControlStateTest {
    @Test public void openingDisablesAllTransport() {
        PlayerControlState state = PlayerControlState.from(
                "opening", "", false, false, true, false);
        assertFalse(state.playEnabled);
        assertEquals("Loading…", state.playLabel);
    }

    @Test public void readyEnablesPlaybackAndReportsReady() {
        PlayerControlState state = PlayerControlState.from(
                "ready", "", false, true, true, false);
        assertTrue(state.playEnabled);
        assertTrue(state.seekEnabled);
        assertEquals("Ready", state.status);
    }
    @Test public void pausingDisablesButtonAndShowsFeedback() {
        PlayerControlState state = PlayerControlState.from(
                "pausing", "", true, true, true, false);
        assertFalse(state.playEnabled);
        assertEquals("Pausing…", state.playLabel);
    }

}
