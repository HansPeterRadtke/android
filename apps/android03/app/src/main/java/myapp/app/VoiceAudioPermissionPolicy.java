package myapp.app;

/** Built-in/USB microphone capture does not need Bluetooth device discovery. */
final class VoiceAudioPermissionPolicy {
  private VoiceAudioPermissionPolicy() {}

  static boolean mayCapture(boolean recordAudioGranted, boolean bluetoothConnectGranted) {
    // Bluetooth is an optional routing enhancement, not microphone authority.
    return recordAudioGranted;
  }

  static boolean shouldOfferBluetoothAccess(int sdkInt, boolean recordAudioGranted,
      boolean bluetoothConnectGranted) {
    // Android 12+ nearby-device access belongs behind an explicit request
    // to choose Bluetooth equipment, never an unconditional launch prompt.
    return sdkInt >= 31 && recordAudioGranted && !bluetoothConnectGranted;
  }
}
