# Voice Button GUI contract

This is the current authoritative screen contract. It consolidates earlier versioned proposals. The recording screen prioritizes recording state and controls. A current-recording transfer is visible when pending. Transcription progress also has its own horizontal bars: a per-file bar calculated from actual processed and total frames, and a separate, visually subordinate overall bar using the server's whole-backlog percentage. Raw engine, protocol, session, retry and storage diagnostics remain outside the main view. Essential text wraps and must not be clipped at large font sizes.

## Recording screen

The screen answers whether recording can start, whether it is recording or paused, how long the current recording is, whether captured audio is protected locally, which microphone is routed, and what action is available. Only the selected active (or newest pending) recording's transfer shows a progress bar: the known, server-acknowledged bytes for that one file. Other recordings are a waiting count, not a denominator. Transcription is a separate subordinate group with a labelled current-file progress bar whenever frame progress is measurable, and a labelled overall transcription progress bar whenever the server supplies a valid committed-recording denominator. Loading and waiting use an indeterminate bar; failure shows an explicit cause rather than fake progress. The overall bar never substitutes for the current-file bar. Empty queues and historical completions consume no main-screen space. Unknown or stale server state must identify uncertainty and the age of the last successful snapshot; it never establishes that the current queue is empty.

Start/Pause/Resume/Recover stays outside the scrolling information area. Finish stays in the separated upper toolbar and requires consequence confirmation. Information starts with the recording state, then the current-recording transfer, subordinate per-file and overall transcription progress bars when meaningful, and idle setup. Microphone route and level appear during capture. The normal phone overview must fit without scrolling after padding, duplicate labels and empty placeholders are removed. Landscape uses two columns for recording state/setup and transfers. Scrolling remains only for genuinely constrained accessibility windows or extensive content, while toolbar and primary controls remain reachable. Branding may hide when constrained. Idle hides duration, local protection and live microphone details. Recording shows those details.

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
| Overall transcription | Server-reported overall progress / committed recordings | Archive set; whole percent, clearly labelled and smaller than current-file progress | Last successful response; last-known on stale refresh; unknown if total absent | Assess remaining transcription workload without confusing current-file progress |
| Per-file transcription | Min(processed frames, expected frames) / expected frames on current identified recording | Named current recording; percent and bar | Indeterminate during load/start, no actual total, or waiting; stale labelled explicitly | Monitor the current transcription's actual progress |
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

Upload status focuses on the currently transmitting recording (newest pending when idle), with durable part acknowledgements divided by that same recording's encoded bytes. Preparation and server commit are distinct phases. If a new upload starts before its manifest appears in a service snapshot, show an indeterminate transfer phase without inventing a per-file percentage. Separate settings for automatic upload and transcription must remain intact. A disabled automatic upload still shows locally saved audio and the manual Send action in More. Quarantined recordings show a visible recovery warning. Never use the global backlog to calculate the current upload progress. Transcription has its own per-recording progress bar and separate subordinate overall bar, never a text-only percent. These are independent of the upload bar.

Regression: idle UI does not render archive lists or whole-catalog bars; paused UI shows enabled Resume; identical snapshots repair stale enabled state; emulator Start → Pause → Resume restores capture; unit tests check active-file selection, partial durable bytes, unknown file size, committed files, server commit and historical isolation.

## November 13 correction: transcription bars are mandatory when measurable

The owner's clarified rule is that every displayed calculable percentage must have a corresponding progress bar. Transcription is no exception. Show the identified current recording, actual processed/total frame progress, percentage and determinate bar whenever that denominator exists. Show a separate labelled overall transcription percentage and bar when Jetson reports a valid committed-file count, while keeping this archive-wide measure subordinate. When exact progress is unknown, use an indeterminate activity bar and accurate phase label; when a server snapshot is stale, label last-known age and keep values visibly historical. A failed operation must not animate as if working. A completed backlog can display a compact 100-percent overall bar. Keep uploading and transcription as separate workstreams and never replace either file-specific progress with global backlog percentages.
