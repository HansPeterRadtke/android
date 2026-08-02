package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerOpenRouteTest {
    @Test public void phoneDocumentsUseRetainedFileDescriptor() {
        assertEquals(PlayerOpenRoute.CONTENT_FILE_DESCRIPTOR,
                PlayerOpenRoute.forScheme("content"));
    }

    @Test public void privateRecordingsUseDirectFilePath() {
        assertEquals(PlayerOpenRoute.FILE_PATH,
                PlayerOpenRoute.forScheme("file"));
    }

    @Test public void networkAndUnknownSchemesRemainUris() {
        assertEquals(PlayerOpenRoute.GENERIC_URI,
                PlayerOpenRoute.forScheme("https"));
        assertEquals(PlayerOpenRoute.GENERIC_URI,
                PlayerOpenRoute.forScheme(null));
    }
}
