# Voice Button GUI contract

This is the current authoritative screen contract. It consolidates earlier versioned proposals. User-facing upload and transcription progress remain on the recording screen; raw engine, protocol, session, retry and storage diagnostics belong in More. Essential text wraps and must not be clipped at large font sizes.

## Recording screen

The screen answers whether recording can start, whether it is recording or paused, how long the current recording is, whether captured audio is protected locally, which microphone is routed, and what action is available. Both upload and transcription retain overall progress and remaining file counts. Empty queues consume no separate list area; active queues use a measured row viewport containing file identity, state and progress. A fresh empty queue may show completion. Unknown or stale server state must identify uncertainty and the age of the last successful snapshot; it never establishes that the current queue is empty.

Start/Pause/Resume/Recover stays outside the scrolling information area. Finish stays in the separated upper toolbar and requires consequence confirmation. Information starts with the recording state, followed by microphone, upload, transcription and idle setup. The normal phone overview must fit without scrolling after padding, duplicate labels and empty placeholders are removed. Landscape uses two columns for recording state/setup and transfers. Scrolling remains only for genuinely constrained accessibility windows or extensive content, while toolbar and primary controls remain reachable. Branding may hide when constrained. Idle hides duration, local protection and live microphone details. Recording shows those details.

Progress rows reuse views and size the queue viewport to the actual first row. Do not reserve an empty 108 dp block for an idle queue. A shortened filename must expose its complete identity and status by tap and accessibility. Overall and per-file percentages are distinct. Unknown denominators use indeterminate progress. Offline and failed states identify what remains local and the remedy: check Internet, then More → Retry synchronization / Send pending recordings. Quarantined audio prevents backup-complete claims and identifies recovery as the next step.

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
| Overall upload | Durable remote bytes plus bounded partial acknowledgements / known local encoded bytes | Phone recording set; whole percent | Verified ledger; indeterminate while bytes are unmeasured | Judge backup progress |
| Per-file upload | Matching segment bytes plus partial durable offsets / that recording's encoded bytes | One named recording; whole percent | Commit is separate from byte completion | Identify current or stalled file |
| Upload remaining | Sessions still requiring transfer or verified commit | Local recording set; integer files | Local snapshot; quarantined files stay incomplete | Know whether backup is complete |
| Overall transcription | Server completion count / server committed recording count | Server recording set; whole percent | Last successful response; marked last-known when stale | Judge transcription backlog |
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
