# Voice Button 0.98 — stop fake backup animations; honor explicit Exit

Recorded: October 18, 2026. Scope: the Voice Button audio-recording Android app, not the SWAAG conversational voice client. The currently public 0.97 APK still contains a busy, inaccurate progress display and task-close continuation behavior. Newer uncommitted 0.98 work in the isolated Voice Button checkout contained a redesign and exit control. This patch audits, tests and completes that work.

## Root problems and actual fixes

**Misleading UI:** The older recording screen displayed one transient row for the current session and additional per-file rows, inventing "Preparing file for upload" even while local audio was still being finalized, and moved its progress bars between indeterminate, zero, transferring and pseudo-completed states based on snapshots that arrived asynchronously. This created apparent endless activity even when bytes already uploaded or when the remote server was only being checked. Rendering an ever-changing internal operation stream as if it were durable upload progress was the underlying presentation defect.

0.98 keeps the recording screen focused on microphone state, duration, local protection, chosen input and the actual primary action. Upload receives at most one calm status line computed from **finished, uncommitted local sessions**; no speculative 0–100 percent or indeterminate upload bars are displayed on the main screen. Neither an active microphone session nor a stale no-session live operation creates a fake upload row. Pending bytes and per-file diagnostic detail are available separately through More when troubleshooting. Delayed, paused or quarantined backup states distinguish genuinely saved-on-phone files from verified Jetson completion.

**Constant retries:** The previous client tries to fetch folder metadata through a Jetson endpoint that requires authorization and can return 401 to an unpaired app. Failure to get a noncritical folder listing must not block durable audio uploads. The upload worker now backs off folder-list probes (five minutes for 401/403 and at least thirty seconds for other errors). Non-actionable permanent/quarantined work returns a terminal Worker result instead of spinning retry requests; a periodic safety net or explicit retry can re-evaluate the stored files. Retryable transfer failures still use bounded backoff, without changing locally captured audio or inventing receipt progress.

**Cannot close:** The previous application deliberately continued a foreground service when the user pressed Back or swiped away the task. 0.98 exposes a plainly labeled Exit button and a More menu Exit action; Back also means Exit. An active/paused recording requires confirmation, then the recorder writes/flushed PCM to its recovery journal, its paused/auto-resume state is durably persisted, uploads stop, foreground notifications clear and the Android task is removed. Home remains the explicit way to switch apps *while recording continues*. No destructive deletion occurs during Exit.

A further race was corrected on October eighteenth: explicit Exit now writes its lasting upload-disable policy immediately without calling WorkManager's initialization and cancellation synchronously on the main UI thread. The service cancels background work off-thread. An interrupted or deliberately closed worker returns success instead of scheduling retries behind the user's back. After the recorder thread finishes, the service waits for its single-threaded PCM journal metadata/paused-state queue to drain before destroying the executor; the old shutdown sequence could interrupt that queue before recording had been marked paused. Real disk stalls remain recoverable through open journals; shutdown cannot guarantee immediate completion of physically blocked storage.

## Tests and acceptance

Checks include AppSettings/RecordingUploadPolicy exit gates, retry policy, stable BackupOverviewText for empty, idle, paused, active, errored and previously uploaded sessions, network polling backoff, and Android screen instrumentation for a visible Exit action, durable upload suppression, Back behavior and stopped foreground service. Verify large/small layouts and no fake upload/progress widgets, full release lint, app/network/audio unit tests, and signed Android package metadata. Test actual phone with pending uploads and active microphone capture before claiming real-device completion.

The client's state machine must distinguish user-requested Close from a temporary network failure, preserve already accepted audio, prevent uploads and work rescheduling until the user explicitly reopens/resumes them, and allow manual upload thereafter without reinstalling or losing offline recordings.

The physical phone is **not connected over ADB** in this run. An emulator test and source-reviewed foreground-service logic are not a claim of installation on the phone. Never uninstall the installed app: an in-place, same-signer update preserves the local journal/database state. No working recording is intentionally deleted by publishing this fix.


## Additional October eighteenth shutdown fixes

The app's distributed APK is a debug build. Its `MainThreadWatchdog` originally posted a new main-thread probe every 250 milliseconds for the entire life of the Android process, including after the user closed all recording screens. This is unnecessary background execution and a misleading way to keep a cached process active. The watchdog now has a stop/restart lifecycle: explicit Exit stops the probe thread when the service has completed safe journal shutdown; reopening the UI starts it again. If WorkManager or a package-replaced event wakes the process while the persistent exit gate remains active, `VoiceButtonApplication` does **not** start the watchdog or diagnostic upload polling merely from process creation. The WorkManager upload gate remains disabled until a genuine user action resumes it.

This is not a claim that Android always immediately kills its cached process. Exit means **no visible task, no ongoing foreground service, no upload worker, no recorder and no app-owned periodic debugging activity**, once the safely flushed recording completes. Android retains control over cached process lifetime. Tests target the actual service state, not the misleading presence of a cached PID alone.

The shutdown now also waits for the single-thread recorder metadata executor to process the PCM journal-close and paused-state work submitted by the capture thread, avoiding an earlier race in which `onDestroy` interrupted that work immediately after the capture thread returned. When the storage device is blocked the close remains bounded and retains the recoverable open PCM journal instead of inventing a successful close.

## Protected folder-listing retry suppression

The Jetson recording server's private `/audio/v2/folders` GET deliberately
rejects an unpaired Voice Button with HTTP 401. The real phone's diagnostics
show this exact event repeating enough to create hundreds of megabytes of
server audit records. A backoff alone merely makes the same authorization
error recur. The uploader now skips private folder enumeration when no token
is configured, without pausing legacy chunk replication or the locally
maintained folder tree. When a supplied token is rejected with 401 or 403,
it records one diagnostic and suppresses unchanged-credential repeats.
Changing the token resets the guard; network changes also re-evaluate it.
Server-side authorization remains mandatory for access to private folders.
Tests verify that no-token, backoff, network and credential states cannot
cause unauthorized polling.

Credential changes are also observed by an already-running background
WorkManager uploader, not only by a currently bound Activity/Service. The
uploader refreshes its HTTP credential and private-folder reconciliation
state when a new token is saved, without logging the credential.
