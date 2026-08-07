package com.hans.android.voicebutton;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class VoiceButtonLocalTrace {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 768L * 1024L;
    private static final long TRIM_BYTES = 384L * 1024L;

    private VoiceButtonLocalTrace() {}

    static void log(Context context, String event, Object... keyValues) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                File file = traceFile(context);
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                trimIfNeeded(file);
                StringBuilder line = new StringBuilder(256);
                long wall = System.currentTimeMillis();
                line.append(isoUtc(wall));
                line.append(" elapsed=").append(SystemClock.elapsedRealtime());
                line.append(" thread=").append(Thread.currentThread().getName());
                line.append(" event=").append(safe(event));
                if (keyValues != null) {
                    for (int i = 0; i + 1 < keyValues.length; i += 2) {
                        line.append(' ')
                                .append(safe(String.valueOf(keyValues[i])))
                                .append('=')
                                .append(safe(String.valueOf(keyValues[i + 1])));
                    }
                }
                line.append('\n');
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(line.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (Exception ignored) {}
        }
    }

    static String tail(Context context, int maxBytes) {
        if (context == null) return "unavailable";
        synchronized (LOCK) {
            try {
                File file = traceFile(context);
                if (!file.isFile()) return "unavailable";
                long length = file.length();
                int size = (int)Math.max(0, Math.min(Math.max(1, maxBytes), length));
                byte[] buffer = new byte[size];
                try (FileInputStream in = new FileInputStream(file)) {
                    long skip = Math.max(0L, length - size);
                    while (skip > 0L) {
                        long skipped = in.skip(skip);
                        if (skipped <= 0L) break;
                        skip -= skipped;
                    }
                    int offset = 0;
                    while (offset < size) {
                        int read = in.read(buffer, offset, size - offset);
                        if (read < 0) break;
                        offset += read;
                    }
                    return new String(buffer, 0, offset, StandardCharsets.UTF_8);
                }
            } catch (Exception failure) {
                return "unreadable: " + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage();
            }
        }
    }

    private static File traceFile(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), "local_trace"),
                "voicebutton-trace.log");
    }

    private static void trimIfNeeded(File file) throws Exception {
        if (!file.isFile() || file.length() <= MAX_BYTES) return;
        byte[] tail;
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int)TRIM_BYTES)) {
            long skip = Math.max(0L, file.length() - TRIM_BYTES);
            while (skip > 0L) {
                long skipped = in.skip(skip);
                if (skipped <= 0L) break;
                skip -= skipped;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            tail = out.toByteArray();
        }
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(tail);
            out.flush();
        }
    }

    private static String isoUtc(long wall) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(wall));
    }

    private static String safe(String value) {
        if (value == null) return "";
        String text = value.replace('\n', '_').replace('\r', '_').replace('\t', '_');
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
