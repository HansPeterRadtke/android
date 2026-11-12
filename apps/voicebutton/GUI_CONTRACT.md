# Voice Button GUI contract

This is the current authoritative screen contract. It consolidates earlier versioned proposals. The recording screen prioritizes recording state and controls. A current-recording transfer is visible when pending, while transcription is secondary; whole-archive percentages and raw engine, protocol, session, retry and storage diagnostics belong outside the main view. Essential text wraps and must not be clipped at large font sizes.

## Recording screen

The screen answers whether recording can start, whether it is recording or paused, how long the current recording is, whether captured audio is protected locally, which microphone is routed, and what action is available. Only the selected active (or newest pending) recording's transfer shows a progress bar: the known, server-acknowledged bytes for that one file. Other recordings are a waiting count, not a denominator. Transcription is one subordinate text line when pending, failed, manually configured or stale; there is no aggregate percentage or transcription progress bar on the recording screen. Empty queues and historical completions consume no main-screen space. Unknown or stale server state must identify uncertainty and the age of the last successful snapshot; it never establishes that the current queue is empty.

Start/Pause/Resume/Recover stays outside the scrolling information area. Finish stays in the separated upper toolbar and requires consequence confirmation. Information starts with the recording state, then the current-recording transfer, optional subordinate transcription status, and idle setup. Microphone route and level appear during capture. The normal phone overview must fit without scrolling after padding, duplicate labels and empty placeholders are removed. Landscape uses two columns for recording state/setup and transfers. Scrolling remains only for genuinely constrained accessibility windows or extensive content, while toolbar and primary controls remain reachable. Branding may hide when constrained. Idle hides duration, local protection and live microphone details. Recording shows those details.

The current transfer displays its recording filename, phase and bounded per-file percentage. No empty queue blocks, historical percentages, or fixed queue viewports are permitted on the main screen. Long filenames wrap or can be inspected in the Library. Unknown MP3 denominators use indeterminate progress, never an invented percentage. An audio transfer can reach 100 percent sent while final server commit is still pending. Offline and failed states identify what remains local and the remedy: check Internet, then More → Retry synchronization / Send pending recordings. Quarantined audio prevents backup-complete claims and identifies recovery as the next step.

More contains player/library, retry, support, diagnostics, automation and privacy. Automatic upload and transcription have separate persisted controls. Manual Send pending recordings and Transcribe uploaded recordings remain available. Foreground and scheduled workers honor the upload control. Work already submitted to the server is not recalled.

## Player and library

Player shows file identity, playing/paused/stopped state, position, total and remaining time, speed, seek, Play/Pause, Stop, skips, Library and More. Toolbar and transport stay reachable. Stop shares the transport row; Library and More share a navigation row. Landscape separates audio position and waveform from queue, mode and navigation controls into two columns. An absent waveform and inactive Studio progress consume no height. Normal phone layouts fit without scrolling; long content remains accessible. Long titles, failure explanations and labels wrap. Action and seek targets are at least 48 dp high. More holds settings, file operations, memory/cache, manual waveform generation and engine details.

An existing file never bypasses capture exclusion. Starting capture pauses the playback engine before opening the microphone. Studio handoff preserves logical position and requested playing/paused state; native paused seek priming stays muted. Source/speed changes invalidate late results and cancel network requests. The playback service owns a monotonic sleep deadline, which survives activity recreation and prevents resumed autoplay after expiry.

Library keeps source, location and items readable at large font sizes. Up and mode controls are at least 48 dp; rows reuse views. Complete identity is available through details. Active capture produces an explicit playback-disabled reason. Destructive actions require consequence previews. Copy-and-delete moves verify source and destination content before source deletion, including providers with unknown lengths.

## Lifecycle and performance

Home and switching views leave active foreground work running. Back/explicit close warn when active work requires it. Task removal follows the current controlled-exit behavior: capture is paused and journaled, workers stop, and playback saves its checkpoint before stopping. Reopening restores the checkpoint and its prior intent subject to capture exclusion and sleep expiry. Force-stop, shutdown and hardware failure remain external boundaries.

Live duration and microphone updates use in-memory snapshots. Filesystem scans, JSON parsing, recursive byte counts, log writes and fsync do not run on the UI update path. Queue changes are coalesced and reuse rows. Diagnostic queue admission and retained bytes remain bounded even during repeated errors.

## Acceptance matrix

Render recorder idle, recording, paused, saving, failure, stale/offline and long queues; player normal and long-title states; and library layouts in phone portrait, phone landscape and tablet portrait at 1.0 and 2.0 font scale. Assert fixed controls remain within bounds and meet 48 dp targets, and assert normal phone overviews have zero scroll overflow. Inspect wrapping, scrolling, full filename details and content-sized queue rows, including the player with a loaded waveform. Device validation additionally covers actual microphone routes, Bluetooth, background operation, TalkBack and OEM lifecycle behavior.

## Metric dictionary

Percentages are bounded to 0–100. Higher completion means less remaining work and proves only the named evidence. Never turn a failed refresh into a fresh success.

| Metric | Source / formula | Scope, units, precision | Freshness / uncertainty | Decision |
| --- | --- | --- | --- | --- |
| Recording duration | Service duration from durable audio plus current recorder samples / actual sample rate | Current recording; hh:mm:ss, whole seconds | Live snapshot; paused duration stops increasing | Confirm capture continuity |
| Microphone level | Recorder signal and normalized peak level | Routed microphone; qualitative text and level | Live only during capture; silence alone is not failure | Check selected input |
| Local protection | Journal/manifest state, overridden by capture or storage failure | Current recording; explicit text | Service snapshot | Pause, preserve audio or recover |
| Historical upload (diagnostics only) | Durable remote bytes plus bounded partial acknowledgements / known local encoded bytes | Phone recording set; whole percent; NOT the main-screen bar | Verified ledger; may include unmeasured raw recordings | Investigate historical backlog |
| Per-file upload | Matching segment bytes plus partial durable offsets / that recording's encoded bytes | One named recording; whole percent | Commit is separate from byte completion | Identify current or stalled file |
| Upload remaining | Sessions still requiring transfer or verified commit | Local recording set; integer files | Local snapshot; quarantined files stay incomplete | Know whether backup is complete |
| Historical transcription (diagnostics only) | Server completion count / server committed recording count | Server recording set; whole percent; NOT the main-screen bar | Last successful response; marked last-known when stale | Diagnose historical backlog |
| Per-file transcription | Server frame progress for the current identified recording | Named server recording; whole percent | Indeterminate during model load/start or unknown totals | Wait or retry |
| Transcription remaining | Server pending list/count | Server recording set; integer files | Unknown if never loaded; last-known after failed refresh | Plan remaining work |
| Status age | Elapsed time since last successful receipt | Transcription snapshot; seconds/minutes | Increases through failures | Distinguish current evidence from cache |
| Playback position | Service logical timeline, adjusted for Studio speed | Selected source; hh:mm:ss | Live snapshot | Seek/resume accurately |
| Playback total | Source duration metadata | Selected source; hh:mm:ss | Unknown until loaded | Judge length |
| Playback remaining | max(0, logical total minus logical position) | Selected source; hh:mm:ss | Same uncertainty as total and position | Judge listening time |
| Speed | Validated setting or acknowledged Studio render speed | Current source; multiplier, two decimals | Applied mode and fallback are explicit | Understand rate |
| Studio progress | Copied/uploaded/downloaded bytes / expected bytes; named render phase if unmeasurable | Current requested source/render; whole percent or phase | Generation-scoped; discarded on cancellation | Wait, cancel or use Instant mode |
| Studio cache | Sum of cached source, render and waveform byte lengths | App cache; binary byte units | Off-thread inspection and checks before writes | Clear unused cache or adjust limit |
| Recording/file size | Local file or durable manifest bytes | Selected file; binary byte units | Provider length may be unknown | Understand storage; never use size alone as move proof |

Defaults, ranges, units, precedence and safety role of limits are in `docs/runtime-configuration.md`.


## November 12 revision: current transfer, reliable Resume

Start, Pause and Resume are always reconciled against each fresh service snapshot; changes in microphone enumeration, permissions or pause state cannot leave a stale disabled action solely because the screen's structural key did not change. A submitted action is acknowledged immediately and protected against accidental duplicate taps until a confirmed state change, explicit failure or bounded watchdog check. Resume remains enabled for a durable paused session after reopening the app.

Upload status focuses on the currently transmitting recording (newest pending when idle), with durable part acknowledgements divided by that same recording's encoded bytes. Preparation and server commit are distinct phases. If a new upload starts before its manifest appears in a service snapshot, show an indeterminate transfer phase without inventing a per-file percentage. Separate settings for automatic upload and transcription must remain intact. A disabled automatic upload still shows locally saved audio and the manual Send action in More. Quarantined recordings show a visible recovery warning. Never use the global backlog to calculate the current upload progress. Transcription remains an optional small line and does not consume the screen's main progress bar.

Regression: idle UI does not render archive lists or whole-catalog bars; paused UI shows enabled Resume; identical snapshots repair stale enabled state; emulator Start → Pause → Resume restores capture; unit tests check active-file selection, partial durable bytes, unknown file size, committed files, server commit and historical isolation.
