package com.hans.android.voicebutton;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class MainThreadWatchdog {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final long CHECK_MS = 250L;
    private static final long STALL_MS = 1500L;
    private static final long REPORT_COOLDOWN_MS = 10000L;

    private MainThreadWatchdog() {}

    static boolean isStalled(long pendingPostedMs, long nowMs, long thresholdMs) {
        return pendingPostedMs > 0L && nowMs - pendingPostedMs >= thresholdMs;
    }

    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Handler main = new Handler(Looper.getMainLooper());
        AtomicLong pendingPostedMs = new AtomicLong(0L);
        Thread watchdog = new Thread(() -> {
            long lastReport = 0L;
            while (!Thread.currentThread().isInterrupted()) {
                long now = SystemClock.elapsedRealtime();
                long pending = pendingPostedMs.get();
                if (pending == 0L) {
                    if (pendingPostedMs.compareAndSet(0L, now)) {
                        main.post(() -> pendingPostedMs.set(0L));
                    }
                } else if (isStalled(pending, now, STALL_MS)
                        && now - lastReport >= REPORT_COOLDOWN_MS) {
                    lastReport = now;
                    long latency = now - pending;
                    Log.e("VoiceButton", "Main thread stall detected: " + latency + " ms");
                    PhoneDiagnostics diagnostics = PhoneDiagnostics.get();
                    if (diagnostics != null) diagnostics.log(PhoneDiagnostics.ERROR,
                            "main_thread.stall", null,
                            "Main thread did not process the watchdog probe in time",
                            PhoneDiagnostics.fields("latency_ms", latency,
                                    "threshold_ms", STALL_MS));
                }
                try { Thread.sleep(CHECK_MS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "voicebutton-main-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }
}
