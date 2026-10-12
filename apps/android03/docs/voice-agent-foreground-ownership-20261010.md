# Voice Agent microphone and network ownership — October 10

## Confirmed failure before revision
The original Voice Agent foreground service existed only to show an Android notification. MainActivity directly owned AudioRecord, microphone routing, network websocket, reconnect timers and answer playback. It stopped capture and closed the websocket from onDestroy, even though onStop incorrectly claimed microphone capture would survive backgrounding. It also sent microphone frames directly to WebSocket.send, which could reject frames under congestion without a durable recovery boundary; the capture loop simply incremented a drop counter.

These behaviors violated the recordings' requirement for continuous phone voice capture and the infra guidelines' requirement for independent supervision, explicit failure state and bounded queues. They contrast with the working Voice Button's RecordingService and JournaledMp3Recorder architecture.

## Reference app is read-only
Voice Button under apps/voicebutton was inspected as an implementation reference, not modified. Original uncommitted and untracked files must be preserved. A SHA-256 inventory of its one hundred non-build files was recorded externally before editing Voice Agent.

## Revised ownership
VoiceForegroundService owns the voice session and continuous network transport. UI activities bind and detach, but do not stop service audio on Home, Back, rotation, view recreation or temporary loss of network. The microphone has urgent thread priority and never calls the network or UI. A separate network sender drains a bounded two-minute PCM queue; a full queue stops capture with a visible error instead of silently treating lost speech as success. VoiceCaptureReadPolicy detects repeated zero reads and immediately rejects negative read results. The app foreground service holds and renews the CPU wake lock while capture is active. When the Activity is detached, service playback can continue assistant speech through the phone speaker.

Manual transcription and typed fallback remain supported. The service never owns SWAAG tools or workers. Jetson remains the authoritative voice gateway; history_get is used to replay durable conversation state to a newly rebound screen.

## Explicit limitations and further acceptance gates
This revision's two-minute PCM queue is volatile memory, not Voice Button's crash-durable disk journal or byte-offset durable upload ledger. Android process death and power loss are therefore not yet protected to the reference recorder's degree. The queued sender currently distinguishes WebSocket-accepted from durable server acknowledgement only indirectly; exact-once media retransmission after an uncertain socket failure remains a protocol requirement. These limits must not be presented as zero-loss compliance.

Tests must verify activity detach/rebind, real microphone routing, Bluetooth and built-in microphone, network drop and replay, bounded queue exhaustion, sender interruption, server history response, TTS continuation and phone-specific foreground restrictions. Android emulator tests do not establish physical OEM phone behavior. Do not claim release acceptance without repeated physical-phone conversations showing accurate transcript, actual agent response and audible playback.
