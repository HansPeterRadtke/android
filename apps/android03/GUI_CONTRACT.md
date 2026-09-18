# Voice Agent UI Contract

## Module Purpose Contract

Module name: Voice Agent.

User goal: communicate with the local agent by voice or text without losing control, see exactly what text will be sent, interrupt intentional agent speech, review both sides of the conversation, and continue foreground conversation while durable SWAAG work proceeds independently.

Operating states: manual text, recording locally, transcribing, live transcription, ready to send, sending, listening, thinking, speaking, paused playback, reconnecting/degraded, server unavailable, STT unavailable, model unavailable, microphone blocked, background working, background needs input, background completed, background failed.

Real results: user-confirmed submitted text, assistant reply text, assistant audio, the user's current unsent recording, completed SWAAG result, and a SWAAG input request.

Progress state: explicit connection/readiness, microphone capture state, transcription state, foreground thinking/speaking state, audio playback time state, and compact background-worker state. Unknown progress is activity state, never a fabricated percentage.

Diagnostics: raw WebSocket events, service versions, queue counters, reconnect counters, dropped frames, exact device IDs, raw health payloads, worker IDs, transport generations, and low-level audio routing. Diagnostics are hidden by default.

Metric dictionary: microphone -> selected human-readable input plus signal/no-signal; voice path -> Ready/Connecting/Reconnecting/Unavailable; STT -> Ready/Transcribing/Unavailable; agent model -> Ready/Thinking/Unavailable; background worker -> Working/Needs input/Completed/Failed; player -> current, remaining, total, buffered duration where applicable.

User-language mapping: transport and service internals are mapped to concise human states. Errors state what is unavailable and which fallback remains usable.

Forbidden default content: raw logs, raw service names, raw worker identifiers, JSON, queue counters, reconnect attempt numbers, protocol fields, developer implementation notes, and duplicate status rows.

## Screen Question Contract

Screen: Voice conversation.

Mode: action plus monitoring.

Purpose: let the user communicate successfully in any automation mode, know what text will be sent, know whether voice services are healthy, control current/received audio, and see background-agent attention without inspecting infrastructure.

Primary questions:
- Can I communicate successfully right now, by voice or at least by text?
- What text is currently mine, and what exactly will be sent?
- Is the system listening, transcribing, thinking, speaking, reconnecting, or unavailable?
- Can I stop or interrupt agent speech right now?
- What did the agent answer?

Visible answers:
- A compact readiness strip states voice readiness and the usable fallback when degraded.
- The conversation history is the main content and visually distinguishes You and Agent.
- A separate always-available current-message editor contains the live transcript or typed text and remains editable before sending.
- The microphone control and Send action remain directly reachable.
- While capturing, concise microphone source and signal state are visible.
- When user or assistant audio exists, the relevant compact player shows play/pause, stop, seek, jump backward, jump forward, current time, remaining time, and total/buffered duration.
- Background work appears only when active, recently completed, failed, or waiting for input.

Secondary questions:
- Which microphone is selected? -> Settings.
- Is automatic transcription enabled? -> Settings.
- Is automatic sending enabled? -> Settings, dependent on automatic transcription.
- Which app version is installed? -> Settings, always visible there in human-readable form.
- Exact connection/STT/model diagnostics? -> Diagnostics.
- Explicit vocabulary/project spellings? -> Settings vocabulary editor.

Hidden by default:
- raw logs, health JSON, worker IDs, connection generations, queue sizes, raw device IDs, protocol timings, internal model paths.

Trust state:
- Ready means microphone path, server transport, STT and agent model are ready enough for the selected automation mode.
- If voice is degraded but manual text still works, the strip explicitly says voice is unavailable and text remains usable.
- Reconnecting states that live audio is not being sent until restored.
- STT failure never disables typed text sending.
- Agent-model failure blocks agent replies and is shown distinctly from STT failure.
- Playback state never claims Playing unless audio is actually advancing.

Automation continuum:
- Fully manual: automatic transcription off, automatic send off. User may type directly, or record locally, replay/edit, press Transcribe, edit result, then Send.
- Live transcription/manual send: automatic transcription on, automatic send off. Speech updates the current editor live; user may edit and Send.
- Continuous conversational: automatic transcription on, automatic send on. A finalized turn sends automatically, but the current text remains visible and manual controls remain available.
- Automatic send is disabled when automatic transcription is disabled; contradictory settings are not allowed.
- Wake word is optional and not required for this release.

Current-message semantics:
- Current editable text is separate from immutable conversation history.
- Vosk partials may update current text; verified final text replaces the partial in place.
- User edits replace the current text in place.
- Only the final user-retained submitted text becomes conversation history and vocabulary evidence.
- Agent replies are not user-editable.

Transcription-improvement semantics:
- The system stores semantic evidence from recognized text to final user-retained submitted text, not raw keystrokes.
- Typed-from-scratch or corrected exact spellings are strong vocabulary evidence.
- Deleted mistaken tokens are not treated as desired vocabulary.
- Explicit vocabulary entries are durable and user-editable.

Interruption semantics:
- Manual Pause/Stop interrupts playback immediately.
- Automatic interruption requires semantic interruption intent, not merely loud audio or generic voice activity.
- Acknowledgements, laughter, coughs, and incidental/background speech must not automatically cancel playback.
- Explicit interruption language such as stop, wait, hold on, no, or a new substantive request may cancel after semantic classification.

Audio-player contract:
- The user's current unsent recording and the latest received assistant audio each have a real player when available.
- Controls: play/pause/resume, stop, seek timeline, jump backward, jump forward.
- Stable labels expose current, remaining and total time; while assistant audio is still arriving, available duration grows rather than showing a fake final duration.
- User unsent audio remains replayable until submission/clear/new recording.
- Player state survives ordinary UI refresh and does not create duplicate playback streams.

Actions:
- Record/Stop recording -> primary voice action.
- Send -> primary message action and always available for nonempty manual text when agent transport is usable.
- Transcribe -> visible only when local buffered audio exists and automatic transcription is off.
- Play/Pause, Stop, seek, jump backward/forward -> contextual audio actions.
- Settings -> microphone, auto-transcription, auto-send, vocabulary, version.
- Diagnostics -> bounded secondary technical details.

Representations:
- Readiness -> compact status strip.
- Conversation -> scrollable history.
- Current message -> persistent Material text editor, separate from history.
- Audio -> compact player card adjacent to the current/assistant audio context.
- Background SWAAG -> compact status row only when meaningful.
- Diagnostics -> separate bounded dialog.

Rejected elements:
- Debug dashboard on the opening screen.
- Raw service names, IDs or protocol details in normal status.
- Automatic sending with no user-disable path.
- Automatic transcription with no manual fallback.
- Replay-only audio control without pause/stop/seek/time.
- Treating arbitrary microphone activity as interruption intent.
- Hiding the user's current editable message inside chat history.
- App version available only in server logs.
- Fixed-height important text that clips at large font.
- Multiple simultaneous WebSocket owners for one conversation.

Acceptance tests:
- Opening portrait screen answers readiness, current conversation, current editable message and primary actions without scrolling past developer detail.
- Manual text sending works when microphone permission is denied or STT is unavailable.
- Fully manual recording buffers locally without transmitting audio until Transcribe is pressed.
- Live-transcription/manual-send and continuous modes work and can be changed from Settings.
- Partial -> verified final -> user edit -> submitted text updates one current-message representation without duplicate history lines.
- User correction evidence stores only the recognized-to-final semantic difference.
- User and assistant audio players support play/pause/resume, stop, seek, jumps and stable current/remaining/total values.
- Assistant available duration grows while streaming.
- Manual playback stop is immediate.
- Automatic interruption classifier cancels explicit interruption and does not cancel acknowledgement/laughter/noise regression cases.
- Ready/connecting/reconnecting/voice-degraded/STT-unavailable/model-unavailable/permission-blocked states are human-readable and expose the remaining usable fallback.
- Settings shows the exact installed app version.
- Main controls work at 200% font scale and in portrait and wide/short window configurations without overlaps or clipped primary controls.
- Offline, reconnect, server unavailable, STT unavailable, background/foreground, process recreation and stale-state paths are tested.
- Only one WebSocket generation owns the conversation; stale callbacks cannot mutate current state.
- Bounded queues/backpressure prevent unbounded memory growth.
- Diagnostics remain available but hidden by default.
