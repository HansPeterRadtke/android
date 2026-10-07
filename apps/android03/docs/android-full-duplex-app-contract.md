# Android Voice Agent Transport Contract

This document describes the current Android-to-SWAAG voice path. The former direct HTTP `/fdx` polling design on port 13482 is obsolete and must not be used by `android03`.

## Responsibility boundary

The Android application is the human interface. It captures and plays audio, displays and edits the current user message, exposes manual fallbacks, sends finalized text, shows agent text and operational state, and reconnects across network/lifecycle changes.

Jetson is the real-time voice transport and speech boundary. It owns authoritative voice endpointing, speech recognition, semantic interruption handling, WebSocket media delivery, and the presentation-side transcript/audio state needed by the phone.

SWAAG remains the working conversational/orchestration system. The voice application and Jetson glue must not replace SWAAG reasoning, worker scheduling, durable question handling, or tool execution. Only finalized/submitted user text reaches SWAAG; partial ASR is presentation state only.

## Client endpoints

The authoritative Android endpoint values are resources in `app/src/main/res/values/voice_config.xml`:

- `voice_ws_url` for the persistent WebSocket.
- `voice_health_url` for explicit health/diagnostic checks.

Do not duplicate host names, ports, tunnel addresses, or fallback URLs in Java source or this document. Deployment changes update the resource/config source of truth.

## Audio uplink

After the WebSocket handshake, Android continuously sends binary PCM while automatic transcription is enabled and the microphone session is active:

- signed PCM sixteen-bit little-endian,
- mono,
- sixteen kilohertz after Android-side resampling,
- frame duration from `voice_frame_ms`.

Local VAD is only a UI/diagnostic hint. It never owns transport and never decides that reply playback should stop. Jetson receives the microphone stream while reply audio is playing and performs semantic interruption classification. Android stops automatic reply playback when Jetson sends the explicit audio-cancel event.

In fully manual transcription mode Android retains the recording locally instead of streaming it into the agent path. The user can replay it, explicitly transcribe it, edit the resulting text, and explicitly send it.

## Text lifecycle

Partial ASR may update the visible current-message editor, but unstable partial text is not submittable. A final transcript stabilizes the draft. If the user deliberately edits a visible partial in manual-send mode, the user-edited text takes ownership and later ASR updates do not overwrite it.

The current editable message is separate from confirmed conversation history. A submitted turn is the only user text promoted into history and into SWAAG. Typed-from-scratch messages use the same submitted-turn boundary.

Automatic transcription and automatic sending are independently user-controllable, with the constraint that automatic sending is disabled when automatic transcription is disabled.

## Background workers and questions

Android does not own worker or question state. Jetson projects SWAAG's global orchestrator event stream into compact UI events. The source of truth for outstanding questions remains SWAAG's complete `orchestration.questions.list` inventory, including worker identity, question id, criticality, importance, revision state, reason, and provisional assumption where applicable.

Blocking or important questions may make the background status conspicuous. Optional/minor questions must remain discoverable without interrupting the current conversation; the Android first screen therefore exposes a compact question-count surface even when the worker status itself is otherwise idle/hidden. The user can ask the SWAAG orchestrator to review the exact outstanding questions. Android must not maintain a competing durable question store or infer answers itself.

## Downlink and playback

Agent reply audio arrives as binary PCM frames bracketed by JSON audio start/end events. Playback begins as soon as the first usable PCM arrives; it does not wait for the complete reply. While audio is still growing, the player keeps Play/Pause, Stop, seek, backward/forward jump, current position, remaining available time, and total/available duration visible and updates them as data arrives.

Manual Stop is immediate. Automatic interruption requires Jetson's semantic decision; local VAD, loudness, coughs, laughter, acknowledgements, or background speech alone are not cancellation authority.

## Connection and lifecycle

The WebSocket is long-lived and uses generation ownership so stale sockets cannot mutate current UI state. Reconnect uses bounded backoff/jitter. The current unsent draft and retained manual recording survive activity recreation. Active microphone capture uses the Android foreground-service path required by the supported platform lifecycle.

Typed text remains usable when microphone permission, STT, or TTS is unavailable. The primary status reports the user-visible degraded effect; component/raw details remain in diagnostics.

## Verification

A passing desktop/unit build is not release evidence. Automated tests must cover submission gating, automation constraints, stale connection ownership, semantic interruption non-trigger behavior, player math/state, lifecycle recovery, and invalid/failure paths. Android instrumentation must cover the screen contract, growing-audio controls, lifecycle, offline/degraded state, and supported window/font configurations. Release completion additionally requires the physical-phone journeys listed in `../GUI_CONTRACT.md`.
