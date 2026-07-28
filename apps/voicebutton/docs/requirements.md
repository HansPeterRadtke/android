# Voice Button requirements

Voice Button is a continuously recording, loss-bounded audio journal with durable phone storage, immediate chunk replication to Jetson, per-chunk speech recognition, transcript return, folders, playback, pause, resume, finish, and explicit close.

## Capture and phone durability

A dedicated urgent-priority capture thread SHALL drain AudioRecord in fifty-millisecond PCM blocks and SHALL perform no encoding, hashing, filesystem, JSON, HTTP, or UI work. Captured blocks SHALL enter a bounded producer/consumer queue sized for two minutes of PCM. Any block already returned by AudioRecord SHALL be delivered to the durable writer before a Pause, Finish, or Close end marker.

A separate high-priority durable writer SHALL write both a raw PCM recovery journal and compressed MP3 output. Both files SHALL be flushed and fsynced every one hundred milliseconds of captured audio. The writer SHALL close independently decodable two-second MP3 chunks, normalize their frames, calculate SHA-256, atomically publish metadata, and signal the uploader immediately. A partial CPU wake lock and foreground microphone service SHALL remain active while capture is running.

A crash-surviving PCM journal SHALL be the recovery source of truth. A partial MP3 for the same sequence SHALL be discarded. If an existing MP3 already matches its committed manifest size and hash, the redundant PCM journal SHALL be removed without re-encoding. Missing chunks SHALL stop preview or final assembly; partial recordings SHALL never be silently assembled.

Absolute zero-loss cannot be guaranteed against microphone hardware failure, kernel failure, sudden total power loss, destroyed flash, or device destruction. The application SHALL minimize and expose the remaining boundary: samples may exist in microphone hardware, the Android kernel, or the current sub-one-hundred-millisecond durability interval. Every detected software discontinuity SHALL be recorded as an explicit failure.

## Chunk ledger and replication

Each recording SHALL have a persistent schema-v2 manifest. Every chunk SHALL record sequence, start and end output sample, sample rate, duration, local filenames, byte counts, hash, creation, close and local-durable times, send attempt count, first and last send time, last error, server identity, server manifest revision, server receive and durable times, remote acceptance, transcript state, transcript text, engine, timestamp, and transcript error.

The uploader SHALL reconcile with Jetson before sending, compare sequence, byte count and SHA-256, send only missing chunks, retry uncertain outcomes, accept identical duplicates, reject conflicts, and poll at approximately one-second intervals while work remains. A chunk SHALL become server-durable locally only after Jetson returns a matching durable acknowledgement following server file fsync, atomic publication, directory fsync and manifest fsync. Local audio chunks SHALL not be deleted automatically after server commit.

The final recording commit SHALL require every contiguous chunk from zero, matching hashes and byte counts, and the canonical manifest SHA-256. A final local or server MP3 SHALL never be treated as complete when a chunk is missing.

## Server speech-to-text and transcript return

Jetson SHALL receive audio independently of speech recognition. Each durable chunk SHALL be queued for STT only after its audio acknowledgement boundary. STT SHALL run in a separate background worker and SHALL never block another upload. Results SHALL be stored as per-chunk JSON and text files beside the audio, fsynced, recorded in the server manifest, and exposed through status reconciliation immediately. Failures SHALL use bounded exponential retry metadata.

Android SHALL save each returned transcript chunk under the matching recording and rebuild the ordered transcript text file. Playback and archive screens SHALL show the transcript as soon as chunks arrive, including before the recording is finished.

## Folders

The main screen SHALL contain a folder dropdown with all local folders and exactly one `<Create new>` option. Folder creation SHALL be durable on the phone first and then idempotently synchronized to Jetson. New recordings SHALL be stored under the selected folder on both devices. Pause and Resume SHALL preserve the original folder. Legacy flat recordings SHALL migrate to the Default folder without deleting audio.

## Lifecycle

Unexpected Android process restart while actively recording SHALL preserve an auto-resume intent, recover durable PCM, reacquire foreground capture and wake lock, and attempt the same microphone. Pause, Finish and explicit Close SHALL clear auto-resume. Explicit Close SHALL stop capture after draining already-captured blocks. Force-stop, reboot restrictions, unavailable microphones, and operating-system foreground-service restrictions remain platform boundaries and SHALL be surfaced rather than hidden.


## Version 0.12 quality and visible feedback

Built-in and high-bandwidth microphones SHALL try forty-eight, forty-four point one, thirty-two and sixteen kilohertz input rates in that order. MP3 output SHALL be mono forty-eight kilohertz at one hundred ninety-two kilobits per second with high-quality LAME settings. Classic Bluetooth call mode remains limited by the headset and Android HFP profile to sixteen or eight kilohertz input, but SHALL still be resampled into the same forty-eight-kilohertz container. Platform automatic gain control SHALL be enabled when available. The encoded stream SHALL apply conservative adaptive gain between zero and twelve decibels, reduce gain immediately before clipping, and leave the raw PCM recovery journal unchanged.

The main screen SHALL display the exact application version and quality profile, a visually outlined recording-folder dropdown containing `<Create new>`, the selected folder name, current recording state, actual microphone route, live RMS and peak dBFS, a microphone-level meter, whether a signal is currently detected, and one determinate server-transfer progress bar. Transfer feedback SHALL state durable bytes, total bytes, durable chunks, total chunks and pending bytes. Quiet audio SHALL remain recording and SHALL be described as quiet rather than treated as failure.
