package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerSettingsTest {
    @Test public void hardSpeedBoundsMatchThorContract() {
        assertEquals(.25f, PlayerSettings.HARD_MIN_SPEED, .0001f);
        assertEquals(8f, PlayerSettings.HARD_MAX_SPEED, .0001f);
    }
    @Test public void nonFiniteSettingsAreRejected(){for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){try{PlayerSettings.clamp(value,.25f,8f);org.junit.Assert.fail("Non-finite setting accepted");}catch(IllegalArgumentException expected){}}}
    @Test public void finiteSettingsAreBounded(){assertEquals(.25f,PlayerSettings.clamp(-1f,.25f,8f),0f);assertEquals(8f,PlayerSettings.clamp(100f,.25f,8f),0f);assertEquals(1.25f,PlayerSettings.clamp(1.25f,.25f,8f),0f);}
}
