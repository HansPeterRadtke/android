package com.hans.android.network.reliable;

import android.content.Context;
import android.content.SharedPreferences;

import com.hans.android.audio.reliable.ReliableSessionManifest;

/**
 * Shared app/worker/uploader upload permission: an explicit app exit is
 * persistent until the user explicitly resumes activity or backup.
 * Never remove an unuploaded recording just because uploads are paused.
 */
public final class RecordingUploadPolicy {
    public static final String PREFS = "voicebutton_behavior";
    public static final String USER_EXIT_PAUSE = "background_paused_by_exit";
    private static final String AUTO_UPLOAD = "automatic_upload";
    private static final String MANUAL_BEFORE = "manual_upload_before_ms";

    private RecordingUploadPolicy() {}

    public static boolean permitted(boolean configuredAutomatic,
                                    boolean pausedByExit, long manualBeforeMs) {
        return !pausedByExit && (configuredAutomatic || manualBeforeMs > 0);
    }

    public static boolean eligible(boolean configuredAutomatic,
                                   boolean pausedByExit, long manualBeforeMs,
                                   long sessionCreatedAtMs) {
        return !pausedByExit && (configuredAutomatic
                || (manualBeforeMs > 0 && sessionCreatedAtMs <= manualBeforeMs));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
    }

    public static boolean requestedAutomatic(Context context) {
        return prefs(context).getBoolean(AUTO_UPLOAD, true);
    }

    public static boolean pausedByExit(Context context) {
        return prefs(context).getBoolean(USER_EXIT_PAUSE, false);
    }

    public static boolean automaticEnabled(Context context) {
        return requestedAutomatic(context) && !pausedByExit(context);
    }

    public static boolean uploadPermitted(Context context) {
        SharedPreferences p = prefs(context);
        return permitted(p.getBoolean(AUTO_UPLOAD, true),
                p.getBoolean(USER_EXIT_PAUSE, false),
                p.getLong(MANUAL_BEFORE, 0L));
    }

    public static boolean eligible(Context context, ReliableSessionManifest manifest) {
        if (manifest == null) return false;
        SharedPreferences p = prefs(context);
        return eligible(p.getBoolean(AUTO_UPLOAD, true),
                p.getBoolean(USER_EXIT_PAUSE, false),
                p.getLong(MANUAL_BEFORE, 0L), manifest.createdAt);
    }

    public static boolean pauseForExit(Context context) {
        // Commit synchronously: WorkManager and service may race after exit.
        return prefs(context).edit().putBoolean(USER_EXIT_PAUSE, true)
                .putLong(MANUAL_BEFORE, 0L).commit();
    }

    public static boolean resumeForUserAction(Context context) {
        return prefs(context).edit().putBoolean(USER_EXIT_PAUSE, false).commit();
    }

    public static boolean requestManualUpload(Context context) {
        return prefs(context).edit().putBoolean(USER_EXIT_PAUSE, false)
                .putLong(MANUAL_BEFORE, System.currentTimeMillis()).commit();
    }
}
