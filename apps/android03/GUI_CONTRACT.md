# Voice Agent UI Contract

## Module Purpose Contract

Module: Voice Agent.

User goal: talk or type to the local agent with minimum friction, see and correct exactly what will be sent, hear and control the answer, and keep a manual path when voice automation is unreliable.

Real results: the final user-submitted text, the agent reply text, user recording when retained for review, agent reply audio, and compact active background-work state when it materially needs attention.

Progress states: connecting, ready, recording/listening, transcribing, awaiting send, thinking, speaking, paused, reconnecting/degraded, and unavailable. Progress is state, never fabricated percentage.

Diagnostics: individual backend components, protocol generations, queue counters, reconnect details, raw health payloads, model/service names, worker identifiers, paths, logs, and exact timings. Diagnostics are hidden from the normal screen.

Primary metrics: one overall readiness state; while recording, selected microphone plus signal; while audio exists, current, remaining, total/available duration.

Forbidden default content: raw component lists when healthy, developer/debug text, version/build detail, connection generations, server/model names, disabled historical-audio load buttons, permanent settings/diagnostics button rows, and backend-centric labels.

## Screen Question Contract: conversation

Primary mode: action plus monitoring.

The first view must answer, in order:

1. Can I communicate successfully now? One compact state near the top answers Ready, Connecting, Listening, Thinking, Speaking, or the concrete degraded condition. Healthy dependency details stay hidden. If voice is unavailable but typed text works, say that directly.
2. What have I and the agent said? Conversation history is the dominant surface. User and agent messages must be visually distinguishable without relying on color alone.
3. What exactly will I send next? One persistent editable current-message field is separate from history and receives live/final transcription. Standard selection, cut, copy and paste remain available.
4. How do I act now? The composer keeps microphone/record and Send directly beside the editable text. Manual Transcribe appears only when locally buffered audio actually exists and automatic transcription is disabled.
5. Can I control current audio? The user-recording player appears only while an unsent recording exists. The agent player appears automatically when reply audio exists/arrives. Each exposed player has play/pause/resume, stop, seek, jump backward, jump forward, current, remaining and total/available duration.

Secondary questions:
- Voice automation mode, microphone choice, preferred spellings/vocabulary and installed version live in Settings.
- Component health details and raw diagnostics live behind the compact readiness state or an Advanced/Diagnostics action inside Settings.
- Background worker state is invisible when idle and compact when working; blocking/attention-required state becomes conspicuous.

Interaction rules:
- Typed text remains usable with microphone permission denied, STT unavailable, or voice service unavailable.
- Automatic transcription can be disabled. Automatic send can be disabled and is impossible when automatic transcription is disabled.
- Fully manual mode records locally until Stop, exposes the own-audio player, then allows Transcribe, edit, and Send.
- Live transcription/manual-send mode continuously updates the current editor but never sends until Send.
- Continuous mode transcribes and sends finalized turns automatically while preserving the editor and manual controls.
- Partial transcription may update the current editor but never becomes agent input. Final verified text replaces partial text in place. User edits replace it in place. Only the retained submitted text enters history and correction learning.
- Agent messages are not user-editable in normal mode.
- Manual player Pause/Stop is immediate. Automatic barge-in requires semantic interruption intent; VAD/volume alone never cancels speech.
- Reconnection preserves conversation and the current unsent message/recording. Stale sockets cannot mutate current UI state.

Layout and hierarchy:
- Phone portrait: compact top state plus settings icon, conversation takes remaining flexible height, contextual audio directly above the composer, composer pinned at the bottom.
- Wide/short/tablet: conversation and composer/context may become two columns, but the same priority order remains.
- Settings and diagnostics must not consume permanent first-view rows.
- Primary touch targets are at least forty-eight dp and text uses scalable units. No fixed-height important text.
- At two hundred percent font scale and supported resizing, readiness, conversation, current editor, microphone and Send remain reachable.

## Rejection gates

Reject the build if any of these are true:
- Healthy first view shows raw Server/STT/Agent/TTS component status as permanent text.
- Settings and Diagnostics occupy permanent large first-view buttons.
- Current message, microphone, Send, or the conversation is below diagnostic or secondary content.
- A visible audio recording lacks seek, pause/stop and all three time values.
- The app claims voice Ready when microphone permission or a required voice dependency is unavailable.
- Typed text is disabled because microphone/STT is unavailable.
- Automatic send cannot be disabled.
- A partial transcript reaches the agent.
- Loud noise/backchannel alone cancels agent speech.
- Emulator-only evidence is used to claim the physical-phone voice journey works.

## Acceptance evidence

Automated deterministic tests: editor state transitions, automation constraints, edit/correction semantics, connection ownership/reconnect, player math/state, invalid protocol/config inputs, history rendering, and non-trigger interruption cases.

Android integration: normal portrait, two-hundred-percent font, short/wide, Settings/version/vocabulary, process recreation, permission denied/revoked, offline startup, server unavailable, reconnect, and persisted unsent text/audio.

Real physical phone is mandatory before release completion: microphone capture and visible signal, live transcription, manual recording/transcription, typed fallback, agent text, Lux reply audio, pause/seek/stop, semantic interruption plus backchannel non-trigger, background/foreground, network loss/reconnect, and process recreation. Server logs must confirm the physical build and actual media frames for the voice cases.
