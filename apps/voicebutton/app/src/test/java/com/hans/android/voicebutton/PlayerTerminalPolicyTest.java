package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayerTerminalPolicyTest {
    @Test public void stoppedCannotEraseDecodeError() {
        assertTrue(PlayerTerminalPolicy.ignoreStateAfterError(
                "decode failed", "stopped"));
        assertFalse(PlayerTerminalPolicy.ignoreStateAfterError("", "stopped"));
    }

    @Test public void replayAfterEndStartsAtZero() {
        assertTrue(PlayerTerminalPolicy.restartFromBeginning(
                "ended", 9000L, 10000L));
        assertTrue(PlayerTerminalPolicy.restartFromBeginning(
                "paused", 9999L, 10000L));
        assertFalse(PlayerTerminalPolicy.restartFromBeginning(
                "paused", 5000L, 10000L));
    }

    @Test public void openingAndBufferingArePending() {
        assertTrue(PlayerTerminalPolicy.startIsPending("opening"));
        assertTrue(PlayerTerminalPolicy.startIsPending("buffering 20%"));
        assertFalse(PlayerTerminalPolicy.startIsPending("playing"));
    }
}
