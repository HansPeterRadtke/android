package com.hans.android.network.reliable;

import java.util.concurrent.atomic.AtomicBoolean;

final class UploadStallWatchdog implements AutoCloseable {
    static final long DEFAULT_TIMEOUT_MS = 2L * 60L * 60L * 1000L;

    private final long timeoutNanos;
    private final Runnable timeoutAction;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean timedOut = new AtomicBoolean(false);
    private volatile long lastHeartbeatNanos = System.nanoTime();
    private final Thread thread;

    UploadStallWatchdog(long timeoutMs, Runnable timeoutAction) {
        if (timeoutMs <= 0L) throw new IllegalArgumentException("timeoutMs must be positive");
        this.timeoutNanos = timeoutMs * 1_000_000L;
        this.timeoutAction = timeoutAction;
        thread = new Thread(this::run, "voicebutton-upload-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    void heartbeat() {
        lastHeartbeatNanos = System.nanoTime();
    }

    boolean hasTimedOut() {
        return timedOut.get();
    }

    static boolean isExpired(long lastNanos, long nowNanos, long timeoutNanos) {
        return timeoutNanos > 0L && nowNanos - lastNanos >= timeoutNanos;
    }

    private void run() {
        while (!closed.get()) {
            long remaining = timeoutNanos - (System.nanoTime() - lastHeartbeatNanos);
            if (remaining <= 0L) {
                if (timedOut.compareAndSet(false, true) && !closed.get()) {
                    try { timeoutAction.run(); } catch (RuntimeException ignored) {}
                }
                return;
            }
            long sleepMs = Math.max(25L, Math.min(500L, remaining / 1_000_000L));
            try { Thread.sleep(sleepMs); }
            catch (InterruptedException interrupted) {
                if (closed.get()) return;
            }
        }
    }

    @Override public void close() {
        closed.set(true);
        thread.interrupt();
    }
}
