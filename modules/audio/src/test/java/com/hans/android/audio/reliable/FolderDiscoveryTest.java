package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public class FolderDiscoveryTest {
    @Test public void manifestFolderIsRecoveredEvenUnderDefaultDirectory()
            throws Exception {
        File root = Files.createTempDirectory("folders").toFile();
        File session = new File(root,
                "default/sessions/03243605-daaa-416a-85e1-3c35f1ff21eb");
        assertTrue(session.mkdirs());
        ReliableSessionManifest manifest = new ReliableSessionManifest();
        manifest.sessionId = "03243605-daaa-416a-85e1-3c35f1ff21eb";
        manifest.folderId = "agents";
        manifest.folderName = "agents";
        manifest.createdAt = 1234L;
        try (FileOutputStream out = new FileOutputStream(
                new File(session, "manifest.json"))) {
            out.write(manifest.toJson().toString().getBytes(StandardCharsets.UTF_8));
        }
        List<ReliableSessionStore.Folder> folders =
                ReliableSessionStore.discoverFoldersFromDisk(root);
        assertTrue(folders.stream().anyMatch(folder ->
                "default".equals(folder.id)));
        ReliableSessionStore.Folder agents = folders.stream()
                .filter(folder -> "agents".equals(folder.id))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("agents", agents.name);
    }
}
