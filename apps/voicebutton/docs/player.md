# Voice Button player and library

Version 0.16 replaces the old MP3-only platform player with LibVLC 3.7.0. LibVLC streams the selected URI and provides broad codec and container support, pitch-preserving instant speed, seeking, volume, media-session controls and local document playback. The application does not claim that every malformed or proprietary file can be decoded; every regular file selected through Android's Storage Access Framework is offered to LibVLC and decode failure is reported without changing the source.

Back, Home, skip, Play/Pause and Stop remain reachable outside the scrolling details region. The full title, state, waveform, seek position, Position/Total/Remaining clock, queue controls and settings scroll when the viewport or font size requires it. See GUI_CONTRACT.md for the current screen contract.

Speed parameters persist across launches. The configurable range is bounded by the Thor contract at 0.25x to 8.00x. Speed step is configurable from 0.01 to 1.00. Backward and forward skip values are independently configurable from 0.1 to 3600 seconds. Presets, volume, mute, loop, autoplay and sleep timer are configurable.

Instant mode uses LibVLC audio time stretching for immediate pitch-preserving playback. Studio mode first keeps instant playback available, then resumably uploads the source to the restricted Thor mobile player API. Thor decodes to 24-bit PCM and renders exact tempo with Rubber Band R3 fine mode. The rendered WAV is cached and replaces instant playback at the same logical position. The user can export either the original source or the studio WAV.

Thor also renders a format-independent decoded waveform. Tapping the waveform seeks by logical source time. The Memory dialog reports process PSS, Java heap, native heap, system memory, selected source size, disk-backed studio cache and waveform bitmap allocation. Audio is streamed from storage; the complete source is not loaded into RAM.

The library has separate App recordings and Phone files modes. App recordings are organized by app folder. App folders and recording names can be renamed, and paused or finished recording directories can be moved atomically between app folders. Metadata changes remain in the durable uploader ledger until Jetson confirms the folder name, recording title and session location. Phone files use Android's Storage Access Framework, so the user chooses the visible provider roots. Regular files can be opened in LibVLC, renamed, moved by verified copy then source deletion, exported or deleted where the provider grants permission. Folders can be browsed and renamed.


## Version 0.18 threading contract

The activity thread never constructs LibVLC, creates Media objects, opens content descriptors, invokes playback, seeks, changes rate, queries native playback state, or releases the engine. Those operations are serialized on `voicebutton-libvlc`, an audio-priority HandlerThread. The UI polls volatile cached position and state twice per second. Native buffering notifications are rate-limited. `content://` sources use a retained ParcelFileDescriptor for the complete playback lifetime; app-private `file://` recordings use the direct filesystem path.

RecordingService `onCreate()` creates only channels, wake-lock objects, and an in-memory STARTING snapshot. Storage recovery, manifest parsing, conversion discovery, uploader creation, automatic resume, and every service command run on `voicebutton-service-command`. Status snapshots run on `voicebutton-status`. Recursive local-byte scans are cached for thirty seconds. Diagnostics initialization and writes are asynchronous. Debug builds enable StrictMode logging and a one-and-a-half-second main-loop watchdog.


## Version 0.22 task-scoped background playback

Playback is owned by a foreground media-playback service only after a file is loaded or a saved checkpoint is restored. Leaving the player for Library, pressing Home, or switching applications unbinds only the activity view; playback, MediaSession controls, queue state, and five-second atomic checkpoints continue. Swiping the Voice Button task away saves a final checkpoint, stops playback, removes the player notification, and stops the service. The recording service separately pauses any active recording, stops synchronization and conversion workers, and removes its notification. Reopening Player restores the original or studio source, queue, logical timestamp, speed, and prior playing state.

## Version 0.94 behavior

Starting capture pauses the playback engine before microphone activation. All playback entry points reject playback while capture remains active. A paused Studio handoff preserves the logical position and paused intent; it never briefly plays to obtain a seek. Source changes cancel obsolete rendering and waveform requests and invalidate late callbacks.

The playback service owns the sleep deadline using monotonic elapsed time and a saved boot identity. Activity recreation and navigation do not extend it. A subsequent explicit Play starts a new configured interval.

Automatic waveforms can be disabled in Automation and privacy, including service restoration. Generate waveform now remains available in the Player menu. Studio cache writes obey the configured byte budget and disk reserve. Clearing cache refuses while a render is running and preserves the active playback file. Responses and decoded waveform dimensions have explicit bounds.

Studio build credentials come from VOICEBUTTON_THOR_TOKEN or the private JSON file selected by VOICEBUTTON_PRIVATE_BUILD_CONFIG (default /data/var/build-config/voicebutton-studio.json, key thor_player_token). The private file is outside Git and mode 0600. Missing credentials disable authenticated Studio access; local playback remains available. The resulting client APK necessarily contains its configured credential. Removing the tracked literal does not erase historical Git revisions or rotate installed clients.
