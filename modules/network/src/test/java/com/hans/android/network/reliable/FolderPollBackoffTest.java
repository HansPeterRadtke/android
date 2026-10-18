package com.hans.android.network.reliable;

import static org.junit.Assert.*;
import org.junit.Test;

public class FolderPollBackoffTest {
    @Test public void authorizationFailureCannotTriggerRapidFolderPolling() {
        assertEquals(300000L, ReliableUploader.folderPullDelayMs(
                new ReliableUploadClient.ProtocolException(401, "authorization required")));
        assertEquals(300000L, ReliableUploader.folderPullDelayMs(
                new ReliableUploadClient.ProtocolException(403, "forbidden")));
    }

    @Test public void transientErrorsAlsoHaveBackoff() {
        assertEquals(30000L, ReliableUploader.folderPullDelayMs(
                new ReliableUploadClient.ProtocolException(503, "server unavailable")));
        assertEquals(30000L, ReliableUploader.folderPullDelayMs(
                new java.io.IOException("disconnected")));
    }
}
