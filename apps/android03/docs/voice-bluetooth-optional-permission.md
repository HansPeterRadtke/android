# Microphone access without Bluetooth permission

`RECORD_AUDIO` is required to record from the phone microphone. On Android
12 and newer, `BLUETOOTH_CONNECT` can also be needed for Bluetooth headset
routing and device discovery. It **must not** be a prerequisite for the
phone's built-in microphone, a USB microphone or a wired audio input.

The prior Android03 code incorrectly required BOTH permissions on Android
12+, and prompted for Bluetooth access automatically. Actual disposable
Android 14 emulator acceptance confirmed the user-facing defect: with
`RECORD_AUDIO` granted and `BLUETOOTH_CONNECT` denied, the voice app still
reported “Microphone permission required.” The original emulator had no
usable physical audio device and no network connection, so this is proof of
the permission/UI gate failure, **not** microphone recording quality.

`VoiceAudioPermissionPolicy` now separates basic recording from optional
Bluetooth discovery. The source calls `requestMissingAudioPermissions()`
only for `RECORD_AUDIO`. The microphone picker exposes a clearly labeled
“Enable Bluetooth microphones (optional)” action on Android 12+ when that
permission is absent. Only pressing the action requests nearby-device
permission. If denied, the app must keep built-in microphone and manual
text workflows available and must not mislabel the denial as missing
microphone permission. Existing `AudioInputCatalog` already ignores
unavailable Bluetooth profiles when access is denied.

Source: Android03 canonical client and shared audio catalog. Deterministic
regressions: `VoiceAudioPermissionPolicyTest.java`, plus an isolated, offline,
no-credential Android 14 emulator test. This neither authenticates an owner
speaker nor authorizes automatic SWAAG commands. The live Android phone,
shared test emulator and Jetson voice service are not modified.

## Independently observed implementation acceptance, November 22

- The pre-fix app, built from canonical `main` `24c94d4`, launched on a
  dedicated offline Android 14 emulator with **RECORD_AUDIO granted** and
  **BLUETOOTH_CONNECT denied**. Its real screen incorrectly said
  **“Microphone permission required”** and tapping Mic requested the
  nearby-device/Bluetooth permission. Original UI and permission-state
  evidence are retained privately.
- A source-distinct debug APK built offline with a **blank voice-service
  credential** completed **49 genuine Android03 JUnit tests**, with **0
  failures, 0 errors and 0 skips**. Five of the tests cover the new
  permission-selection policy. The APK SHA-256 is
  `ccc6aae8b472f1f35f713e9d195c70cd6496b41fc1a9dda4299ed79cb85b52d9`.
  It is for non-connecting tests only.
- Replacing the app **only on the disposable emulator** with that APK,
  without changing either permission, changed the real app status from
  **“Microphone permission required”** to **“Ready”**. The app recognized
  **“Current: Built-in microphone”**, and its microphone picker displayed
  **“Built-in microphone”** plus **“Enable Bluetooth microphones (optional)”**.
  Opening the picker did not automatically request nearby-device permission.
- Selecting that optional Bluetooth entry **explicitly** caused Android's
  normal nearby-device permission prompt. Selecting **“DON’T ALLOW”** left
  microphone permission granted and the app still showing **“Ready”**.
  The app remained offline with no service credential, no audio device, no
  SWAAG/tool/model requests and no real recorded speech.

The new private test evidence is under
`/data/var/swaag-benchmarks/android03-emulator-noaction-20261122/`
and `/data/var/swaag-benchmarks/android03-bt-permission-20261122/`.
The shared emulator, Blackview phone and installed Jetson voice server were
never changed. This passes the permission/UI acceptance gate; it **does not**
prove physical microphone input, voice-agent authentication, Bluetooth
headset playback or conversation readiness.
