package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class FolderTreeModelTest {
    @Test public void folderNeedsSyncWhenParentChanges() {
        ReliableSessionStore.Folder value = new ReliableSessionStore.Folder(
                "child", "Child", "parent", 1L,
                "Child", "", "Parent/Child");
        assertTrue(value.needsRemoteSync());
    }

    @Test public void folderExposesFullPath() {
        ReliableSessionStore.Folder value = new ReliableSessionStore.Folder(
                "child", "Child", "parent", 1L,
                "Child", "parent", "Parent/Child");
        assertEquals("Parent/Child", value.path);
    }
}
