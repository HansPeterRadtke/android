package com.hans.android.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

public class AudioInputCatalogTest {
    @Test public void acceptsOnlyPhysicalRecordingInputs() {
        assertTrue(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_BUILTIN_MIC));
        assertTrue(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_WIRED_HEADSET));
        assertTrue(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_USB_HEADSET));
        assertTrue(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
        assertFalse(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_REMOTE_SUBMIX));
        assertFalse(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_TELEPHONY));
        assertFalse(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_FM_TUNER));
        assertFalse(AudioInputCatalog.isSelectablePhysicalType(AudioDeviceInfo.TYPE_BUS));
    }
    @Test public void numbersDuplicateBuiltInInputsWithoutInventingEntries() {
        java.util.List<AudioInputOption> inputs = new java.util.ArrayList<>();
        inputs.add(new AudioInputOption(11, AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone", AudioInputOption.Category.BUILT_IN));
        inputs.add(new AudioInputOption(12, AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone — Noise microphone", AudioInputOption.Category.BUILT_IN));
        java.util.List<AudioInputOption> result =
                AudioInputCatalog.numberDuplicatePhysicalInputs(inputs);
        org.junit.Assert.assertEquals(2, result.size());
        org.junit.Assert.assertEquals("Built-in microphone 1", result.get(0).getLabel());
        org.junit.Assert.assertEquals("Built-in microphone 2 — Noise microphone", result.get(1).getLabel());
        org.junit.Assert.assertEquals(11, result.get(0).getDeviceId());
        org.junit.Assert.assertEquals(12, result.get(1).getDeviceId());
    }

    @Test public void collapsesMultipleBuiltInHardwareMicsToOneLogicalRoute() {
        java.util.List<AudioInputOption> inputs = new java.util.ArrayList<>();
        inputs.add(new AudioInputOption(7, AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone — Noise microphone", AudioInputOption.Category.BUILT_IN));
        inputs.add(new AudioInputOption(3, AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone", AudioInputOption.Category.BUILT_IN));
        inputs.add(new AudioInputOption(20, AudioDeviceInfo.TYPE_USB_HEADSET,
                "USB microphone", AudioInputOption.Category.USB));
        java.util.List<AudioInputOption> result = AudioInputCatalog.collapseBuiltInInputs(inputs);
        org.junit.Assert.assertEquals(2, result.size());
        org.junit.Assert.assertEquals("Built-in microphone", result.get(0).getLabel());
        org.junit.Assert.assertEquals(3, result.get(0).getDeviceId());
        org.junit.Assert.assertEquals(AudioInputOption.Category.USB, result.get(1).getCategory());
    }

}
