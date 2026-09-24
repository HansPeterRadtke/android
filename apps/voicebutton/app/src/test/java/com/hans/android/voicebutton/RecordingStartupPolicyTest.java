package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import org.junit.Test;

public class RecordingStartupPolicyTest {
    @Test public void pausedOpenSessionRestoresPausedBeforeReady() {
        ReliableSessionManifest open = new ReliableSessionManifest();
        open.sessionId = "paused";
        open.paused = true;
        open.recordingFinished = false;
        assertEquals("PAUSED", RecordingStartupPolicy.requestedState(open));
        assertEquals("Recording is paused and ready to play or resume",
                RecordingStartupPolicy.explanation(open));
    }

    @Test public void unfinishedNonPausedSessionRequiresRecovery() {
        ReliableSessionManifest open = new ReliableSessionManifest();
        open.sessionId = "interrupted";
        open.paused = false;
        open.recordingFinished = false;
        assertEquals("RECOVERY REQUIRED", RecordingStartupPolicy.requestedState(open));
    }

    @Test public void noOpenSessionIsReady() {
        assertEquals("READY", RecordingStartupPolicy.requestedState(null));
    }
}
