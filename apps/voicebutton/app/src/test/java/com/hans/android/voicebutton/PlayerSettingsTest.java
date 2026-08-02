package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerSettingsTest {
    @Test public void hardSpeedBoundsMatchThorContract() {
        assertEquals(.25f, PlayerSettings.HARD_MIN_SPEED, .0001f);
        assertEquals(8f, PlayerSettings.HARD_MAX_SPEED, .0001f);
    }
}
