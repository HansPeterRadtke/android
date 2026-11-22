package myapp.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class VoiceAudioPermissionPolicyTest {
  @Test public void builtInMicrophoneRemainsAvailableWhenBluetoothPermissionDenied() {
    assertTrue(VoiceAudioPermissionPolicy.mayCapture(true,false));
    assertTrue(VoiceAudioPermissionPolicy.mayCapture(true,true));
  }

  @Test public void bluetoothPermissionAloneCannotAuthorizeMicrophoneRecording() {
    assertFalse(VoiceAudioPermissionPolicy.mayCapture(false,true));
    assertFalse(VoiceAudioPermissionPolicy.mayCapture(false,false));
  }

  @Test public void Android14OffersBluetoothOnlyAsOptionalPickerAction() {
    assertTrue(VoiceAudioPermissionPolicy.shouldOfferBluetoothAccess(34,true,false));
    assertFalse(VoiceAudioPermissionPolicy.shouldOfferBluetoothAccess(34,true,true));
    assertFalse(VoiceAudioPermissionPolicy.shouldOfferBluetoothAccess(34,false,false));
  }

  @Test public void android11NeverRequiresBluetoothConnectRuntimePermission() {
    assertFalse(VoiceAudioPermissionPolicy.shouldOfferBluetoothAccess(30,true,false));
    assertFalse(VoiceAudioPermissionPolicy.shouldOfferBluetoothAccess(24,true,false));
  }

  @Test public void newPermissionBoundaryWorksAcrossSupportedVersions() {
    for (int sdk=24;sdk<=35;sdk++) {
      assertTrue(VoiceAudioPermissionPolicy.mayCapture(true,false));
      assertFalse(VoiceAudioPermissionPolicy.mayCapture(false,true));
      assertEquals(sdk>=31, VoiceAudioPermissionPolicy.shouldOfferBluetoothAccess(sdk,true,false));
    }
  }
}
