# Android03 voice-agent automatic-send admission

The Android03 client uses package ID `myapp.app`. This document defines the
current code-level safety boundary for voice-agent commands; it is not a
claim that the application is installed on a phone or ready for hands-free use.

**Automatic voice sending is fail-closed until a trusted server-side owner
speaker decision exists for the current recorded utterance.** The current
Jetson voice worker has no securely enrolled and replay-resistant owner
speaker verification. A phone `client_state` field, a microphone confidence,
transcript quality score, a stored preference and a checked UI control are
**not** proof of who spoke the words.

`VoiceAutomationPolicy.effectiveAutoSend(autoTranscribe, requestedAutoSend)`
is the published Android03 call path, including preference restoration, the
outbound `client_state` and the settings toggle. It now returns **false**
while owner identity remains unverified, even if the prior saved preference
said `true`. The explicitly separate three-argument method is a future-only
admission hook; its third argument must be granted exclusively by an
**authenticated server** for the current utterance after actual speaker
verification and anti-replay checks. No existing phone UI/client preference
may manufacture that value. Until that server functionality is implemented
and released, client-side **manual review and explicit Send** remain the
only authority to submit spoken commands. Transcription and local recording
settings remain independent and unchanged.

The originally published Android03 policy at Git main
`6b749c521885227037f386bf1fb1a2ad5528ffea` returned **true** for
`effectiveAutoSend(true,true)` without a trusted speaker argument. This
exact previous behavior was compiled with Java 17 and reproduced in a
private, controlled source-level failure. The new policy passed independent
Java 17 assertions and its `VoiceAutomationPolicyTest` unit regressions.

The real Android03 client package was **not found** among the twenty-one
third-party apps on the connected Blackview XPLORE 1 (Android 15) at the
November 21 read-only wireless ADB check. The separate `com.multiverse.reverbprobe`
app was active; it must not be interrupted by a blind voice-app install or
launcher action. An offline Android03 build or unit test does not prove
physical microphone, speech, output audio, Bluetooth route, echo cancellation,
first-word retention, reconnect or trusted owner identification.

Before a production installation, independently establish the intended
Android03 client identity and source revision, create a trusted owner-speaker
verification/replay-resistance system, test the complete authoritative
server-to-client protocol with source-pinned credentials and a reversible
release, and perform explicit physical phone acceptance. Never embed
production voice tokens in a public APK, commit, example or test log.
