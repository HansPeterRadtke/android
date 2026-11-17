# Android voice review and saved-recording recovery — emulator acceptance, November 17, 2026

**Status:** isolated Android test build and targeted instrumentation, NOT
deployed to the owner's physical phone or the production Jetson service.

## Why this is more than unit-only policy checks

The earlier Android source passed pure Java tests for the latest-voice-draft
policy. Jetson had separately tested original recorded speech, real Whisper,
and prerecorded reply streaming across several WebSockets. A remaining
gap was whether the actual Android MainActivity rendered the review,
replay and error stages correctly across server events and recreation.
The existing emulator on Nitro provides that UI-level observation without
using the owner's physical phone.

The new AndroidJUnit4 class VoiceReviewReconnectEmulatorTest runs only in
the special GUIDELINE_TEST Android package. In this build the voice and
health endpoints are pinned to 127.0.0.1 port 9, with a test-only bearer
token and a package ID distinct from the normal installed Voice Button.
The instrumented Activity receives exact server event JSON in memory.
The tests do not perform microphone capture, call SWAAG, synthesize TTS,
click Send or Retry, or exercise the previously denied physical lost-ACK test.

## Rendered UI observations

Four initial tests passed with the previous latest-draft fix:

- A latest unsubmitted draft is visible and explicitly sendable in the
  editor even when automatic sending is selected globally. Activity
  recreation retains the pending draft without rendering it as an
  already confirmed user message.
- An older unfinished draft is not resurrected after a newer completed
  conversational turn.
- An empty latest draft cannot enable an executable Send control.
- A restored ASR-final event, awaiting-review state and late canonical
  history snapshot retain the same current unsubmitted draft.

Two additional tests passed for all-ASR-outage recovery. The actual
Activity displays the saved-recording warning and Replay availability,
keeps Send disabled for an untranscribed artifact, and does not invent
user messages after restoring an audio-only history item. A
server-capability gate prevents the Retry control from being used
against the older production server.

## Newly reproduced UI race and repair

A seventh emulator test identified a separate source defect. An ASR
artifact event for an older saved failed transcription set its retry
identity, but a following ASR-final event for DIFFERENT newer valid
speech did not clear that identity. The previous handler only cleared
it when both turn IDs were equal. The wrong recording could remain
offered as the current retry target until another history snapshot,
although the server independently rejects a retry of non-latest audio.

The specific negative test failed against the original handler,
showing that the old untranscribed artifact was still the UI target.
The narrow correction clears that stale retry identity and its
inflight flag when a valid newer ASR-final event contains a nonempty
turn ID and recognized words. An empty or untrusted recognition result
does not discard the saved recording or fabricate a new message.
The canonical server still decides whether any explicit audio retry
is allowed.

After this repair the entire seven-case emulator UI suite passed.
The complete offline Android JVM unit suite also passed 81 tests
with zero failures or skips, and the isolated debug APK built.
The first four tests passed before the new UI regression was added;
two more checked recording recovery before the final stale-retry
case was reproduced and repaired. Baseline failure, seven-case
success, unit suite and APK build logs remain in Nitro's private
evidence root, outside Git.

## Reproduction and acceptance boundary

Build the isolated Android repository's apps/android03 Gradle project
offline with the GUIDELINE_TEST flag and fake test-only credential.
Assemble the debug app and debug instrumentation APK, install only those
two distinct test packages on the existing emulator and invoke only
VoiceReviewReconnectEmulatorTest through AndroidJUnitRunner. Do not
run ConnectedVoiceWorkflowTest, PlaybackReceiptReconnectTest or any
opt-in live voice-chain suite in this no-action UI acceptance.

These are actual on-emulator screen and lifecycle checks with injected
server protocol events, not a physical microphone, AudioTrack, new
speech synthesis or production WebSocket acceptance. Real recorded
PCM-to-reply data and reconnect delivery were tested separately on
Jetson with an inert answer. The live Jetson service remains on older
source, the dedicated Thor semantic classifier is still unavailable,
and foreground-speaker/overlap acceptance remains unresolved.
No production rollout or physical phone test is claimed.
