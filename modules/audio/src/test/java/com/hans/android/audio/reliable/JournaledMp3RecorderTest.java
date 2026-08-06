package com.hans.android.audio.reliable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import com.hans.android.audio.AudioInputOption;

import org.junit.Test;

public class JournaledMp3RecorderTest {
    @Test public void classicBluetoothRejectsEightKilohertzFallback() {
        AudioInputOption bluetooth = new AudioInputOption(1484,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                "Bluetooth headset microphone — G06-BT",
                AudioInputOption.Category.BLUETOOTH);
        assertArrayEquals(new int[] {16000},
                JournaledMp3Recorder.candidateInputSampleRates(bluetooth));
    }

    @Test public void nonBluetoothKeepsNormalSixteenKilohertzInput() {
        AudioInputOption builtIn = new AudioInputOption(16,
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone",
                AudioInputOption.Category.BUILT_IN);
        assertArrayEquals(new int[] {48000, 44100, 32000, 16000},
                JournaledMp3Recorder.candidateInputSampleRates(builtIn));
    }
    @Test public void speechProfileUsesFortyEightKilohertzAndEfficientBitrate() {
        org.junit.Assert.assertEquals(48000, ReliableSessionManifest.OUTPUT_SAMPLE_RATE);
        org.junit.Assert.assertEquals(96, Mp3Converter.BITRATE_KBPS);
    }

    @Test public void capturePathRotatesLiveJournalsForBackgroundEncoding() {
        org.junit.Assert.assertTrue(JournaledMp3Recorder.encodesWhileCapturing());
        org.junit.Assert.assertEquals(19200,
                JournaledMp3Recorder.captureBufferBytes(48000, 4096));
        org.junit.Assert.assertEquals(1000,
                JournaledMp3Recorder.syncIntervalMs());
    }


    @Test public void enhancementLevelNamesAreStable() {
        org.junit.Assert.assertEquals("off", JournaledMp3Recorder.enhancementName(-1));
        org.junit.Assert.assertEquals("off", JournaledMp3Recorder.enhancementName(0));
        org.junit.Assert.assertEquals("natural", JournaledMp3Recorder.enhancementName(1));
        org.junit.Assert.assertEquals("strong", JournaledMp3Recorder.enhancementName(2));
        org.junit.Assert.assertEquals("maximum", JournaledMp3Recorder.enhancementName(3));
        org.junit.Assert.assertEquals("maximum", JournaledMp3Recorder.enhancementName(99));
    }

}
