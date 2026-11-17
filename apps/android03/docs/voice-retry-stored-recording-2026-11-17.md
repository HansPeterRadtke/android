# Explicit saved-voice re-transcription UI — November 17, 2026

**Status:** isolated, compiled Android source branch, not installed or
deployed on the owner's physical phone. A separate Jetson server
development branch implements the matching control. This work does not
retest the previously denied Android lost-playback-acknowledgment case.

## What was missing

The earlier voice client could display and replay a private original
microphone recording after every speech recognizer failed. But it
could not retranscribe that stored audio after the recognizer recovered:
the user had to record the same words again.

## Added explicit control and status

The updated Jetson source advertises saved_audio_retry_protocol=1
during the authenticated WebSocket hello. Only a client observing this
exact capability may offer retry_untranscribed_audio. The normal
already-running older production server does NOT support the protocol,
so this client sends no retry request to it.

When canonical conversation history has a latest asr_unavailable entry
that really has original audio, the Android Settings dialog shows an
explicit "Retry saved recording transcription" control below the
recent audio/replay controls. It is enabled only with the matching
server capability, a live authenticated socket and a legitimate
untranscribed artifact ID, and when the microphone is not recording
and no retry is already in progress. A strict pure-Java policy refuses
older servers, missing/wrong artifact identifiers and active capture.
The control sends only the turn identity, not a filesystem path or the
original audio again. It never submits a SWAAG command.

The request is user initiated. Successful socket queueing is displayed
only as a requested operation, not as proof of transcription. The
server's transcribing state updates the UI while the existing
recognizer runs. A successful asr.final updates the editable review
draft; the awaiting_send state explicitly requires the user to review
and press Send. Retry failures preserve the original saved-recording
notice and Replay availability. Reconnect and history-refresh logic
tracks only the latest failed artifact and clears obsolete identifiers
when a real later draft has arrived. The capability is rechecked after
each server hello to prevent cross-session or old-server requests.

## Tests and limitations

The offline Gradle debug build and JUnit suite passed 78 tests with
zero failures or skips, including four focused pure policy tests.
The debug APK was assembled in the private project build directory.
No physical phone, Bluetooth, Android speaker or microphone hardware
was modified or tested by this change. The previously denied Android
lost-playback-ACK acceptance was not rerun or disguised.

The matching Jetson source executed eleven focused no-action tests,
the complete server regression suite, and an explicit opt-in real
Whisper large-v3/three-WebSocket reconnect acceptance using a
checksum-verified pre-existing speech recording. The model ran once,
the original audio remained unchanged, and the result was ONE
editable unsent draft.

This is not automatic speech turn completion. It does not establish
foreground-speaker identity, semantic action authorization, or
production service readiness. Until a separately verified release,
the live Android and Jetson services cannot be assumed to support it.

Source and tests are committed in a dedicated Android branch.
Temporary tests, source recordings, original audio, private tokens
and model result logs stay outside Git.
