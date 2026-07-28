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


## App close contract

Back from the main recording screen means close the application, not leave a background worker. If microphone capture or local MP3 file work is active, the screen asks whether to keep the app open or close immediately. Close stops all workers and removes the notification. If only paused state or resumable server synchronization exists, Back closes immediately. Resume from PAUSED never opens the interrupted-recording dialog.
