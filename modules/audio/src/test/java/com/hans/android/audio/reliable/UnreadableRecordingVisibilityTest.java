package com.hans.android.audio.reliable;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UnreadableRecordingVisibilityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void unreadableRecordingIsVisibleWithoutMutatingAudioOrManifest()
            throws Exception {
        File session = temp.newFolder("session_test_123");
        File manifest = new File(session, "manifest.json");
        File journal = new File(session, "segment_000000_48000.open.pcm");
        byte[] original = "{bad json".getBytes(StandardCharsets.UTF_8);
        byte[] pcm = new byte[]{4, 3, 2, 1};
        Files.write(manifest.toPath(), original);
        Files.write(journal.toPath(), pcm);
        ReliableSessionManifest listing =
                ReliableSessionStore.unreadableListingPlaceholder(
                        session, "default", new IOException("injected corrupted metadata"));
        assertEquals("session_test_123", listing.sessionId);
        assertEquals("default", listing.folderId);
        assertEquals("UNREADABLE_METADATA", listing.state);
        assertFalse(listing.isInterrupted());
        assertFalse(listing.paused);
        assertFalse(listing.recordingFinished);
        assertFalse(listing.autoResumeRequested);
        assertTrue(listing.displayName.contains("recovery"));
        assertTrue(listing.error.contains("unreadable"));
        assertArrayEquals(original, Files.readAllBytes(manifest.toPath()));
        assertArrayEquals(pcm, Files.readAllBytes(journal.toPath()));
    }
}
