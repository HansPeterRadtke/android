package com.hans.android.network.reliable;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.regex.Pattern;

/**
 * Revocable per-phone access key, configured by the owner rather than embedded
 * in the distributed APK. Kept in app-private non-backed-up preferences.
 * No diagnostic function should ever include the token itself.
 */
public final class MobileAudioCredential {
    private static final String PREFS = "voicebutton_recording_access";
    private static final String KEY = "token";
    private static final String STUDIO_KEY = "studio_token";
    private static final Pattern TOKEN_FORMAT =
            Pattern.compile("[A-Za-z0-9_-]{40,128}");

    private MobileAudioCredential() {}

    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty() && !TOKEN_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Enter the complete recording-server access token (40–128 characters)");
        }
        return value;
    }

    public static String read(Context context) {
        String candidate = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "");
        try { return normalize(candidate); }
        catch (IllegalArgumentException invalid) { return ""; }
    }

    public static boolean save(Context context, String raw) {
        String normalized = normalize(raw);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.edit().putString(KEY, normalized).commit();
    }

    public static boolean isConfigured(Context context) {
        return !read(context).isEmpty();
    }

    public static String readStudioToken(Context context) {
        String candidate = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(STUDIO_KEY, "");
        try { return normalize(candidate); }
        catch (IllegalArgumentException invalid) { return ""; }
    }

    public static boolean saveStudioToken(Context context, String raw) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(STUDIO_KEY, normalize(raw)).commit();
    }

    public static boolean studioConfigured(Context context) {
        return !readStudioToken(context).isEmpty();
    }
}
