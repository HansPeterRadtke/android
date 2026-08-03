package com.hans.android.voicebutton;

import android.content.Intent;
import android.net.Uri;

import java.io.File;

final class PlayerSource {
    static final String EXTRA_URI = "player_uri";
    static final String EXTRA_TITLE = "player_title";
    static final String EXTRA_KIND = "player_kind";
    static final String EXTRA_BYTES = "player_bytes";
    static final String EXTRA_SESSION = "player_session";
    static final String EXTRA_FOLDER = "player_folder";
    static final String EXTRA_PARENT_URI = "player_parent_uri";
    static final String KIND_RECORDING = "recording";
    static final String KIND_DOCUMENT = "document";
    static final String KIND_STUDIO = "studio";

    final Uri uri;
    final String title;
    final String kind;
    final long bytes;
    final String sessionId;
    final String folderId;
    final Uri parentUri;

    PlayerSource(Uri uri, String title, String kind, long bytes,
                 String sessionId, String folderId, Uri parentUri) {
        this.uri = uri; this.title = title == null || title.isEmpty() ? "Audio" : title;
        this.kind = kind == null ? KIND_DOCUMENT : kind; this.bytes = Math.max(0L, bytes);
        this.sessionId = sessionId == null ? "" : sessionId;
        this.folderId = folderId == null ? "" : folderId;
        this.parentUri = parentUri;
    }

    static PlayerSource fromIntent(Intent intent) {
        String raw = intent == null ? "" : intent.getStringExtra(EXTRA_URI);
        if (raw == null || raw.isEmpty()) return null;
        String parent = intent.getStringExtra(EXTRA_PARENT_URI);
        return new PlayerSource(Uri.parse(raw), intent.getStringExtra(EXTRA_TITLE),
                intent.getStringExtra(EXTRA_KIND), intent.getLongExtra(EXTRA_BYTES, 0L),
                intent.getStringExtra(EXTRA_SESSION), intent.getStringExtra(EXTRA_FOLDER),
                parent == null || parent.isEmpty() ? null : Uri.parse(parent));
    }

    void put(Intent intent) {
        intent.putExtra(EXTRA_URI, uri.toString()); intent.putExtra(EXTRA_TITLE, title);
        intent.putExtra(EXTRA_KIND, kind); intent.putExtra(EXTRA_BYTES, bytes);
        intent.putExtra(EXTRA_SESSION, sessionId); intent.putExtra(EXTRA_FOLDER, folderId);
        if (parentUri != null) intent.putExtra(EXTRA_PARENT_URI, parentUri.toString());
    }

    static PlayerSource recording(File file, String title, long bytes,
                                  String sessionId, String folderId) {
        return new PlayerSource(Uri.fromFile(file), title, KIND_RECORDING, bytes,
                sessionId, folderId, null);
    }
}
