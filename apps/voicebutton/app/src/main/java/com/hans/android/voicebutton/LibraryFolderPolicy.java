package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.ReliableSessionStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class LibraryFolderPolicy {
    private LibraryFolderPolicy() {}

    static List<ReliableSessionStore.Folder> visibleFolders(
            List<ReliableSessionStore.Folder> allFolders,
            List<ReliableSessionStore.Folder> directChildren,
            boolean atRoot) {
        List<ReliableSessionStore.Folder> result = new ArrayList<>(
                atRoot ? allFolders : directChildren);
        result.sort(Comparator.comparing(value -> value.path,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }
}
