package com.hans.android.audio.reliable;

import static org.junit.Assert.assertArrayEquals;

import android.media.AudioDeviceInfo;

import com.hans.android.audio.AudioInputOption;

import org.junit.Test;

public class JournaledMp3RecorderTest {
    @Test public void classicBluetoothUsesTelephoneRateBeforeWidebandFallback() {
        AudioInputOption bluetooth = new AudioInputOption(1484,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                "Bluetooth headset microphone — G06-BT",
                AudioInputOption.Category.BLUETOOTH);
        assertArrayEquals(new int[] {8000, 16000},
                JournaledMp3Recorder.candidateInputSampleRates(bluetooth));
    }

    @Test public void nonBluetoothKeepsNormalSixteenKilohertzInput() {
        AudioInputOption builtIn = new AudioInputOption(16,
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone",
                AudioInputOption.Category.BUILT_IN);
        assertArrayEquals(new int[] {16000},
                JournaledMp3Recorder.candidateInputSampleRates(builtIn));
    }
}
