package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MainScreenTextTest {
    @Test public void defaultScreenHidesProtocolDetails() {
        String waiting = MainScreenText.transfer(true, 0L, 0L, 0);
        String value = MainScreenText.transfer(true, 1000L, 500L, 500);
        assertTrue(waiting.contains("current recording protected locally"));
        assertTrue(value.contains("earlier audio syncing"));
        assertFalse(value.contains("50.0%"));
        assertFalse(waiting.contains("chunk"));
        assertFalse(value.contains("chunk"));
        assertFalse(value.contains("HTTP"));
        assertFalse(value.contains("sha"));
    }

    @Test public void quietSignalIsExplicitlyStillRecording() {
        assertTrue(MainScreenText.microphone(true, false).contains("recording continues"));
    }
    @Test public void overviewStateDoesNotExposeUploaderDebugNoise() {
        String value = MainScreenText.stateSummary("SYNCHRONIZING", false, false,
                false, false);
        assertFalse(value.contains("chunk"));
        assertFalse(value.contains("revision"));
        assertFalse(value.contains("HTTP"));
    }

    @Test public void structureKeyIgnoresVolatileExplanationText() {
        String first = MainScreenText.structureKey("RECORDING", true, false,
                false, false, true, "default", 4, 10);
        String second = MainScreenText.structureKey("RECORDING", true, false,
                false, false, true, "default", 4, 10);
        org.junit.Assert.assertEquals(first, second);
    }


    @Test public void compressingNeverClaimsServerComplete() {
        String value = MainScreenText.transfer("COMPRESSING", false,
                1_173_328_416L, 0L, 1000,
                "local_finalizing", 0, 0L, 0L);
        assertTrue(value.contains("still finalizing locally"));
                assertFalse(value.contains("Upload: complete"));
        assertTrue(MainScreenText.isCurrentRecordingUnmeasured(
                "COMPRESSING", "local_finalizing"));
    }

    @Test public void finishedUploadCanClaimComplete() {
        String value = MainScreenText.transfer("READY", false,
                1_200_000L, 0L, 1000,
                "idle", -1, 0L, 0L);
        assertEquals("Upload: complete · acknowledged by Jetson", value);
        assertFalse(MainScreenText.isCurrentRecordingUnmeasured("READY", "idle"));
    }

    @Test public void retryBackoffExplainsWhyProgressCannotAdvance() {
        String value = MainScreenText.transfer("SYNCHRONIZING", false,
                100_000_000L, 52_000_000L, 480,
                "retry_backoff", -1, 0L, 0L);
        assertTrue(value.contains("unavailable"));
        assertTrue(value.contains("retrying"));
        assertTrue(value.contains("waiting"));
    }

    @Test public void jetsonHealthStatesAreExplicit() {
        assertEquals("Jetson: reachable · updated now",
                MainScreenText.jetsonHealth(true, true, 0L));
        assertEquals("Jetson: status unavailable · local recording still works",
                MainScreenText.jetsonHealth(false, false, 0L));
        assertEquals("Jetson: status unavailable · last seen 2m ago · local recording still works",
                MainScreenText.jetsonHealth(true, false, 120_000L));
    }

    @Test public void overviewUsesProgressiveDisclosure() {
        assertFalse(MainScreenText.shouldShowTimer("READY", false, false, false));
        assertTrue(MainScreenText.shouldShowTimer("RECORDING", true, false, true));
        assertTrue(MainScreenText.shouldShowSetup("READY", false, false, false, false));
        assertFalse(MainScreenText.shouldShowSetup("STARTING", false, false, false, false));
        assertFalse(MainScreenText.shouldShowSetup("RECORDING", true, true, false, false));
        assertFalse(MainScreenText.shouldShowUploadStatus("READY", false, 0L, "idle"));
        assertFalse(MainScreenText.shouldShowUploadStatus("RECORDING", true, 50_000L,
                "upload_chunk"));
        assertTrue(MainScreenText.shouldShowUploadStatus("SYNCHRONIZING", false, 50_000L,
                "upload_chunk"));
        assertTrue(MainScreenText.shouldShowUploadStatus("SYNCHRONIZING", true, 50_000L,
                "retry_backoff"));
    }

    @Test public void primaryStateLabelsUseUserLanguage() {
        assertEquals("Ready to record",
                MainScreenText.stateTitle("READY", false, false, false));
        assertEquals("Recording",
                MainScreenText.stateTitle("RECORDING", true, false, false));
        assertEquals("Recording paused",
                MainScreenText.stateTitle("PAUSED", false, true, false));
        assertEquals("Recording needs attention",
                MainScreenText.stateTitle("FAILED", false, false, true));
    }

    @Test public void backupNoticeIsProgressiveDisclosure() {
        assertEquals("Backup complete · 100%",
                MainScreenText.backupNotice(false, 0L, 1000, "idle"));
        assertTrue(MainScreenText.shouldShowBackupNotice(false, 0L, "idle"));
        String pending = MainScreenText.backupNotice(false, 65_312_064L, 971,
                "reconcile");
        assertTrue(pending.startsWith("Backing up · 97.1%"));
        assertTrue(pending.contains("remaining"));
        assertTrue(MainScreenText.shouldShowBackupNotice(false, 65_312_064L,
                "reconcile"));
        String recordingPending = MainScreenText.backupNotice(true, 65_312_064L, 971,
                "upload_chunk");
        assertTrue(recordingPending.startsWith("Backing up · 97.1%"));
        assertTrue(recordingPending.contains("recording safe on this phone"));
        assertTrue(MainScreenText.shouldShowBackupNotice(true, 65_312_064L,
                "upload_chunk"));
        String delayed = MainScreenText.backupNotice(true, 65_312_064L, 971,
                "retry_backoff");
        assertTrue(delayed.contains("Backup delayed"));
        assertTrue(delayed.contains("safe on this phone"));
        assertTrue(MainScreenText.shouldShowBackupNotice(true, 65_312_064L,
                "retry_backoff"));
    }

    @Test public void overviewTextAvoidsIdleAndNormalStateNoise() {
        assertEquals("", MainScreenText.localProtection("gui", false));
        assertEquals("Safe on this phone · gui",
                MainScreenText.localProtection("gui", true));
        assertEquals("", MainScreenText.microphone(false, false));
        assertEquals("Input detected", MainScreenText.microphone(true, true));
        assertEquals("Input quiet · recording continues",
                MainScreenText.microphone(true, false));
        assertEquals("", MainScreenText.stateSummary("RECORDING", true, false,
                false, true));
        assertEquals("", MainScreenText.stateSummary("PAUSED", false, true,
                false, true));
    }

    @Test public void localEncodingShowsCurrentBackupState() {
        String idle = MainScreenText.backupNotice(false, 1234L, 1000,
                "waiting_local_encoding");
        assertEquals("Preparing backup · local MP3 encoding", idle);
        String recording = MainScreenText.backupNotice(true, 1234L, 1000,
                "waiting_local_encoding");
        assertTrue(recording.contains("Preparing backup · local MP3 encoding"));
        assertTrue(recording.contains("recording safe on this phone"));
        assertTrue(MainScreenText.shouldShowBackupNotice(true, 1234L,
                "waiting_local_encoding"));
    }

    @Test public void recordingWithoutClosedBytesStillShowsBackupState() {
        String value = MainScreenText.backupNotice(true, 0L, 1000, "idle");
        assertTrue(value.contains("Backup current recording"));
        assertTrue(value.contains("progress becomes measurable"));
        assertTrue(MainScreenText.shouldShowBackupNotice(true, 0L, "idle"));
    }

    @Test public void overviewShowsExactRequestedProgressFields() {
        assertEquals("Upload overall: 97.1% · 3 files left",
                MainScreenText.uploadOverall(971, 3, false));
        assertEquals("Upload current file: Recording.mp3 · 62.5%",
                MainScreenText.uploadCurrent("Recording.mp3", 625, 3, "upload_chunk"));
        assertEquals("Transcription overall: 83% · 4 files left",
                MainScreenText.transcriptionOverall(83, 4, ""));
        assertEquals("Transcription current file: Recording.mp3 · 37%",
                MainScreenText.transcriptionCurrent("Recording.mp3", 37, 4, "transcribing"));
        assertEquals("0 files left", MainScreenText.filesLeftLabel(0));
        assertEquals("1 file left", MainScreenText.filesLeftLabel(1));
    }
}
