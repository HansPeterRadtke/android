# General Voice Agent: thin Android client

Purpose: a separately installable general Android voice interface, not the
old KITT on-device inference feasibility study. Phone captures microphone PCM,
transports to a compatible server, receives streaming PCM and state, handles
playback and editable text controls. ASR, LLM, TTS and turn-taking are server-side.

The primary implementation is the existing Android03 MainActivity, protocol,
player and modules/audio. The new app's sourceSets reuse their source paths
without copying network code. Only optional presentation/configuration hooks
are added to Android03, leaving its default behavior unchanged.
Voice Button remains an unrelated durable recording and file-management app.

New app-owned code: GeneralVoiceAgentActivity, GeneralVoiceProfile,
GeneralVoiceVisual. App has a separate package ID, storage, Android Keystore
credential container, icon and settings. Server switching clears the previous
bearer. WSS is mandatory. No secret is included in the APK.

User can configure palette, animation, display size, and avatar using the
Android image picker. Requested voice ID is an optional server-side
client_state field. The current Jetson server uses Phonon Easton by default
and does not yet support selecting arbitrary voice IDs. A real voice change
requires a backend catalog, allowlist, and verified server acknowledgement;
client configuration alone is not evidence that the voice changed.

Build from Android repository root:
  ./gradlew --offline :apps:voiceagent:assembleDebug \
    :apps:voiceagent:testDebugUnitTest :apps:android03:testDebugUnitTest

APK: apps/voiceagent/app/build/outputs/apk/debug/voiceagent-debug.apk

Pre-release checks: no bundled speech models or local inference runtimes;
separate installation; config validation, display and avatar persistence,
secure absent-credential behavior, WSS handshake and actual audio exchange,
hands-free turn-taking, playback interruption, reconnect, offline drafting,
headset routing, background/foreground and accessibility on physical phones.

Emulator and unit tests do not establish physical-phone microphone input,
real authenticated server audio or end-to-end response latency.

## One-time personal-phone provisioning (development only)

The owner-side release is debuggable for local Android ADB testing. A trusted
operator can pipe the private token from a protected credential file into
the app's private files using Android's run-as sandbox, never as a command
argument, URL query, or baked APK value. The next app launch encrypts it with
Android Keystore and erases the staged bytes. Release builds ignore staging.
Do not stage a token in shared storage, expose it in tooling output, or
place credentials in the downloadable Raspi APK. A general public release
will need explicit authenticated enrollment/secure pairing.

## 2026-11-26 latest verified deployment state and unresolved release gate

- Latest source checkpoint on Android repository main: 4ae4a71.
- Latest APK version 0.3, debug build, 2,805,118 bytes, SHA-256:
  68e30c4078b669fa4d23e8fbf1186cdf3ef8f3cc3b3a036c685817848ccbb33f.
  The exact bytes are staged on Raspi at
  /data/var/web_portal/uploads/Multiverse-VoiceAgent-0.3.apk.
  Android emulator install/launch and 58 new-client unit tests plus 49
  original Android03 tests passed. No native inference libraries in APK.
- NOT accepted as a connected physical phone release.
  Blackview XPLORE 1 at 192.168.0.48:5555 was reachable through G3 ADB,
  but the new package com.multiverse.voiceclient was not installed
  as of the last observed package inventory.
- Actual ADB transport tests: regular shell commands, a 24-byte local
  TCP-listener round trip and a tiny helper via ASCII/base64 command worked.
  Large ADB push/exec-in installs and writes repeatedly broke at about
  19-30 seconds, emitting EOF/device-offline; receiver files remained zero
  bytes. Restarting adbd in TCP mode did not fix bulk transfers.
  A temporary phone netcat listener accepted a short G3 TCP probe, but
  larger direct TCP transfer failed. Windows temporary inbound firewall
  rule and Python test HTTP servers were stopped/removed, and phone
  temporary files cleared. Do not interpret a sender-side '1 file pushed'
  line as successful installation without the independent device digest.
- Server health and authentication: Jetson gateway loopback
  127.0.0.1:13483 exposed the expected secure WS handshake, and a
  local authenticated test conversation received hello and history
  events without assistant model actions. The live server requires the
  private bearer. The app has no bearer packaged; a new installation
  will not connect until securely paired/provisioned into Android Keystore.
  The debug APK supports only owner-operated one-time app-private
  ADB bootstrap, not unauthenticated auto-enrollment.
  Do not print, embed or publish the bearer.
- Voice selection: client_state.voice_id is not supported by the live
  Jetson gateway yet. The app-side setting alone does not choose a
  different voice. Easton and Qwen services on Nitro exist separately.
  An acknowledged, allowlisted server-side implementation is required.
- Remaining acceptance: physical install, secure enrollment,
  phone authenticated WebSocket hello, bidirectional PCM, complete
  speech and playback, interruption handling, selected server voice,
  and real end-to-end first-audio measurements. Do not promote to a
  stable release before these pass.

## Additional current observation, 26 November 2026, Blackview connection investigation

Device inspection through G3 wireless ADB at approximately 18:45 Berlin time
confirmed Blackview XPLORE 1 user zero has installed
com.multiverse.voiceagent version 0.15, the old phone-inference feasibility app.
It **does not** have com.multiverse.voiceclient, the new general thin client,
installed, and there is no Multiverse VoiceAgent APK in its Downloads folder.
This proves a mismatch on the *inspected Blackview only*; another phone or
profile cannot be inferred from this check. The Jetson gateway's live health
was OK with zero active sessions, and the existing authenticated WebSocket
health/hello test was successful from a server-side test client. A healthy
server does not authenticate a phone that lacks its device-held credential.

Further independent transfer diagnostics: Blackview wireless ADB can run short
shell commands and can receive a 24-byte TCP probe from G3. A multi-kilobyte
TCP probe and larger ADB/exec-in transfers stall and leave zero-byte files.
Phone-to-G3 Java HTTP downloads started but timed out with zero payload;
the Android shell's custom Java app_process download helper was killed by
Android before an APK was downloaded. Small test jars were transferred
with individually verified base64 chunks, so the failure is not explained
by a corrupt helper jar. Even a restart of TCP ADB did not establish a
reliable multi-megabyte path. Temporary G3 HTTP listener/firewall rule and
phone-side testing files were removed. Do **not** claim the real phone app
has been installed. Only a separate verified user/device install would
change this status.

The current 0.3 beta has app-private, one-shot *debug-only* ADB token
provisioning with Android Keystore; it intentionally has no server bearer
embedded in its APK. After actual installation, per-device secure pairing
is still required for real authenticated voice transport. There is no
owner-free production onboarding or supported dynamic server voice switching
in this build. Do not disable gateway authentication or publish the private
bearer in a generic APK merely to make the status say connected.
