package com.hans.android.voicebutton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PlayerCheckpoint {
    static final int SCHEMA_VERSION = 1;

    final PlayerSource original;
    final PlayerSource active;
    final List<PlayerSource> queue;
    final int queueIndex;
    final long logicalPositionMs;
    final boolean resumeOnOpen;
    final boolean studioActive;
    final float studioSpeed;
    final float instantSpeed;
    final long savedAtMs;

    PlayerCheckpoint(PlayerSource original, PlayerSource active,
                     List<PlayerSource> queue, int queueIndex,
                     long logicalPositionMs, boolean resumeOnOpen,
                     boolean studioActive, float studioSpeed,
                     float instantSpeed, long savedAtMs) {
        this.original = original;
        this.active = active;
        this.queue = queue == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(queue));
        this.queueIndex = queueIndex;
        this.logicalPositionMs = Math.max(0L, logicalPositionMs);
        this.resumeOnOpen = resumeOnOpen;
        this.studioActive = studioActive;
        this.studioSpeed = Math.max(.01f, studioSpeed);
        this.instantSpeed = Math.max(.01f, instantSpeed);
        this.savedAtMs = savedAtMs;
    }

    boolean hasSource() { return original != null && active != null; }

    JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("schema", SCHEMA_VERSION);
        if (original != null) value.put("original", original.toJson());
        if (active != null) value.put("active", active.toJson());
        JSONArray items = new JSONArray();
        for (PlayerSource source : queue) if (source != null) items.put(source.toJson());
        value.put("queue", items);
        value.put("queue_index", queueIndex);
        value.put("logical_position_ms", logicalPositionMs);
        value.put("resume_on_open", resumeOnOpen);
        value.put("studio_active", studioActive);
        value.put("studio_speed", studioSpeed);
        value.put("instant_speed", instantSpeed);
        value.put("saved_at_ms", savedAtMs);
        return value;
    }

    static PlayerCheckpoint fromJson(JSONObject value) {
        if (value == null || value.optInt("schema", 0) != SCHEMA_VERSION) return empty();
        PlayerSource original = PlayerSource.fromJson(value.optJSONObject("original"));
        PlayerSource active = PlayerSource.fromJson(value.optJSONObject("active"));
        ArrayList<PlayerSource> queue = new ArrayList<>();
        JSONArray items = value.optJSONArray("queue");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            PlayerSource source = PlayerSource.fromJson(items.optJSONObject(i));
            if (source != null) queue.add(source);
        }
        return new PlayerCheckpoint(original, active, queue,
                value.optInt("queue_index", -1),
                value.optLong("logical_position_ms", 0L),
                value.optBoolean("resume_on_open", false),
                value.optBoolean("studio_active", false),
                (float)value.optDouble("studio_speed", 1.0),
                (float)value.optDouble("instant_speed", 1.0),
                value.optLong("saved_at_ms", 0L));
    }

    static PlayerCheckpoint empty() {
        return new PlayerCheckpoint(null, null, Collections.emptyList(),
                -1, 0L, false, false, 1f, 1f, 0L);
    }
}
