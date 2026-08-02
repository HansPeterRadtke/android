package com.hans.android.voicebutton;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class PlayerStateStore {
    static final class Saved {
        final PlayerSource original;
        final PlayerSource active;
        final List<PlayerSource> queue;
        final int queueIndex;
        final long logicalPositionMs;
        final boolean resumeOnOpen;
        final boolean studioActive;
        final float studioSpeed;

        Saved(PlayerSource original, PlayerSource active,
              List<PlayerSource> queue, int queueIndex,
              long logicalPositionMs, boolean resumeOnOpen,
              boolean studioActive, float studioSpeed) {
            this.original = original;
            this.active = active;
            this.queue = queue;
            this.queueIndex = queueIndex;
            this.logicalPositionMs = Math.max(0L, logicalPositionMs);
            this.resumeOnOpen = resumeOnOpen;
            this.studioActive = studioActive;
            this.studioSpeed = Math.max(.01f, studioSpeed);
        }
    }

    private final SharedPreferences preferences;

    PlayerStateStore(Context context) {
        preferences = context.getSharedPreferences(
                "voicebutton_player_checkpoint", Context.MODE_PRIVATE);
    }

    Saved load() {
        try {
            JSONObject root = new JSONObject(preferences.getString("state", "{}"));
            PlayerSource original = PlayerSource.fromJson(root.optJSONObject("original"));
            PlayerSource active = PlayerSource.fromJson(root.optJSONObject("active"));
            JSONArray array = root.optJSONArray("queue");
            List<PlayerSource> queue = new ArrayList<>();
            if (array != null) for (int i = 0; i < array.length(); i++) {
                PlayerSource source = PlayerSource.fromJson(array.optJSONObject(i));
                if (source != null) queue.add(source);
            }
            return new Saved(original, active, queue,
                    root.optInt("queue_index", -1),
                    root.optLong("logical_position_ms", 0L),
                    root.optBoolean("resume_on_open", false),
                    root.optBoolean("studio_active", false),
                    (float)root.optDouble("studio_speed", 1.0));
        } catch (Exception ignored) {
            return new Saved(null, null, new ArrayList<>(),
                    -1, 0L, false, false, 1f);
        }
    }

    void save(PlayerSource original, PlayerSource active,
              List<PlayerSource> queue, int queueIndex,
              long logicalPositionMs, boolean resumeOnOpen,
              boolean studioActive, float studioSpeed,
              boolean durable) {
        try {
            JSONObject root = new JSONObject();
            if (original != null) root.put("original", original.toJson());
            if (active != null) root.put("active", active.toJson());
            JSONArray array = new JSONArray();
            if (queue != null) for (PlayerSource source : queue) {
                if (source != null) array.put(source.toJson());
            }
            root.put("queue", array);
            root.put("queue_index", queueIndex);
            root.put("logical_position_ms", Math.max(0L, logicalPositionMs));
            root.put("resume_on_open", resumeOnOpen);
            root.put("studio_active", studioActive);
            root.put("studio_speed", studioSpeed);
            root.put("saved_at_ms", System.currentTimeMillis());
            SharedPreferences.Editor editor = preferences.edit()
                    .putString("state", root.toString());
            if (durable) editor.commit(); else editor.apply();
        } catch (Exception ignored) {}
    }

    void clear() { preferences.edit().remove("state").apply(); }
}
