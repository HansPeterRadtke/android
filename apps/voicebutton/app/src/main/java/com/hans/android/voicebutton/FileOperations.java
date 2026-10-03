package com.hans.android.voicebutton;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

final class FileOperations {
    private FileOperations() {}


    static void persistPermissions(Context context, Uri uri, int returnedFlags) {
        if ((returnedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
        }
        if ((returnedFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) {}
        }
    }

    static DocumentFile document(Context context, Uri uri) throws Exception {
        DocumentFile file = DocumentFile.fromSingleUri(context, uri);
        if (file == null || !file.exists()) throw new FileNotFoundException("The selected file no longer exists");
        return file;
    }

    static Uri rename(Context context, Uri uri, String requestedName) throws Exception {
        DocumentFile file = document(context, uri);
        String name = cleanName(requestedName);
        if (!file.canWrite()) throw new java.io.IOException("This provider does not allow renaming");
        if (!file.renameTo(name)) throw new java.io.IOException("The file provider rejected the new name");
        return file.getUri();
    }

    static Uri move(Context context, Uri sourceUri, Uri destinationTree,
                    String requestedName) throws Exception {
        DocumentFile source = document(context, sourceUri);
        DocumentFile destination = DocumentFile.fromTreeUri(context, destinationTree);
        if (destination == null || !destination.isDirectory() || !destination.canWrite()) {
            throw new java.io.IOException("The destination folder is not writable");
        }
        String name = cleanName(requestedName == null || requestedName.isEmpty()
                ? source.getName() : requestedName);
        String type = source.getType() == null ? "application/octet-stream" : source.getType();
        DocumentFile target = destination.createFile(type, name);
        if (target == null) throw new java.io.IOException("Could not create the destination file");
        try {
            copy(context, sourceUri, target.getUri());
            // Provider length is optional and is never proof of a successful move.
            // Reopen both streams after closing the copy and verify every byte.
            verifyIdentical(context, sourceUri, target.getUri());
            if (!source.delete()) throw new java.io.IOException("The copy succeeded but the source could not be deleted");
            return target.getUri();
        } catch (Exception failure) {
            target.delete();
            throw failure;
        }
    }

    static void copy(Context context, Uri source, Uri destination) throws Exception {
        InputStream rawIn = context.getContentResolver().openInputStream(source);
        OutputStream rawOut = context.getContentResolver().openOutputStream(destination, "w");
        if (rawIn == null || rawOut == null) {
            if (rawIn != null) rawIn.close();
            if (rawOut != null) rawOut.close();
            throw new FileNotFoundException("Could not open the selected source or destination");
        }
        try (BufferedInputStream in = new BufferedInputStream(rawIn);
             BufferedOutputStream out = new BufferedOutputStream(rawOut)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.io.InterruptedIOException("File operation cancelled");
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    static void verifyIdentical(Context context, Uri source, Uri destination) throws Exception {
        byte[] expected = digest(context, source);
        byte[] actual = digest(context, destination);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new IOException("Destination content differs; the original file was retained");
        }
    }

    private static byte[] digest(Context context, Uri uri) throws Exception {
        java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
        InputStream raw = context.getContentResolver().openInputStream(uri);
        if (raw == null) throw new IOException("Cannot verify copied audio; original retained");
        try (InputStream in = new BufferedInputStream(raw)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Verification cancelled");
                hash.update(buffer, 0, n);
            }
        }
        return hash.digest();
    }

    static String cleanName(String value) throws Exception {
        String name = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 160) throw new java.io.IOException("Enter a filename of one to one hundred sixty characters");
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character < 32 || character == 127 || character == '/' || character == '\\') {
                throw new java.io.IOException("The filename contains a path separator or control character");
            }
        }
        return name;
    }

    static void delete(Context context, Uri source) throws IOException {
        DocumentFile file = DocumentFile.fromSingleUri(context, source);
        if (file == null || !file.exists()) {
            throw new IOException("The selected file is no longer available");
        }
        if (!file.delete()) {
            throw new IOException("The Android storage provider rejected deletion");
        }
    }
}
