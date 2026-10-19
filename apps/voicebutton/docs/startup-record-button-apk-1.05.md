# Voice Button 1.05 — recorded startup delays, Start pipeline, and APK footprint

This analysis was grounded in the October 19 Android 12 Ulefone diagnostics, build artifacts and source code. No handset mic testing is inferred from emulator unit tests.

## Measured prior release delays

In app 1.00, `ui.main.create` occurred at 15:52:43.660 UTC and `service.create` at 15:52:44.188 UTC. `service.initial_state_resolved` did not report READY until 15:53:01.833 UTC: **17,645 milliseconds** of recording-service initialization. Another cold start resolved READY in **4,883 milliseconds** at 11:15:42.688 UTC. The varying delay is consistent with repeated on-flash directory and manifest scans and WorkManager handoff, not evidence that LibVLC was playing video at startup.

In that same phone run, Start was pressed at 15:53:06.319 UTC, `recording.start_requested` arrived at 15:53:06.375, microphone resolution was logged at 15:53:08.785, durable recording metadata existed at 15:53:10.603, and the actual recorder opened at 15:53:11.597 UTC. **The user waited 5,278 milliseconds between pressing Start and the first confirmed capture**. Approximately 2.4 seconds preceded microphone validation, 1.8 seconds elapsed while durable session metadata was being created, and microphone activation itself took about 1 second. The old Start flow synchronously enumerated every historical recording even when the currently open session was already known. This was unnecessary for 156 historical sessions.

The seven-minute-and-four-second Bluetooth microphone capture generated approximately 13.6 MB of raw 16-kHz mono PCM. After Finish was pressed at 16:00:15 UTC, its MP3 conversion appeared as `recovery.pcm_chunk_encoded` at 16:00:49 UTC. Finalizing audio while idle was real CPU work, not network uploading; it can take many seconds on the handset. The original 1.00 support report showed `FINISHING` and `pending=unknown`, which is not proof of missing or deleted audio. The later Jetson receiver contained 172 committed and 172 transcribed sessions.

## Changes

The service now performs one authoritative manifest pass to find its current unfinished/interrupted session and populate the first snapshot. It does not re-read all historical manifests to determine open, interrupted and backup states separately. Its second read-only status handle skips duplicate folder-index repair. The main screen folder selector uses the inexpensive reader rather than repeatedly running authoritative metadata repair while the user watches the Start button.

The cold READY snapshot no longer recursively counts the existing 10+ GB audio library. That count runs on a cancellable, low-priority, off-main-thread storage-meter worker **after** READY has been published. Pressing Start immediately preempts the background size scan, which does not share a manifest-write mutex. Until verification completes, the debug report says `local_bytes=not_yet_measured`, rather than incorrectly reporting zero. Idle periodic recounts are throttled to five minutes, and a finished MP3 explicitly invalidates the byte-count cache. No scan of the full library runs in the microphone thread or UI rendering.

Starting a new recording checks the already-known unfinished-session ID with one durable manifest read instead of rescanning every previous session. New diagnostics divide Start into the preflight check, microphone resolution, fsynced metadata creation, and hardware AudioRecord start. The service also logs separate ownership wait, primary store open, secondary store open, manifest scan and initial snapshot times. This permits the actual Ulefone after-update numbers to be compared against the verified baseline rather than inventing speed results from emulation.

The background upload Worker now uses interruptible recovery so an incoming foreground recording can take exclusive storage ownership without waiting for an entire historical recovery pass. WorkManager periodic-job initialization moves off the app startup main thread. Existing durable file writes, capture wake locks, and the production LAME encoder were not disabled or bypassed to make the interface seem faster.

## Why the APK was nearly 200 MB

The previously published universal Android debug APK was **204,873,166 bytes** (about 195 MiB). Its native libraries alone accounted for approximately 198 MB because the build packaged **four entire processor architectures**. The architecture-specific `libvlc.so` decoder was around 49.0 MB for x86-64, 47.0 MB for x86, 42.8 MB for ARM64 and 38.4 MB for ARMv7; native C++ libraries added further bytes. The Voice Button player uses LibVLC to play recordings with variable speed and track support. It does not need four architectures on one ARM64 handset.

Build option: `./gradlew --offline --no-daemon -PvoicebuttonTargetAbi=arm64-v8a :apps:voicebutton:assembleDebug`. This includes only native ARM64 libraries and leaves normal universal debug builds available for x86 emulator development. The output must be checked for exactly one `lib/arm64-v8a` architecture, the existing signing certificate and versionCode 105 before it replaces the public APK. Nothing about the ABI filter deletes recordings; the app's private storage is preserved during an in-place update.

## Acceptance boundary

The code changes and JVM/emulator build tests must pass, but actual speed and Bluetooth audio continuity require measurements on the physical Ulefone with version 1.05 installed. The key evidence will be the new `service.initial_state_resolved` phase timings, `recording.start_preflight_complete.elapsed_ms`, `recording.session_created.total_pre_capture_ms` and `recording.started.start_duration_ms` events. If the total remains high, the component responsible can be isolated without weakening PCM durability.

## Built ARM64 result and subsequent GUI review

The actual ARM64-only debug APK built successfully at **59,169,692 bytes (about 59.17 decimal MB or 56.43 MiB)**. It contains only `lib/arm64-v8a/` native libraries, including the LAME encoder and the ARM64 VLC player. Compared with the old 204,873,166-byte universal APK, this removes 145,703,474 bytes, approximately 71% of the original installer. Both APKs carry exactly the same debug signing certificate fingerprint, so the smaller APK can update the previous installation without replacing app-private recordings. No bundled user videos were found; the size was dominated by repeated architecture-specific native VLC player binaries.

A final UI review found that the main screen independently refreshed all recording folders on both `onStart` and service connection, before authoritative index repair finished. Besides wasting flash I/O, that could replace the user's saved folder selection with Default during a repair. These two cold-start refreshes were removed; the status listener now performs the first refresh only when the storage service has resolved its state. Returning to an already-initialized Activity still triggers an asynchronous refresh. The original UI recording explanation also falsely claimed that MP3 compression runs during microphone capture. It now correctly states that PCM is durable locally and MP3 encoding follows pause or finish.
