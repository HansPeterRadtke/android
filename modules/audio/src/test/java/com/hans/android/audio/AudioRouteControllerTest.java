package com.hans.android.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

public class AudioRouteControllerTest {
    @Test public void exactBluetoothAddressWinsCommunicationSinkSelection() {
        int exact = AudioRouteController.communicationDeviceScore(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "50:C0:F0:B8:D3:CE", "G06-BT",
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "50:C0:F0:B8:D3:CE", "G06-BT", true);
        int productOnly = AudioRouteController.communicationDeviceScore(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "50:C0:F0:B8:D3:CE", "G06-BT",
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "11:22:33:44:55:66", "G06-BT", true);
        assertTrue(exact > productOnly);
    }

    @Test public void communicationDeviceMustBeMatchingBluetoothSink() {
        assertEquals(-1, AudioRouteController.communicationDeviceScore(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "a", "headset",
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "a", "headset", false));
        assertEquals(-1, AudioRouteController.communicationDeviceScore(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "a", "headset",
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "", "phone", true));
    }
}
