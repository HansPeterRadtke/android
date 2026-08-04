package com.hans.android.audio.reliable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class RecordingFileNames {
    private RecordingFileNames() {}

    public static String defaultDisplayName(long createdAtMs) {
        return "Recording " + new SimpleDateFormat(
                "yyyy-MM-dd HH-mm-ss", Locale.US).format(new Date(createdAtMs));
    }

    public static String defaultMp3Name(long createdAtMs, String sessionId) {
        String id = sessionId == null ? "unknown" : sessionId.replaceAll("[^A-Za-z0-9]", "");
        if (id.length() > 8) id = id.substring(0, 8);
        if (id.isEmpty()) id = "unknown";
        return defaultDisplayName(createdAtMs) + " " + id + ".mp3";
    }

    public static boolean isLegacyGenericName(String value) {
        return value == null || value.isEmpty() || "recording.mp3".equalsIgnoreCase(value);
    }
    public static String mp3Name(long createdAtMs, String sessionId,
                                 String displayName) {
        String defaultDisplay = defaultDisplayName(createdAtMs);
        String clean = displayName == null ? "" : displayName.trim();
        if (clean.isEmpty() || clean.equals(defaultDisplay)) {
            return defaultMp3Name(createdAtMs, sessionId);
        }
        StringBuilder safe = new StringBuilder(clean.length() + 4);
        for (int i = 0; i < clean.length(); i++) {
            char character = clean.charAt(i);
            safe.append(character < 32 || character == 127
                    || character == '/' || character == '\\' ? '_' : character);
        }
        String value = safe.toString().trim();
        return value.toLowerCase(Locale.US).endsWith(".mp3")
                ? value : value + ".mp3";
    }

}
