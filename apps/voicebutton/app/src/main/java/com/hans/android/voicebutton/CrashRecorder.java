package com.hans.android.voicebutton;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

final class CrashRecorder {
    private static final String DIRECTORY = "uncaught_crashes";
    private static final String FILE = "pending-crash.txt";
    private static final int MAX_REPORT_CHARS = 128 * 1024;

    private CrashRecorder() {}

    static void install(Context context) {
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            writeCrash(app, thread, failure);
            if (previous != null) previous.uncaughtException(thread, failure);
            else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    static void reportPending(Context context, PhoneDiagnostics diagnostics) {
        if (diagnostics == null) return;
        File file = crashFile(context);
        if (!file.isFile() || file.length() <= 0L) return;
        String report = readLimited(file);
        diagnostics.log(PhoneDiagnostics.ERROR, "app.previous_uncaught_crash", null,
                "The previous Voice Button process ended because of an uncaught Java exception",
                PhoneDiagnostics.fields("crash_report", report,
                        "crash_file_bytes", file.length()));
        diagnostics.flushSoon();
        if (!file.delete()) file.renameTo(new File(file.getParentFile(),
                "reported-" + System.currentTimeMillis() + ".txt"));
    }

    private static void writeCrash(Context context, Thread thread, Throwable failure) {
        try {
            File file = crashFile(context);
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) return;
            StringWriter buffer = new StringWriter();
            PrintWriter out = new PrintWriter(buffer);
            out.println("Voice Button uncaught crash");
            out.println("time=" + new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
            out.println("app_version=" + BuildConfig.VERSION_NAME
                    + " code=" + BuildConfig.VERSION_CODE);
            out.println("device=" + Build.MANUFACTURER + " " + Build.MODEL
                    + " android=" + Build.VERSION.RELEASE
                    + " sdk=" + Build.VERSION.SDK_INT);
            out.println("thread=" + (thread == null ? "unknown" : thread.getName()));
            out.println();
            if (failure != null) failure.printStackTrace(out);
            out.println();
            out.println("All thread stacks:");
            for (Map.Entry<Thread, StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                out.println("--- " + entry.getKey().getName()
                        + " state=" + entry.getKey().getState());
                for (StackTraceElement element : entry.getValue()) {
                    out.println("    at " + element);
                }
            }
            out.flush();
            byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream stream = new FileOutputStream(file, false)) {
                stream.write(bytes, 0, Math.min(bytes.length, MAX_REPORT_CHARS));
                stream.flush();
                stream.getFD().sync();
            }
        } catch (Throwable ignored) {}
    }

    private static File crashFile(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), DIRECTORY), FILE);
    }

    private static String readLimited(File file) {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while (out.length() < MAX_REPORT_CHARS
                    && (read = reader.read(buffer)) != -1) {
                out.append(buffer, 0, Math.min(read,
                        MAX_REPORT_CHARS - out.length()));
            }
        } catch (Exception failure) {
            return "Could not read pending crash: " + failure;
        }
        return out.toString();
    }
}
