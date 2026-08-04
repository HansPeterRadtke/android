package com.hans.android.voicebutton;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

final class FastDocumentDirectory {
    static final class Entry {
        final Uri uri;
        final String name;
        final String mimeType;
        final long bytes;
        final boolean directory;

        Entry(Uri uri, String name, String mimeType, long bytes,
              boolean directory) {
            this.uri = uri;
            this.name = name == null || name.isEmpty() ? "Unnamed" : name;
            this.mimeType = mimeType == null ? "" : mimeType;
            this.bytes = Math.max(0L, bytes);
            this.directory = directory;
        }
    }

    private FastDocumentDirectory() {}

    static List<Entry> list(Context context, Uri directoryUri) throws Exception {
        try {
            return queryOnce(context, directoryUri);
        } catch (Exception unsupported) {
            return fallback(context, directoryUri);
        }
    }

    private static List<Entry> queryOnce(Context context, Uri directoryUri)
            throws Exception {
        String documentId;
        try { documentId = DocumentsContract.getDocumentId(directoryUri); }
        catch (IllegalArgumentException failure) {
            documentId = DocumentsContract.getTreeDocumentId(directoryUri);
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                directoryUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                children, projection, null, null, null)) {
            if (cursor == null) throw new java.io.IOException(
                    "The document provider returned no folder cursor");
            int idColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String childId = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                long bytes = sizeColumn >= 0 && !cursor.isNull(sizeColumn)
                        ? cursor.getLong(sizeColumn) : 0L;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(
                        directoryUri, childId);
                result.add(new Entry(uri, name, mime, bytes,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)));
            }
        }
        return result;
    }

    private static List<Entry> fallback(Context context, Uri directoryUri)
            throws Exception {
        DocumentFile directory = DocumentFile.fromSingleUri(context, directoryUri);
        if (directory == null || !directory.isDirectory()) {
            directory = DocumentFile.fromTreeUri(context, directoryUri);
        }
        if (directory == null) throw new java.io.IOException(
                "The selected folder is unavailable");
        ArrayList<Entry> result = new ArrayList<>();
        for (DocumentFile child : directory.listFiles()) {
            String mime = child.getType();
            boolean isDirectory = child.isDirectory();
            result.add(new Entry(child.getUri(), child.getName(), mime,
                    isDirectory ? 0L : child.length(), isDirectory));
        }
        return result;
    }
}
