# Voice Button GUI contract

This is the current authoritative screen contract. It consolidates earlier versioned proposals. The October nineteenth correction explicitly requires visible, accurate progress bars, rather than removing all progress indicators to avoid misleading animations. The recorder remains primary and detailed diagnostic queues stay under More. Essential text wraps and must not be clipped at large font sizes.

## Recording screen

The screen answers whether recording can start, whether it is recording or paused, how long the current recording is, whether captured audio is protected locally, which microphone is routed, and what action is available. Two prominently visible overall progress bars under the toolbar show verified server-backup bytes and verified transcription completion. Each has its own status/units. A currently transferring recording has a separate per-file bar, and a currently transcribing recording has its own frame-based bar when the server supplies its identity and progress. The corresponding detailed current-file bars may scroll with the recording information. Never hide the overall progress indicators merely because an older version displayed misleading progress. Do not show endless speculative preparing rows, pseudo-percentages or stale animations. A live recording is not a known final-size upload; percentage covers only locally encoded, closed MP3 chunks already in the durable ledger. Missing or stale status must not establish that a remote copy exists.

Start/Pause/Resume/Recover stays outside the scrolling information area. Finish stays in the separated upper toolbar and requires consequence confirmation. The overall progress pair is fixed just under the toolbar in portrait. In short, wide landscape windows it uses the right column to remain visible without making the microphone and setup column scroll. Current-file progress remains in the scrollable information area. The normal phone overview must fit without scrolling after padding, duplicate labels and empty placeholders are removed. Landscape uses two columns for recording state/setup and transfers. Scrolling remains only for genuinely constrained accessibility windows or extensive content, while toolbar and primary controls remain reachable. Branding may hide when constrained. Idle hides duration, local protection and live microphone details. Recording shows those details.

Optional detailed operation views may reuse progress rows; the recording overview reserves space for the two useful overall progress bars and their labels, not empty queue rows. Do not reserve an empty 108 dp block for an idle queue. A shortened filename must expose its complete identity and status by tap and accessibility. Overall and per-file percentages are distinct. Unknown denominators use indeterminate progress. Offline and failed states identify what remains local and the remedy: check Internet, then More → Retry synchronization / Send pending recordings. Quarantined audio prevents backup-complete claims and identifies recovery as the next step.

More contains player/library, retry, support, diagnostics, automation and privacy. Automatic upload and transcription have separate persisted controls. Manual Send pending recordings and Transcribe uploaded recordings remain available. Foreground and scheduled workers honor the upload control and persistent explicit Exit suspension. Work already submitted to the server is not recalled.

## Player and library

Player shows file identity, playing/paused/stopped state, position, total and remaining time, speed, seek, Play/Pause, Stop, skips, Library and More. Toolbar and transport stay reachable. Stop shares the transport row; Library and More share a navigation row. Landscape separates audio position and waveform from queue, mode and navigation controls into two columns. An absent waveform and inactive Studio progress consume no height. Normal phone layouts fit without scrolling; long content remains accessible. Long titles, failure explanations and labels wrap. Action and seek targets are at least 48 dp high. More holds settings, file operations, memory/cache, manual waveform generation and engine details.

An existing file never bypasses capture exclusion. Starting capture pauses the playback engine before opening the microphone. Studio handoff preserves logical position and requested playing/paused state; native paused seek priming stays muted. Source/speed changes invalidate late results and cancel network requests. The playback service owns a monotonic sleep deadline, which survives activity recreation and prevents resumed autoplay after expiry.

Library keeps source, location and items readable at large font sizes. Up and mode controls are at least 48 dp; rows reuse views. Complete identity is available through details. Active capture produces an explicit playback-disabled reason. Destructive actions require consequence previews. Copy-and-delete moves verify source and destination content before source deletion, including providers with unknown lengths.

## Lifecycle and performance

Home permits background recording while switching apps. Back and the visible Exit button shut down background uploads and safely pause active capture, with confirmation for an open recording. Swiping the Android task away is also a safe close operation. Exit suspends background jobs persistently until a later explicit user action. Reopening restores the checkpoint and its prior intent subject to capture exclusion and sleep expiry. Force-stop, shutdown and hardware failure remain external boundaries.

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
| Overall upload | Durable remote bytes plus bounded partial acknowledgements / known local encoded bytes | Phone recording set, including live closed chunks; whole percent | Verified ledger; unknown future bytes never guessed, commit separate | Judge backup progress |
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
