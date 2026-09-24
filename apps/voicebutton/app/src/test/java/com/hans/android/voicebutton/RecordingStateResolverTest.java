package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.hans.android.audio.reliable.ReliableSessionManifest;

import org.junit.Test;

public class RecordingStateResolverTest {
    @Test public void pausedMetadataOverridesBackgroundSynchronization() {
        assertEquals("PAUSED", RecordingStateResolver.normalize(
                "SYNCHRONIZING", false, true, false));
        assertEquals(RecordingService.ACTION_RESUME,
                RecordingStateResolver.primaryAction(false, true, false));
    }

    @Test public void liveRecorderOverridesStalePausedState() {
        assertEquals("RECORDING", RecordingStateResolver.normalize(
                "PAUSED", true, false, false));
        assertEquals(RecordingService.ACTION_PAUSE,
                RecordingStateResolver.primaryAction(true, false, false));
        assertEquals(RecordingService.ACTION_PAUSE,
                RecordingStateResolver.primaryAction(true, true, false));
    }

    @Test public void pauseTransitionCannotBecomeRecoveryState() {
        assertEquals("PAUSING", RecordingStateResolver.normalize(
                "PAUSING", false, false, true));
    }

    @Test public void interruptedMetadataRequiresRecoveryWhenIdle() {
        assertEquals("RECOVERY REQUIRED", RecordingStateResolver.normalize(
                "SYNCHRONIZING", false, false, true));
        assertEquals("RECOVERY",
                RecordingStateResolver.primaryAction(false, false, true));
    }
    @Test public void uploadExplanationCannotReplaceRecordingOrPausedExplanation() {
        assertEquals("Audio is journaled locally and compressed in the background",
                RecordingStateResolver.explanation("RECORDING", "Sending segment 2 of 2"));
        assertEquals("Recording is paused and ready to play or resume",
                RecordingStateResolver.explanation("PAUSED", "Reconciling server storage"));
        assertEquals("Stored completely on the server",
                RecordingStateResolver.explanation("READY", "Stored completely on the server"));
    }

    @Test public void readyStateRemainsReadyAfterCommitCompletion() {
        assertEquals("READY", RecordingStateResolver.normalize(
                "READY", false, false, false));
        assertEquals("Stored completely on the server",
                RecordingStateResolver.explanation("READY", "Stored completely on the server"));
    }


    @Test public void startingRemainsAnOperationStateBeforeCaptureThreadOpens() {
        assertEquals("STARTING", RecordingStateResolver.normalize(
                "STARTING", false, false, false));
    }

    @Test public void finishedLiveManifestCannotBeResurrectedAsPaused() {
        ReliableSessionManifest cached = new ReliableSessionManifest();
        cached.sessionId = "session";
        cached.paused = true;
        cached.recordingFinished = false;
        ReliableSessionManifest live = new ReliableSessionManifest();
        live.sessionId = "session";
        live.paused = true;
        live.recordingFinished = true;
        live.conversionFinished = true;
        assertNull(RecordingStateResolver.authoritativeOpenSession(cached, live));
    }

    @Test public void resumedLiveManifestOverridesCachedPausedFlag() {
        ReliableSessionManifest cached = new ReliableSessionManifest();
        cached.sessionId = "session";
        cached.paused = true;
        ReliableSessionManifest live = new ReliableSessionManifest();
        live.sessionId = "session";
        live.paused = false;
        live.state = "RECORDING";
        assertSame(live,
                RecordingStateResolver.authoritativeOpenSession(cached, live));
    }

    @Test public void stalePausedWithoutOpenSessionBecomesReady() {
        assertEquals("READY", RecordingStateResolver.normalize(
                "PAUSED", false, false, false));
        assertEquals("READY", RecordingStateResolver.normalize(
                "RECORDING", false, false, false));
        assertEquals("SYNCHRONIZING", RecordingStateResolver.normalize(
                "SYNCHRONIZING", false, false, false));
    }
}
