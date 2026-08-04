package com.hans.android.audio.reliable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RecordingFileNamesTest {
    @Test public void defaultNameContainsTimestampSessionAndExtension() {
        String value = RecordingFileNames.defaultMp3Name(
                1785830400000L, "03243605-daaa-416a-85e1-3c35f1ff21eb");
        assertTrue(value.startsWith("Recording 2026-08-04"));
        assertTrue(value.contains("03243605"));
        assertTrue(value.endsWith(".mp3"));
        assertFalse("recording.mp3".equalsIgnoreCase(value));
    }

    @Test public void legacyGenericNameIsRecognized() {
        assertTrue(RecordingFileNames.isLegacyGenericName("recording.mp3"));
        assertTrue(RecordingFileNames.isLegacyGenericName(""));
        assertFalse(RecordingFileNames.isLegacyGenericName("Recording 2026.mp3"));
    }
    @Test public void defaultDisplayNameStillGetsUniquePhysicalName() {
        long created = 1785830400000L;
        String value = RecordingFileNames.mp3Name(created,
                "03243605-daaa", RecordingFileNames.defaultDisplayName(created));
        assertTrue(value.contains("03243605"));
    }

}
