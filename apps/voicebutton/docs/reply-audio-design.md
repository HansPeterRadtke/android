# Recording playback design

Playback is isolated from microphone capture and transfer. The archive shows a lazy list of all recording metadata but creates only one MediaPlayer for the selected item. The selected inspector provides Play or Pause, a full-width seek control, elapsed time, total duration, local size, durable-segment count, microphone label, and server state.

The current recording appears immediately. While its microphone is active, its player is disabled with a visible explanation because phone playback could be recorded back into the microphone. The inspector provides a direct Pause remedy. Pausing flushes and closes the current MP3 segment, assembles a playable snapshot, and then enables playback without finishing the recording.

Selecting another item stops the old player and points the single inspector at the new local MP3. Starting or resuming microphone capture stops playback of the current recording. Playback errors do not delete or rewrite files.
