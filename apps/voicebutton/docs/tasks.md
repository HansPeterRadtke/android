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


Version 0.18 moves recording-service recovery and every service command to a named single-thread executor, moves LibVLC construction and all playback operations to a dedicated audio-priority HandlerThread, opens Storage Access Framework content through a retained file descriptor, removes synchronous diagnostics and store construction from activities, makes application diagnostics initialization asynchronous, reduces player and recorder UI polling, caches recursive storage-byte scans, rate-limits buffering callbacks, enables debug StrictMode, and adds a main-loop stall watchdog.

Version 0.21 rolls back the task-scoped PlayerService and checkpoint layer to the last reported stable 0.18 activity-owned LibVLC architecture, keeps complete multiline filenames and extensions, installs a durable uncaught-exception stack recorder, and standardizes publication to exactly one Explorer artifact named `voicebutton-debug.apk`.


Version 0.22 adds a task-scoped foreground playback service with platform MediaSession notification controls, five-second AtomicFile checkpoints, exact logical timestamp restoration, Home/app-switch continuation, task-swipe shutdown, and safe recording-service pause and worker shutdown. Explorer publication remains exactly one overwritten file named `voicebutton-debug.apk`.


Version 0.23 fixes the file/player crash caused by querying the native `LibVLC.version()` symbol before the asynchronous LibVLC engine had loaded. Technical summaries now report `loading` until native initialization completes and convert any native-symbol failure to `unavailable` instead of crashing the main thread.


Version 0.24 removes the artificial forty-four-character line break from library filenames. Each complete filename is now rendered by one unrestricted TextView and wraps naturally against the actual screen width, with no ellipsis, marquee, max-line cap, or hyphenation; the extension remains part of the visible text. Media notification actions now use dedicated monochrome Previous, Rewind, Play, Pause, Forward, Next, and Close vector icons instead of reusing the colored microphone logo.


Version 0.25 adds single-query phone-folder enumeration, immediate cached library rows, exact library/main-screen state restoration, stale-query cancellation, lightweight recording browsing, unique timestamp-plus-session recording filenames with legacy migration, signed final filename synchronization to Jetson, engine-readiness transport gating, and fixed-size pressed/disabled button feedback.


Version 0.26 fixes playback termination and permanent Starting state. MP3 previews/finals are published by atomic rename so an open player file is never truncated underneath LibVLC. Decode errors remain terminal instead of being overwritten by a later Stopped event. Replay after Ended/Stopped seeks to zero, EndReached publishes the exact final position, and opening/starting has a fifteen-second failure timeout. Old app-recording caches are invalidated and missing cached recording paths are rejected.


Version 0.27 removes the regressed player readiness/start wrapper and restores the direct 0.24 playback control path. Atomic MP3 publication, stale recording-cache rejection, terminal decode errors, and replay-from-zero remain. Folder metadata is repaired from the actual on-disk folder/session manifests so existing folders cannot disappear when `folders.json` is incomplete.


Version 0.28 fixes Default-only folder selection. MainActivity no longer asks the still-initializing RecordingService for folders; it opens the lightweight browse store directly and refreshes again when service initialization completes. Folder repair now discovers folder IDs and names from every session manifest, including manifests physically stored under a different folder directory.


Version 0.29 makes PlayerPlaybackService task-scoped at the Android manifest level. Swiping the Voice Button task away now forces Android to destroy the player service, stop foreground execution, and explicitly cancel the player notification. Home and normal app switching still leave the task present and keep playback active.
