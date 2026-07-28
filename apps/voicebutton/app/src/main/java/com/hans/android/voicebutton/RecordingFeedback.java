package com.hans.android.voicebutton;

final class RecordingFeedback {
    private RecordingFeedback() {}

    static int levelPermille(float peakDbfs) {
        if (Float.isNaN(peakDbfs) || peakDbfs <= -60f) return 0;
        if (peakDbfs >= 0f) return 1000;
        return Math.max(0, Math.min(1000,
                Math.round((peakDbfs + 60f) * 1000f / 60f)));
    }

    static int uploadPermille(long durableBytes, long totalBytes) {
        if (totalBytes <= 0L || durableBytes <= 0L) return 0;
        if (durableBytes >= totalBytes) return 1000;
        return (int)Math.max(0L, Math.min(1000L,
                durableBytes * 1000L / totalBytes));
    }
}
