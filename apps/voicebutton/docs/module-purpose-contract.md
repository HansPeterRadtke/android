# Module Purpose Contract: Reliable audio recorder

Module name: Reliable audio recorder.

User goal: Record long-form audio without losing completed speech, pause and resume the same recording, play any local recording, and let compressed audio synchronize with the server independently of the recording controls.

Operating states: ready, preparing, recording, pausing, paused, resuming, finishing, preparing playback, waiting for network, reconciling, server complete, recovery required, cleaning, failed, and unknown.

Real results: a locally playable MP3 recording, its duration and local size, and a server-complete confirmation for the same ordered recording manifest.

Progress state: current duration, number of durable MP3 segments, local bytes, server-pending or server-complete state, and current microphone route.

Diagnostics: raw session ids, file paths, HTTP responses, thread state, stack traces, individual retry timers, and raw manifest JSON. Diagnostics are hidden by default.

Visible metric dictionary: see the source, formula, scope, units, precision, freshness and decision definitions in `../GUI_CONTRACT.md`.

User language mapping: internal capture and upload states become READY, RECORDING, PAUSED, FINISHING, WAITING FOR NETWORK, SYNCHRONIZING, SERVER COMPLETE, RECOVERY REQUIRED, and FAILED, each with a human-readable explanation and remedy.

Forbidden default content: raw protocol fields, hashes, sequence numbers, HTTP codes, implementation paths, LAME settings, logs, and the full metadata object.
