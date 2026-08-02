package com.hans.android.voicebutton;

final class PlayerTimeline {
    private PlayerTimeline() {}

    static long logicalTime(long physicalTimeMs, boolean studio, float studioSpeed) {
        return studio ? Math.round(Math.max(0L, physicalTimeMs) * studioSpeed)
                : Math.max(0L, physicalTimeMs);
    }

    static long logicalLength(long physicalLengthMs, boolean studio, float studioSpeed) {
        return logicalTime(physicalLengthMs, studio, studioSpeed);
    }

    static long physicalTime(long logicalTimeMs, boolean studio, float studioSpeed) {
        if (!studio) return Math.max(0L, logicalTimeMs);
        return Math.round(Math.max(0L, logicalTimeMs) / Math.max(.01f, studioSpeed));
    }

    static int progress(long logicalTimeMs, long logicalLengthMs) {
        if (logicalLengthMs <= 0L) return 0;
        return (int)Math.max(0L, Math.min(1000L, logicalTimeMs * 1000L / logicalLengthMs));
    }

    static long fromProgress(int progress, long logicalLengthMs) {
        return Math.max(0L, logicalLengthMs * Math.max(0, Math.min(1000, progress)) / 1000L);
    }
}
