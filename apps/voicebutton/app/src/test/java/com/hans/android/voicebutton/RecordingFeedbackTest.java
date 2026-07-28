package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RecordingFeedbackTest {
    @Test public void mapsDbfsToVisibleMeterRange() {
        assertEquals(0, RecordingFeedback.levelPermille(-120f));
        assertEquals(0, RecordingFeedback.levelPermille(-60f));
        assertEquals(500, RecordingFeedback.levelPermille(-30f));
        assertEquals(1000, RecordingFeedback.levelPermille(0f));
    }

    @Test public void mapsDurableBytesToTransmissionProgress() {
        assertEquals(0, RecordingFeedback.uploadPermille(0L, 0L));
        assertEquals(250, RecordingFeedback.uploadPermille(250L, 1000L));
        assertEquals(1000, RecordingFeedback.uploadPermille(1000L, 1000L));
        assertEquals(1000, RecordingFeedback.uploadPermille(1500L, 1000L));
    }
}
