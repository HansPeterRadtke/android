package com.hans.android.voicebutton;

final class PlayerControlState {
    final boolean playEnabled;
    final boolean seekEnabled;
    final boolean skipEnabled;
    final boolean speedEnabled;
    final String playLabel;
    final String status;

    private PlayerControlState(boolean playEnabled, boolean seekEnabled,
                               boolean skipEnabled, boolean speedEnabled,
                               String playLabel, String status) {
        this.playEnabled = playEnabled;
        this.seekEnabled = seekEnabled;
        this.skipEnabled = skipEnabled;
        this.speedEnabled = speedEnabled;
        this.playLabel = playLabel;
        this.status = status;
    }

    static PlayerControlState from(PlayerPlaybackService.Snapshot value) {
        return from(value == null ? "idle" : value.state,
                value == null ? "" : value.error,
                value != null && value.playing,
                value != null && value.seekable,
                value != null && value.activeSource != null,
                value != null && value.studioActive);
    }

    static PlayerControlState from(String rawState, String error,
                                   boolean playing, boolean seekable,
                                   boolean hasSource, boolean studioActive) {
        if (!hasSource) {
            return new PlayerControlState(false, false, false, false,
                    "Play", "Choose audio from Library");
        }
        String state = rawState == null ? "idle" : rawState.toLowerCase();
        String exactError = error == null ? "" : error;
        if (!exactError.isEmpty() || "error".equals(state)) {
            return new PlayerControlState(false, false, false, false,
                    "Unavailable", exactError.isEmpty()
                    ? "Playback failed" : exactError);
        }
        if ("opening".equals(state) || "queued".equals(state)
                || "restoring".equals(state) || "idle".equals(state)) {
            return new PlayerControlState(false, false, false, false,
                    "Loading…", "Loading playback engine…");
        }
        if ("pausing".equals(state)) {
            return new PlayerControlState(false, false, false, false,
                    "Pausing…", "Pausing playback…");
        }
        if (state.startsWith("buffering") || "starting playback".equals(state)) {
            return new PlayerControlState(false, false, false, false,
                    "Starting…", state.startsWith("buffering")
                    ? "Buffering audio…" : "Starting playback…");
        }
        boolean ready = "ready".equals(state) || "media-ready".equals(state)
                || "paused".equals(state) || "playing".equals(state)
                || "stopped".equals(state) || "ended".equals(state);
        return new PlayerControlState(ready, ready && seekable,
                ready, ready && !studioActive,
                playing ? "Pause" : "Play",
                playing ? "Playing" : ready ? "Ready" : rawState);
    }
}
