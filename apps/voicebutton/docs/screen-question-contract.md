# Screen Question Contracts

## Screen: Reliable recording controls

Mode: action and operational monitoring.

Purpose: Let the user safely start, pause, resume, or finish one recording while immediately understanding whether audio is protected and synchronization can continue.

Primary questions: Am I recording, paused, finishing, or ready? Is my captured audio protected locally? Which microphone is selected and actually routed? What is the current recording duration? What is the safe next action?

Visible answers: a text health strip at the top; selected and actual microphone route; current duration and total local storage; one primary Start, Pause, or Resume action; a separate Finish action only while a recording is open; and a compact button leading to the recordings archive.

Secondary questions: Which past recording should I play? How much space does each recording use? Is a selected recording complete on the server? These belong to the recordings archive.

Hidden by default: raw ids, file names, hashes, retry counters, protocol responses, stack traces, and individual segment details.

Trust state: local protection state, actual microphone route, network or reconciliation state, explicit failure meaning, and whether the current recording is safely paused or permanently finished.

Actions: Start is visible only when no recording is open. Pause is the primary action while recording. Resume is the primary action while paused. Finish is visually separate because it permanently closes the current recording. Microphone selection is disabled while recording. Delete-all is not on this operational screen.

Rejected elements: one large player per recording, raw transfer tables, logs, tiny controls, ambiguous colored dots, and repeated archive data.

Acceptance tests: primary state and remedy remain above the fold; Pause never marks the recording finished; Resume keeps the same recording id and sequence; Finish creates a playable final MP3; disabled controls include a visible reason; large font does not clip the primary actions; and phone portrait has no overlapping text.

## Screen: Recording archive and selected player

Mode: selection and exact-record playback.

Purpose: Let the user select any recording, including the current one, understand its local and server state, and play the selected playable MP3 with seeking.

Primary questions: Which recording is selected? Is it current, paused, interrupted, finished, or server-complete? Can it be played now? What is its duration and local size? Where am I in playback?

Visible answers: a lazy selectable list of all recordings; one selected-recording inspector; one Play or Pause button; one seek control with elapsed and total time; and a text reason when playback is unavailable.

Secondary questions: What microphone created it and how many durable segments exist? These appear in the selected-recording inspector, not every list row.

Hidden by default: raw ids, local paths, hashes, HTTP state, and per-segment audit data.

Trust state: local MP3 availability, recording state, server pending or complete, and any failure that affects playback or synchronization.

Actions: selecting a row is visible. Play is enabled only for a complete local MP3 or paused-current preview. The current active recording remains listed, but playback is disabled with the reason that playing phone audio into an active microphone is unsafe. Delete all local audio and cache is separated at the bottom and requires a consequence preview.

Rejected elements: eagerly rendered player cards for every file, nested tables, hidden playback errors, automatic playback, and destructive controls adjacent to Play.

Acceptance tests: the current recording appears immediately; selecting another row changes the single player; paused current audio is playable; active current audio shows why Play is disabled; all closed recordings remain selectable; cleanup confirmation states exactly what is deleted and what remains on the server; list rows and controls survive large font settings.


## App background contract

Back, Home, or swiping the task removes the activity only. An already-started microphone foreground service continues recording, journaling, synchronizing, alarming, and recovering until the user presses Pause or Finish. The persistent notification provides Pause, Finish, and Silence alarm actions. Android force-stop and device shutdown remain external boundaries.

## Performance contract

The live microphone meter and duration may refresh several times per second, but they SHALL use in-memory state only. Filesystem scans, manifest parsing, recursive storage-size calculation, notification rebuilding, and diagnostic fsync SHALL NOT run in the live UI loop. Repository reconciliation SHALL be coalesced on a background worker. Hundred-millisecond durability fsync remains mandatory but SHALL NOT emit one diagnostic record per fsync.


## Version 0.17 GUI acceptance contract

The recorder overview answers only five questions: whether capture is active, how long it has run, whether local protection is active, whether the selected microphone has signal, and whether server synchronization is complete. It exposes one dominant recording action and one context-dependent secondary action. Folder and microphone selection are compact controls. Player, diagnostics, retry, refresh, version information, and raw status details live behind More.

No normal overview may display worker names, protocol revisions, hashes, chunk sequence details, HTTP errors, retry backoff, or full service explanations. Every live text region is single-line, ellipsized, and allocated a stable height derived from the active Android font scale. Normal duration, level, and synchronization updates SHALL NOT add, remove, resize, or reorder views. Structural controls update only when the recording state, selected source, or available action changes.

The player overview answers what is loaded, whether it is playing, where playback is, how to play or pause, how to skip, what the current speed is, and how to reach the library. Settings, file actions, engine details, memory, cache, and studio controls live behind More. The studio progress slot retains its geometry while idle.

The library overview answers which source is being browsed, the current location, and which items are available. Management and import actions live behind More or the selected item's long-press menu. List rows SHALL reuse existing views rather than reconstructing their child hierarchy while scrolling.

Copy support summary is bounded to twenty-four thousand characters and verifies that Android retained the exact clipboard text before reporting success. The complete per-session and per-chunk ledger is exported to a user-selected text document and is never placed on the clipboard. Ordinary diagnostic events are batched without a per-event fsync; warning and error events remain immediately durable.
