package com.hans.android.network.reliable;

final class RetryBackoff {
    static final long BASE_DELAY_MS = 1_000L;
    static final long MAX_DELAY_MS = 5L * 60L * 1_000L;

    private RetryBackoff() {}

    static long fullJitterDelayMs(int attempt, long retryAfterMs,
                                  double randomUnit) {
        int normalizedAttempt = Math.max(1, Math.min(30, attempt));
        long exponential = BASE_DELAY_MS;
        for (int i = 1; i < normalizedAttempt && exponential < MAX_DELAY_MS; i++) {
            exponential = Math.min(MAX_DELAY_MS, exponential * 2L);
        }
        double normalizedRandom = Math.max(0d, Math.min(0.999999999d, randomUnit));
        long jitter = (long)(normalizedRandom * (exponential + 1d));
        return Math.max(Math.max(0L, retryAfterMs), jitter);
    }
}
