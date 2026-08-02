# Voice Button version 0.11

Implemented: dedicated capture and durable-writer threads, fifty-millisecond blocks, two-minute bounded queue, one-hundred-millisecond PCM and MP3 fsync, independent two-second chunks, rich per-chunk transfer ledger, `/audio/v2` folder-aware streamed upload, durable server acknowledgements, one-second reconciliation, persistent server manifest revisions, restart-safe chunk STT, immediate transcript return, local transcript assembly, folder dropdown with `<Create new>`, nested phone/server storage, legacy migration, wake lock, sticky unexpected-process recovery, Bluetooth HFP routing, archive playback, pause, resume, finish and explicit cleanup.

Required physical verification: prolonged built-in and Bluetooth capture, Android process kill, screen-off capture, long offline queueing, reconnect, near-full storage, phone reboot, explicit force-stop, large-font UI, folder creation, live transcript arrival and installation over previous debug versions.


Version 0.12 adds a visible version and quality badge, a blue recording-folder dropdown with `<Create new>`, live microphone RMS and peak feedback, signal detection, a determinate durable-server progress bar, forty-eight-kilohertz one-hundred-ninety-two-kilobit MP3, platform automatic gain control and conservative adaptive encode gain. The server protocol remains audio-v2 and already accepts the larger chunks without a compatibility change.


Version 0.13 adds four-kibibyte resumable HTTPS parts, per-part fsync acknowledgements, restart-safe server offsets, partial-byte phone ledger state, byte-level GUI progress, short network timeouts, two-hundred-fifty-millisecond initial retry, five-second maximum retry, audio-first reconciliation and temporary-network wording.


Version 0.14 adds an upload no-progress watchdog, active-connection cancellation, complete uploader replacement on manual retry, uploader watchdog diagnostics and a Copy debug clipboard report containing the complete local and remote-part ledger.

Version 0.15 adds task-swipe-safe foreground recording, sticky background synchronization, continuity wake-lock renewal, immediate network callbacks, automatic microphone recovery, a repeating alarm-stream failure signal, high-priority error notification, in-app Silence alarm and Pause automatic recovery controls.


Version 0.15 performance repair removes per-fsync and per-upload-part diagnostic writes, coalesces repository reconciliation on a dedicated worker, keeps live duration and microphone telemetry in memory, throttles notification rebuilds, avoids duplicate microphone enumeration, and places the primary action inside the top trust-state card.

Version 0.16 adds the LibVLC broad-format player, decoded waveform seeking, configurable 0.25x to 8x speed range and step, independent skip values, presets, volume, mute, loop, autoplay, sleep timer, queue previous and next, Android media-session controls, Thor Rubber Band R3 fine studio rendering, studio WAV export, RAM and cache inspection, app-folder library, Storage Access Framework browser, recording and folder rename, atomic recording move, external file rename and move, and Jetson metadata/location reconciliation.


Version 0.17 rebuilds recorder, player, and library screens from the infra GUI question-first and rejection-gate guidelines. It adds stable scaled text geometry, one-primary-action hierarchy, progressive disclosure, coalesced rendering, background microphone and folder enumeration, recycled library rows, bounded verified clipboard summaries, full diagnostic file export, and batched noncritical diagnostic writes.
