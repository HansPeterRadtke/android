# Android03 reply playback and durable server acknowledgment

The SWAAG voice server must not treat downloading an assistant's audio as
proof that the user actually heard it. A `WebSocket.send`, receipt of the last
network PCM packet, or successful `AudioTrack.write` only means bytes were
*queued*, not necessarily played through the hardware audio device.

The voice assistant player now waits for Android `AudioTrack`'s **playback
head** (the OS count of PCM frames consumed by the selected output route) to
reach the final written frame after the server has sent the audio-end event.
This confirms software-level playback progress, not whether a person heard
sound: muted volume, headphones or Bluetooth hardware can still fail to make
speech audible, and those require real device acceptance.
A bounded wait runs on the existing playback thread, never the Android UI
thread. No `playback_completed` acknowledgment is sent for audio that is
empty, paused, manually stopped, cancelled, still buffering, an ordinary
user-requested replay, or whose output device never reports completion.
The previously supported playback controls, layout, microphone capture and
bounded PCM buffer remain intact. Pending sound after network EOF still
shows the playback state instead of falsely displaying conversation-ready.

When Android reports that the playback head reached the end, the client
sends the *exact* server-provided `turn_id`, `stream_id`, and audio `generation`
as a
`playback_completed` message through the existing protected WebSocket.
The client retains a pending receipt until an authenticated matching
`playback_ack` confirms that the **server persisted** the completed state.
After a connection failure the pending same-stream proof may be sent again,
which the server already handles idempotently. A different turn, stream ID
or generation cannot acknowledge the current one. A new answer, explicit
cancel or user stop fences stale callbacks and discards old playback state.
This does not grant any new authority to execute SWAAG tools or speech
commands; the unknown-speaker manual review rule remains unchanged.

Source and unit checks:
`VoicePcmPlayerView.java`, `VoicePlaybackCompletionPolicy.java`,
`VoicePlaybackReceiptPolicy.java` and corresponding policy JUnit tests.
The server's existing actual WebSocket no-action test with a previously
recorded reply separately demonstrated **exact PCM restoration and normal
playback ACK with no fourth replay**. That server test was not rerun as part
of this change; it cannot replace actual phone output-device verification.

A successful offline Android debug build with an **empty voice-service
credential** proves compilation and deterministic policy tests only. It
cannot authenticate to the private server and must never be installed or
advertised as a usable voice application. Still required: physical Android
`AudioTrack` measurements on the owner's intended device, Bluetooth
routing and interruption handling, microphone echo, accurate owner-speaker
verification and production-safe server rollout. Those are not proven by
this source patch.
