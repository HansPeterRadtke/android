package com.hans.android.voicebutton;

import static org.junit.Assert.*;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class CurrentUploadPresentationTest {
    private static ReliableSessionManifest session(String id, long createdAt,
                                                   long total, long acked, boolean finished) {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = id;
        value.displayName = id;
        value.createdAt = createdAt;
        value.recordingFinished = finished;
        value.conversionFinished = finished;
        ReliableSessionManifest.Segment segment = new ReliableSessionManifest.Segment();
        segment.seq = 0;
        segment.mp3Bytes = total;
        segment.remotePartialBytes = acked;
        segment.remoteAccepted = acked >= total;
        value.segments.add(segment);
        return value;
    }

    @Test public void liveRecordingWinsOverOlderPendingFiles() {
        ReliableSessionManifest older = session("older", 100L, 200L, 100L, true);
        ReliableSessionManifest newer = session("newer", 200L, 200L, 20L, true);
        CurrentUploadPresentation.State state = CurrentUploadPresentation.from(
                Arrays.asList(older, newer), Collections.emptySet(),
                "newer", "upload_chunk", 0, 80L);
        assertEquals("newer.mp3", state.fileName);
        assertEquals(400, state.progressPermille);
        assertEquals("Uploading now", state.phase);
        assertEquals(1, state.otherFilesWaiting);
    }

    @Test public void withoutLiveTransferTheNewestPendingRecordingIsShown() {
        CurrentUploadPresentation.State state = CurrentUploadPresentation.from(
                Arrays.asList(session("older", 100L, 100L, 0L, true),
                        session("newer", 200L, 100L, 20L, true)),
                Collections.emptySet(), "", "idle", -1, 0L);
        assertEquals("newer.mp3", state.fileName);
        assertEquals(200, state.progressPermille);
        assertEquals("Waiting to continue upload", state.phase);
    }

    @Test public void historicalCommittedAudioCannotSkewNewRecordingProgress() {
        ReliableSessionManifest completed = session("completed", 100L, 10000L, 10000L, true);
        completed.remoteCommitted = true;
        ReliableSessionManifest active = session("active", 200L, 100L, 10L, true);
        CurrentUploadPresentation.State state = CurrentUploadPresentation.from(
                Arrays.asList(completed, active), Collections.emptySet(),
                "active", "upload_chunk", 0, 50L);
        assertEquals(500, state.progressPermille);
        assertEquals(0, state.otherFilesWaiting);
    }

    @Test public void unencodedAudioHasNoInventedPercentage() {
        ReliableSessionManifest value = session("new", 100L, 0L, 0L, true);
        value.conversionFinished = false;
        CurrentUploadPresentation.State state = CurrentUploadPresentation.from(
                Collections.singletonList(value), Collections.emptySet(),
                "", "idle", -1, 0L);
        assertEquals(-1, state.progressPermille);
        assertEquals("Preparing audio for upload", state.phase);
    }

    @Test public void serverCommitIsDistinctFromSendingTheAudio() {
        ReliableSessionManifest value = session("sent", 100L, 500L, 500L, true);
        CurrentUploadPresentation.State state = CurrentUploadPresentation.from(
                Collections.singletonList(value), Collections.emptySet(),
                "sent", "idle", -1, 0L);
        assertEquals(1000, state.progressPermille);
        assertEquals("Audio sent · waiting for server confirmation", state.phase);
    }

    @Test public void liveUploadWithoutRefreshedManifestHasNoFakePercentage() {
        CurrentUploadPresentation.State state =
                CurrentUploadPresentation.activeWithoutManifest("new-session", "upload_chunk");
        assertNotNull(state);
        assertEquals("current recording", state.fileName);
        assertEquals(-1, state.progressPermille);
        assertEquals("Uploading now · measuring full recording", state.phase);
        assertNull(CurrentUploadPresentation.activeWithoutManifest("", "upload_chunk"));
        assertNull(CurrentUploadPresentation.activeWithoutManifest("new-session", "idle"));
    }

    @Test public void allCommittedMeansNoTransferWidget() {
        ReliableSessionManifest value = session("sent", 100L, 500L, 500L, true);
        value.remoteCommitted = true;
        assertNull(CurrentUploadPresentation.from(
                Collections.singletonList(value), Collections.emptySet(),
                "", "idle", -1, 0L));
    }
}
