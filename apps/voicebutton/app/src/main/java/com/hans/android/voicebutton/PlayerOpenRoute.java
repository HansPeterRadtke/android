package com.hans.android.voicebutton;

final class PlayerOpenRoute {
    static final String CONTENT_FILE_DESCRIPTOR = "content-file-descriptor";
    static final String FILE_PATH = "file-path";
    static final String GENERIC_URI = "generic-uri";

    private PlayerOpenRoute() {}

    static String forScheme(String scheme) {
        if (scheme == null) return GENERIC_URI;
        if ("content".equalsIgnoreCase(scheme)) return CONTENT_FILE_DESCRIPTOR;
        if ("file".equalsIgnoreCase(scheme)) return FILE_PATH;
        return GENERIC_URI;
    }
}
