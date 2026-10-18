package com.hans.android.voicebutton;

import static org.junit.Assert.*;
import com.hans.android.audio.reliable.ReliableSessionManifest;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class BackupOverviewTextTest {
    private static ReliableSessionManifest session(String id, boolean finished,
            boolean converted, boolean committed) {
        ReliableSessionManifest m = new ReliableSessionManifest();
        m.sessionId = id;
        m.recordingFinished = finished;
        m.conversionFinished = converted;
        m.remoteCommitted = committed;
        ReliableSessionManifest.Segment a = new ReliableSessionManifest.Segment();
        a.seq = 0;
        a.mp3Bytes = converted ? 100 : 0;
        m.segments.add(a);
        return m;
    }

    @Test public void idleAndOpenAudioNeverPretendToUpload() {
        assertEquals("", BackupOverviewText.summary(Collections.emptyList(),
                true, false, "idle", ""));
        assertEquals("", BackupOverviewText.summary(Arrays.asList(
                session("open", false, false, false)), true, false, "idle", "open"));
        assertEquals("", BackupOverviewText.summary(Arrays.asList(
                session("done", true, true, true)), true, false, "upload_chunk", "done"));
    }

    @Test public void staleOperationsDoNotCreateProgressOnAlreadyUploadedFiles() {
        assertEquals("", BackupOverviewText.summary(Arrays.asList(
                session("done", true, true, true)), true, false, "stopped", "done"));
    }

    @Test public void pendingUploadHasOneStableHumanMeaningNotFalsePercent() {
        ReliableSessionManifest pending = session("pending", true, true, false);
        assertEquals("1 recording awaiting backup", BackupOverviewText.summary(
                Arrays.asList(pending), true, false, "idle", "pending"));
        assertEquals("1 recording awaiting backup", BackupOverviewText.summary(
                Arrays.asList(pending), true, false, "stopped", "pending"));
        assertEquals("Backing up 1 recording", BackupOverviewText.summary(
                Arrays.asList(pending), true, false, "upload_chunk", "pending"));
        assertEquals("1 recording awaiting backup", BackupOverviewText.summary(
                Arrays.asList(pending), true, false, "upload_chunk", "old-session"));
    }

    @Test public void explicitExitKeepsFilesLocalAndNeverClaimsUploadProgress() {
        ReliableSessionManifest pending = session("pending", true, true, false);
        assertEquals("Backup paused · 1 recording saved on phone",
                BackupOverviewText.summary(Arrays.asList(pending),
                        false, true, "retry_backoff", "pending"));
        assertEquals("Backup delayed · 1 recording saved on phone",
                BackupOverviewText.summary(Arrays.asList(pending),
                        true, false, "retry_backoff", "pending"));
        assertEquals("Backup needs attention · 1 recording saved on phone",
                BackupOverviewText.summary(Arrays.asList(pending),
                        true, false, "paused_permanent", "pending"));
    }
}
