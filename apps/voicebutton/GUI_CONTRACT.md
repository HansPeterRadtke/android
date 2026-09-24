# Voice Button GUI contract

Module: Protected recording
User goal: Start, pause, resume, and finish a loss-protected recording quickly and confidently on the phone.
Operating states: ready, starting, recording, paused, saving/finalizing, recovery required, failed/degraded.
Real results: a locally protected recording and its finalized playable file.
Progress state: current recording duration and live microphone input while recording; background backup only when pending or delayed.
Diagnostics: raw uploader state, retry counters, remote chunk details, transcription internals, session IDs, stack traces, logs.
Forbidden default content: transcription state, raw uploader/session/protocol fields, worker state, healthy server detail, diagnostic counters, player internals.

Screen: Voice Button recording
Mode: action and monitoring
Purpose: Let the user know whether recording can proceed and control the current recording with minimal visual and motor effort.
Primary questions:
- Can I record now?
- Am I recording, paused, starting, saving, or recovering?
- How long is the current/open recording?
- Is captured audio safe on this phone?
- Is the selected microphone producing input while recording?
- What action can I take now?
Visible answers:
- Upper information area -> one human-language recording state; timer only for an actual/open recording; transition/error explanation only when needed.
- Local safety -> one concise “Safe on this phone” line only for an actual/open recording.
- Live input -> selected routed microphone, input state, and level only while recording.
- Idle setup -> folder and microphone controls only while a new recording can be configured.
- Fixed bottom reach zone -> one large Start/Pause/Resume/Recover action with inline disabled reason.
- Upper-left separated danger zone -> Finish/Silence alarm only while relevant; Finish requires consequence confirmation.
- Upload status -> always show overall byte percentage, overall progress bar, current recording filename with its own whole-file percentage when measurable, and number of recordings left to upload.
- Transcription status -> always show overall percentage, overall progress bar, current filename with current-file percentage when available, and number of files left to transcribe.
Secondary questions:
- Exact synchronization, Jetson, transcription, player/files, support and diagnostics -> More / detail screens.
Hidden by default:
- Raw transcription engine/session/protocol diagnostics; user-facing transcription overall/current-file progress remains visible.
- Healthy Jetson/server state.
- Raw uploader operation, sequence, chunks, retries, watchdogs and quarantine counters.
- Session IDs, logs, stack traces, internal booleans and raw config.
Trust state:
- Recording state and local safety are visible without scrolling.
- A backup delay is visible but explicitly does not imply local recording loss.
- Remote/transcription detail is available through More without competing with recording.
Actions:
- Start/Pause/Resume/Recover -> always-reachable fixed bottom action.
- Finish -> upper-left, separated from the frequent action, visible only for an open/current recording, requires confirmation.
- Folder/microphone -> idle-only setup controls.
- Player/files, synchronization retry, status, support and diagnostics -> More.
Rejected elements:
- Dashboard/card-stack composition.
- Permanent healthy Jetson/upload/transcription status.
- Transcription progress on the recording overview.
- Idle 00:00:00 timer.
- “Local protection” jargon when there is no audio to protect.
- Raw chunk/sequence/retry/session details on the overview.
- Primary action inside scrollable content.
- Finish adjacent to Pause/Resume in the lower reach zone.
- Disabled primary action without a visible reason.
- Fixed-height important text that clips at large font scale.
Acceptance tests:
- Start remains usable while old backup/transcription work exists.
- Primary action remains outside the ScrollView and at least 48 dp high.
- Finish remains outside the bottom action zone and requires confirmation.
- Idle state hides timer, local-safety line, live microphone signal, and transcription.
- Recording state shows timer, local safety, routed microphone, signal text, and level.
- Healthy upload and transcription remain visible as compact 100% completion states because overall completion is an explicit user requirement.
- Any pending backup remains visible during idle, recording, pause, finalization, and retry with one concise line plus progress bar; retry backoff says local recording remains safe.
- Important text wraps; no raw internal names appear on the default screen.

Metric dictionary:
- Recording duration: RecordingService snapshot duration for the current/open recording, hh:mm:ss, exact, confirms capture continuity.
- Microphone input: live recorder signal/level for the current recording, qualitative text plus level bar, confirms input activity without treating quiet as automatic failure.
- Backup pending: durable local upload ledger for finalized audio, bytes/percent when denominator is known, shown only while pending or delayed.
