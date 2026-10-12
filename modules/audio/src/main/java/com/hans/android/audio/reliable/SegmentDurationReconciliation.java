package com.hans.android.audio.reliable;

import java.io.File;
import java.io.IOException;

/** Correct duration corruption in recordings created by older app versions.
 * The capture sample count is independent of a mistaken MP3 timebase.
 */
final class SegmentDurationReconciliation {
    private SegmentDurationReconciliation() {}

    static boolean repairIfInflated(ReliableSessionManifest.Segment segment,
                                    File encodedMp3) throws IOException {
        if (segment == null || segment.durationMs <= 0L
                || segment.sampleRate <= 0 || segment.sampleRate > 384000
                || segment.endSample <= segment.startSample) {
            return false;
        }
        long capturedDurationMs = WavDurations.pcmDurationMs(
                (segment.endSample - segment.startSample),
                segment.sampleRate, 1);
        if (capturedDurationMs <= 0L) return false;
        // Encoder flushing can add a small amount of silence. Only correct
        // metadata that is clearly inconsistent with actual captured samples.
        long toleranceMs = Math.max(250L, capturedDurationMs / 5L);
        long delta = segment.durationMs > capturedDurationMs
                ? segment.durationMs - capturedDurationMs
                : capturedDurationMs - segment.durationMs;
        if (delta <= toleranceMs) return false;

        long correctedMs = capturedDurationMs;
        if (encodedMp3 != null && encodedMp3.isFile()) {
            Mp3Frames.Stats frames = Mp3Frames.inspect(encodedMp3);
            if (frames.frames == 0L || frames.durationMs <= 0L) {
                // An unreadable MP3 should not destroy the capture-based
                // timing correction or silently replace the original file.
                throw new IOException("Cannot confirm MP3 duration for local repair");
            }
            correctedMs = frames.durationMs;
        }
        if (segment.durationMs == correctedMs) return false;
        segment.durationMs = correctedMs;
        return true;
    }
}
