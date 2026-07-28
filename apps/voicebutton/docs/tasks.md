# Voice Button version 0.11

Implemented: dedicated capture and durable-writer threads, fifty-millisecond blocks, two-minute bounded queue, one-hundred-millisecond PCM and MP3 fsync, independent two-second chunks, rich per-chunk transfer ledger, `/audio/v2` folder-aware streamed upload, durable server acknowledgements, one-second reconciliation, persistent server manifest revisions, restart-safe chunk STT, immediate transcript return, local transcript assembly, folder dropdown with `<Create new>`, nested phone/server storage, legacy migration, wake lock, sticky unexpected-process recovery, Bluetooth HFP routing, archive playback, pause, resume, finish and explicit cleanup.

Required physical verification: prolonged built-in and Bluetooth capture, Android process kill, screen-off capture, long offline queueing, reconnect, near-full storage, phone reboot, explicit force-stop, large-font UI, folder creation, live transcript arrival and installation over previous debug versions.
