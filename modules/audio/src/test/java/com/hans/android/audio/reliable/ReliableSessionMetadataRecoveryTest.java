package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ReliableSessionMetadataRecoveryTest {
    @Test public void corruptPrimaryFallsBackToDurablePausedBackup()
            throws Exception {
        File directory = Files.createTempDirectory("voicebutton-manifest-backup").toFile();
        File primary = new File(directory, "manifest.json");
        File backup = new File(directory, "manifest.json.bak");
        Files.write(primary.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "paused-session";
        value.createdAt = 1234L;
        value.paused = true;
        value.recordingFinished = false;
        Files.write(backup.toPath(), value.toJson().toString()
                .getBytes(StandardCharsets.UTF_8));

        ReliableSessionManifest recovered =
                ReliableSessionStore.readManifestRecoveringBackup(primary);

        assertEquals("paused-session", recovered.sessionId);
        assertTrue(recovered.paused);
        assertTrue(recovered.isOpen());
    }

    @Test public void missingPrimaryCanReadSurvivingBackup() throws Exception {
        File directory = Files.createTempDirectory("voicebutton-manifest-missing").toFile();
        File primary = new File(directory, "manifest.json");
        File backup = new File(directory, "manifest.json.bak");
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = "survivor";
        value.createdAt = 4321L;
        Files.write(backup.toPath(), value.toJson().toString()
                .getBytes(StandardCharsets.UTF_8));

        ReliableSessionManifest recovered =
                ReliableSessionStore.readManifestRecoveringBackup(primary);

        assertEquals("survivor", recovered.sessionId);
    }
}
