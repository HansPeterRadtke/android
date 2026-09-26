package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import com.hans.android.audio.reliable.ReliableSessionStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class LibraryFolderPolicyTest {
    private static ReliableSessionStore.Folder folder(
            String id, String name, String parent, String path) {
        return new ReliableSessionStore.Folder(id, name, parent, 1L,
                name, parent, path);
    }

    @Test public void recordingsRootShowsNestedFoldersToo() {
        ReliableSessionStore.Folder development = folder(
                "development", "development", "", "development");
        ReliableSessionStore.Folder general = folder(
                "general", "general", "development", "development/general");
        ReliableSessionStore.Folder programming = folder(
                "programming", "programming", "general",
                "development/general/programming");
        List<ReliableSessionStore.Folder> visible = LibraryFolderPolicy.visibleFolders(
                Arrays.asList(programming, development, general),
                Collections.singletonList(development), true);
        assertEquals(3, visible.size());
        assertEquals("development", visible.get(0).path);
        assertEquals("development/general", visible.get(1).path);
        assertEquals("development/general/programming", visible.get(2).path);
    }

    @Test public void insideFolderShowsOnlyDirectChildren() {
        ReliableSessionStore.Folder direct = folder(
                "general", "general", "development", "development/general");
        ReliableSessionStore.Folder nested = folder(
                "programming", "programming", "general",
                "development/general/programming");
        List<ReliableSessionStore.Folder> visible = LibraryFolderPolicy.visibleFolders(
                Arrays.asList(direct, nested), Collections.singletonList(direct), false);
        assertEquals(1, visible.size());
        assertEquals("development/general", visible.get(0).path);
    }
}
