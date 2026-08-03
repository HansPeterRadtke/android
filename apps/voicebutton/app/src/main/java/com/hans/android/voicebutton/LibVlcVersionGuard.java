package com.hans.android.voicebutton;

final class LibVlcVersionGuard {
    interface Reader { String read(); }

    private LibVlcVersionGuard() {}

    static String safeVersion(boolean nativeReady, Reader reader) {
        if (!nativeReady) return "loading";
        try {
            String value = reader == null ? "" : reader.read();
            return value == null || value.trim().isEmpty() ? "unknown" : value;
        } catch (Throwable failure) {
            return "unavailable";
        }
    }
}
