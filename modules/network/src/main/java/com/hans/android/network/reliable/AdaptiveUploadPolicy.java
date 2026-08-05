package com.hans.android.network.reliable;

final class AdaptiveUploadPolicy {
    static final int MIN_PART_BYTES = 4 * 1024;
    static final int INITIAL_PART_BYTES = 64 * 1024;
    static final int MAX_PART_BYTES = 1024 * 1024;
    private static final int FAST_SUCCESSES_TO_GROW = 4;
    private static final long FAST_SUCCESS_MS = 30_000L;
    private static final long SLOW_SUCCESS_MS = 120_000L;

    private int partBytes = INITIAL_PART_BYTES;
    private int fastSuccesses;

    synchronized int nextPartLength(long offset, long totalBytes) {
        if (offset < 0L || totalBytes < 0L || offset >= totalBytes) return 0;
        return (int)Math.min((long)partBytes, totalBytes - offset);
    }

    synchronized int currentPartBytes() { return partBytes; }

    synchronized void onPartSuccess(int transmittedBytes, long elapsedMs) {
        if (transmittedBytes <= 0) return;
        if (elapsedMs >= SLOW_SUCCESS_MS) {
            shrink();
            return;
        }
        if (elapsedMs <= FAST_SUCCESS_MS && transmittedBytes >= partBytes) {
            fastSuccesses++;
            if (fastSuccesses >= FAST_SUCCESSES_TO_GROW) {
                partBytes = Math.min(MAX_PART_BYTES, partBytes * 2);
                fastSuccesses = 0;
            }
        } else {
            fastSuccesses = 0;
        }
    }

    synchronized void onPartFailure() { shrink(); }

    private void shrink() {
        partBytes = Math.max(MIN_PART_BYTES, partBytes / 2);
        fastSuccesses = 0;
    }
}
