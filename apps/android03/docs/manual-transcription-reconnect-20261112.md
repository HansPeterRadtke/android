# Manual transcription reconnect verification

On November 12, 2026, the Android voice development baseline was `fb9a00b7bf0953640e9a884d1c8378949e64666a` (Voice Agent 1.7.17). The fix was built in a separate Nitro worktree.

## Reproduction

A tap on Transcribe before the server greeting left the original activity's pending flag set even after the upload began. Two greetings produced two `begin_manual_utterance` messages. In a second test, the first socket reported backpressure after the upload began. Replacing that socket produced a new start on its successor. The service's stable WebSocket facade resolves the current network connection on each send, so holding the facade cannot pin an utterance to its original transport.

## Change

A tap captures the selected PCM. The request can leave the queue once and stays associated with its concrete socket until the transcription result or interruption. On interruption the client keeps the recording for review and enables an explicit retry. A stale uploader or recognition result from the original connection cannot finish the replacement request. During screen recreation Android can transfer a still-unstarted request in memory. A bundle, including one saved by an earlier version, restores an ambiguous upload for review only.

## Verification

The two reconnect checks failed on the original application in the emulator. The final isolated Android build passed all 63 JVM tests and Android lint reported no issues. Eight instrumentation tests passed on a fresh API 34 emulator, including these three cases.

| Case | Observed Android behavior |
| --- | --- |
| Queued tap, repeated greeting, then connection loss before recognition | One start and one end on the first socket. All 8,192 PCM bytes matched. The replacement initially received zero audio. A fresh tap transferred the original bytes once, ignored a stale final from the first socket and displayed the replacement draft. |
| Disconnect during backpressure | The first socket saw one start and no end. The replacement received no start, PCM or end. |
| Recreate the screen before and after upload | The unstarted tap transferred all 4,096 original bytes once. Recreating again after the upload kept the recording available for review. The new connection received no audio. |

The other five cases covered draft, history, acknowledgement and recording recovery. The fixture used the actual Android activity, foreground service and upload thread, with in-memory WebSockets, synthetic PCM and a synthetic recognition result. It observed zero `submit`, `text_turn` or `commit_turn` messages. The isolated APK used loopback endpoints and a placeholder credential. The normal application package also built offline with a placeholder credential and was not installed. Physical phone, radio, microphone and deployed recognition were outside this run.

Build the isolated variant with `-PvoiceGuidelineTest` and `VOICE_AGENT_AUTH_TOKEN=local-test-placeholder`. Use `:apps:android03:testDebugUnitTest`, `:apps:android03:lintDebug`, `:apps:android03:assembleDebug` and `:apps:android03:assembleDebugAndroidTest`. In the isolated instrumentation runner select the three manual reconnect cases in `OutgoingMessageUiTest`, all four `DraftStateUiTest` cases, and `LifecycleUiTest#recoveredRecordingRemainsTranscribableAfterScreenRecreation`.
