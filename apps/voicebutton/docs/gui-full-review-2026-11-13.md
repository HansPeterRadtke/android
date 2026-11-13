# Voice Button cross-screen GUI and interaction revision — 13 November 2026

## Scope and source

This is a revision of the production Java/Android-Views client on top of the verified 0.97 recording/upload/transcription build. It complements the recording-derived Infra GUI rules, Android platform guide, action acknowledgement contract, native layout tests, and the live screen's full GUI_CONTRACT.md. The goal is to improve the *entire* recording-to-folder-to-playback workflow without silently redesigning audio durability or Jetson's reliable transport.

The user-facing screen review uses the following question contracts.

**Recorder (action/monitoring).** Immediately answer: is capture ready/active/paused, is audio saved, what is the next action, and what is the status of *this recording's* transfer and transcription? Secondary: choose folder/microphone, open recordings, send/transcribe existing files, change automation, diagnose. Primary action is bottom-fixed; Finished is separated. Known percentages always have real progress bars and explicit scope. Keep all old audio and server state unchanged.

**Library (exploration/management).** Immediately answer: which source and folder am I browsing, what recordings/files are available, can a selected item play, and how can I manage its folder/file? Secondary: create/rename/move folders, select phone document tree, view file metadata. Root selection and loaded-state transitions must not be overwritten by older asynchronous queries. Rows must have *discoverable* actions in addition to long-press. Disabled and error states must have reasons and remedies. File/folder changes are asynchronous and visible.

**Player (action/monitoring).** Immediately answer: what exact file is selected, is the engine playing/paused/failed, what are position/total/remaining time, what can I play/seek/skip, and how can I return to the Library? Secondary: player settings, speed and Studio controls, file management and diagnostics. Play/Stop/Skip/Seek must not appear executable when no source has been loaded. Source-sensitive file operations must preserve the exact selected source even across asynchronous file-picker returns.

**Menus (action/selection).** Put frequent navigation on-screen, avoid duplicate commands and expose context-sensitive management. Group rare diagnostics behind secondary controls. A long-running command is acknowledged immediately, with a distinct eventual success/failure. Dangerous delete/move actions need truthful confirmation of remote-durability and permission boundaries.

## Independently observed source defects in 0.97

- Recorder hid the Library behind a general More menu, which originally opened an empty player rather than the folder collection.
- Library switched to Phone files without always invalidating an in-flight App recordings request. A stale result could replace the new mode. The background worker also wrote UI navigation state off the main thread.
- Library row management existed only on long-press; row actions were not discoverable. Compact-height mode hid status/error text entirely.
- Library offered delete before checking whether the recording had a server-committed copy; an eventual store rejection did not explain the constraint at the menu/confirmation stage.
- The player exposed Play, Skip, Seek and Stop on the initial no-source view, with false optimistic playback feedback possible.
- Player folder-move labels used only folder basename, making identically named folders ambiguous. Player SAF move used whatever source happened to be active when the picker returned, instead of pinning the file selected when the action began.
- Player 'File details' presented internal recording folder IDs and raw URIs as the main summary.
- Existing async menu/file operations were often silent until a Toast; a Toast alone is not reliable progress or error retention.

## Rejection gates for this revision

Do not ship a main overview without direct entry to recordings, a player without a clear no-source state, library mode transitions that let old callbacks overwrite the current selection, ambiguous folder destinations, unsafe picker source substitution, missing error conditions, or unacknowledged multi-second actions. Test actual emulator user flows, policy/unit regressions, different orientation/font cases and full build. Storage provider/OEM behavior still requires physical device coverage and must not be declared validated from the emulator.
