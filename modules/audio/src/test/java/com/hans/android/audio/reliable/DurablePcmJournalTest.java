package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import org.junit.Test;

public class DurablePcmJournalTest {
    @Test public void thirtyMinuteFortyEightKilohertzStreamIsExact()
            throws Exception {
        File directory = Files.createTempDirectory("pcm-journal").toFile();
        File published = null;
        try {
            DurablePcmJournal journal = new DurablePcmJournal(
                    directory, 7, 48000);
            short[] oneSecond = new short[48000];
            for (int i = 0; i < oneSecond.length; i++) {
                oneSecond[i] = (short)(i - 24000);
            }
            int seconds = 30 * 60;
            long maxSyncMs = 0L;
            for (int second = 0; second < seconds; second++) {
                journal.append(oneSecond, oneSecond.length);
                maxSyncMs = Math.max(maxSyncMs, journal.sync());
            }
            published = journal.publish();
            assertTrue(published.isFile());
            assertEquals((long)seconds * oneSecond.length * 2L,
                    published.length());
            assertFalse(journal.openFile().exists());
            assertTrue(maxSyncMs < 30000L);
            byte[] first = new byte[2];
            try (FileInputStream input = new FileInputStream(published)) {
                assertEquals(2, input.read(first));
            }
            assertEquals(oneSecond[0] & 0xff, first[0] & 0xff);
            assertEquals((oneSecond[0] >>> 8) & 0xff,
                    first[1] & 0xff);
        } finally {
            if (published != null) published.delete();
            directory.delete();
        }
    }

    @Test public void synchronizedOpenJournalSurvivesForCrashRecovery()
            throws Exception {
        File directory = Files.createTempDirectory("pcm-open").toFile();
        DurablePcmJournal journal = new DurablePcmJournal(directory, 3, 48000);
        short[] samples = new short[] {1, -2, 3, -4};
        journal.append(samples, samples.length);
        journal.closePreservingOpenJournal();
        assertTrue(journal.openFile().isFile());
        assertEquals(8L, journal.openFile().length());
        assertTrue(journal.openFile().getName().endsWith(".open.pcm"));
        assertFalse(journal.closedFile().exists());
    }
    @Test public void recoveryRecognizesOpenAndClosedJournalNames() {
        assertTrue(ReliableSessionStore.isPcmJournalName(
                "segment_000003_48000.open.pcm"));
        assertTrue(ReliableSessionStore.isOpenPcmJournalName(
                "segment_000003_48000.open.pcm"));
        assertTrue(ReliableSessionStore.isPcmJournalName(
                "segment_000003_48000.pcm"));
        assertFalse(ReliableSessionStore.isOpenPcmJournalName(
                "segment_000003_48000.pcm"));
        assertFalse(ReliableSessionStore.isPcmJournalName(
                "segment_000003.mp3"));
    }

}
