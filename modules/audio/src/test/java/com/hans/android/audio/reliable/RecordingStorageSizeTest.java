package com.hans.android.audio.reliable;

import static org.junit.Assert.*;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecordingStorageSizeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void largeLibraryUsesMetadataWithoutReadingGigabytes() throws Exception {
        File folder = temp.newFolder("recordings");
        File nested = new File(folder, "folder-one");
        assertTrue(nested.mkdir());
        try (RandomAccessFile wav = new RandomAccessFile(new File(nested,"big.pcm"), "rw")) {
            wav.setLength(10_000_000_000L);
        }
        try (RandomAccessFile meta = new RandomAccessFile(new File(folder,"manifest.json"), "rw")) {
            meta.write(new byte[]{1,2,3});
        }
        assertEquals(10_000_000_003L,
                ReliableSessionStore.directoryBytesInterruptible(folder, () -> false));
    }

    @Test public void recordingStartAbortsAnInFlightSizeScan() throws Exception {
        File folder=temp.newFolder("many-files");
        for(int i=0;i<16;i++)assertTrue(new File(folder,"record-"+i).createNewFile());
        AtomicInteger checked=new AtomicInteger();
        try {
            ReliableSessionStore.directoryBytesInterruptible(folder,
                    () -> checked.incrementAndGet()>4);
            fail("A recording start must preempt slow historical file traversal");
        } catch(InterruptedIOException expected) {
            assertTrue(checked.get()>=5);
        }
    }
}
