package com.hans.android.voicebutton;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class PlayerSettings {
    static final String MODE_INSTANT = "instant";
    static final String MODE_STUDIO = "studio";
    static final float HARD_MIN_SPEED = 0.25f;
    static final float HARD_MAX_SPEED = 8.0f;

    float speed = 1.0f;
    float speedMin = 0.25f;
    float speedMax = 8.0f;
    float speedStep = 0.01f;
    float skipBack = 10.0f;
    float skipForward = 10.0f;
    int volume = 100;
    boolean muted;
    boolean loop;
    boolean autoplay;
    int sleepMinutes;
    String speedMode = MODE_STUDIO;
    List<Float> presets = new ArrayList<>(Arrays.asList(.5f,.75f,1f,1.25f,1.5f,1.75f,2f,2.5f,3f,4f,6f,8f));

    private final SharedPreferences preferences;

    PlayerSettings(Context context) {
        preferences = context.getSharedPreferences("voicebutton_player", Context.MODE_PRIVATE);
        load();
    }

    void load() {
        speedMin = clamp(preferences.getFloat("speed_min", .25f), HARD_MIN_SPEED, HARD_MAX_SPEED);
        speedMax = clamp(preferences.getFloat("speed_max", 8f), speedMin, HARD_MAX_SPEED);
        speedStep = clamp(preferences.getFloat("speed_step", .01f), .01f, 1f);
        speed = normalize(preferences.getFloat("speed", 1f));
        skipBack = clamp(preferences.getFloat("skip_back", 10f), .1f, 3600f);
        skipForward = clamp(preferences.getFloat("skip_forward", 10f), .1f, 3600f);
        volume = Math.max(0, Math.min(100, preferences.getInt("volume", 100)));
        muted = preferences.getBoolean("muted", false);
        loop = preferences.getBoolean("loop", false);
        autoplay = preferences.getBoolean("autoplay", false);
        sleepMinutes = Math.max(0, Math.min(1440, preferences.getInt("sleep_minutes", 0)));
        speedMode = preferences.getString("speed_mode", MODE_STUDIO);
        if (!MODE_STUDIO.equals(speedMode) && !MODE_INSTANT.equals(speedMode)) speedMode = MODE_STUDIO;
        presets = parsePresets(preferences.getString("presets", "0.5,0.75,1,1.25,1.5,1.75,2,2.5,3,4,6,8"));
    }

    void save() {
        preferences.edit()
                .putFloat("speed", speed).putFloat("speed_min", speedMin)
                .putFloat("speed_max", speedMax).putFloat("speed_step", speedStep)
                .putFloat("skip_back", skipBack).putFloat("skip_forward", skipForward)
                .putInt("volume", volume).putBoolean("muted", muted)
                .putBoolean("loop", loop).putBoolean("autoplay", autoplay)
                .putInt("sleep_minutes", sleepMinutes).putString("speed_mode", speedMode)
                .putString("presets", presetsText()).apply();
    }

    float normalize(float value) {
        float bounded = clamp(value, speedMin, speedMax);
        float normalized = Math.round(bounded / speedStep) * speedStep;
        return clamp(normalized, speedMin, speedMax);
    }

    float adjust(int direction) {
        speed = normalize(speed + direction * speedStep);
        save();
        return speed;
    }

    String presetsText() {
        StringBuilder out = new StringBuilder();
        for (Float value : presets) {
            if (out.length() > 0) out.append(',');
            out.append(String.format(Locale.US, "%g", value));
        }
        return out.toString();
    }

    void setPresets(String text) {
        presets = parsePresets(text);
        save();
    }

    private List<Float> parsePresets(String text) {
        List<Float> result = new ArrayList<>();
        for (String raw : String.valueOf(text).split("[,\\s]+")) {
            if (raw.isEmpty()) continue;
            try {
                float value = normalize(Float.parseFloat(raw));
                if (!result.contains(value)) result.add(value);
            } catch (NumberFormatException ignored) {}
        }
        if (result.isEmpty()) result.add(1f);
        result.sort(Float::compare);
        return result;
    }

    static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
