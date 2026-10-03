# Screen question contracts

The current recording, player, library, lifecycle, progress, freshness and interaction requirements are consolidated in [GUI_CONTRACT.md](../GUI_CONTRACT.md). Its metric dictionary and acceptance matrix are authoritative. Earlier versioned proposals for hiding transcription, ellipsizing essential text, unbounded diagnostic retention, or continuing capture after task removal are superseded.

Recording asks whether capture is active, whether audio is locally protected, which microphone is routed, which backup/transcription work remains, and what safe action is available. Player asks what is loaded, playback state, position/total/remaining time, current speed, and which transport action is available. Library asks which location is being browsed, which items exist, and whether the selected recording can safely be played or managed.

Diagnostics remain secondary. Every unavailable primary action has a visible reason and a reachable remedy. Unknown or stale remote evidence is labeled explicitly.
