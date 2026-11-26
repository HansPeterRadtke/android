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
