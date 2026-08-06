package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import org.junit.Test;

public class RecordingPlaybackPolicyTest {
    @Test public void existingFinalFileOpensImmediately() {
        ReliableSessionManifest manifest = new ReliableSessionManifest();
        assertEquals(RecordingPlaybackPolicy.Action.READY,
                RecordingPlaybackPolicy.decide(manifest, true, true));
    }

    @Test public void finishedRecordingIsFinalizedBeforePlayback() {
        ReliableSessionManifest manifest = withAudio();
        manifest.recordingFinished = true;
        assertEquals(RecordingPlaybackPolicy.Action.FINALIZE,
                RecordingPlaybackPolicy.decide(manifest, false, false));
    }

    @Test public void interruptedRecordingGetsSafePreview() {
        ReliableSessionManifest manifest = withAudio();
        manifest.recordingFinished = false;
        manifest.state = "INTERRUPTED";
        assertEquals(RecordingPlaybackPolicy.Action.PREVIEW,
                RecordingPlaybackPolicy.decide(manifest, false, false));
    }

    @Test public void conversionNeverCompetesWithActiveCapture() {
        ReliableSessionManifest manifest = withAudio();
        assertEquals(RecordingPlaybackPolicy.Action.BLOCK_CAPTURE,
                RecordingPlaybackPolicy.decide(manifest, false, true));
    }

    @Test public void emptyRecordingCannotPretendToBePlayable() {
        ReliableSessionManifest manifest = new ReliableSessionManifest();
        manifest.remoteCommitted = false;
        assertEquals(RecordingPlaybackPolicy.Action.NO_AUDIO,
                RecordingPlaybackPolicy.decide(manifest, false, false));
    }

    private static ReliableSessionManifest withAudio() {
        ReliableSessionManifest manifest = new ReliableSessionManifest();
        ReliableSessionManifest.Segment segment =
                new ReliableSessionManifest.Segment();
        segment.seq = 0;
        segment.pcmJournalName = "segment_000000_16000.pcm";
        segment.pcmBytes = 32000L;
        manifest.segments.add(segment);
        manifest.totalPcmBytes = 32000L;
        return manifest;
    }
}
