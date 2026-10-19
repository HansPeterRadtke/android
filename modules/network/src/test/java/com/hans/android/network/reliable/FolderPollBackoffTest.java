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

    @Test public void unauthenticatedPhoneNeverProbesPrivateFolderEndpoint() {
        assertFalse(ReliableUploader.shouldPullPrivateFolders(false, false,
                true, 600000L, 0L));
        assertTrue(ReliableUploader.shouldPullPrivateFolders(true, false,
                true, 600000L, 0L));
        assertFalse(ReliableUploader.shouldPullPrivateFolders(true, true,
                true, 600000L, 0L));
        assertFalse(ReliableUploader.shouldPullPrivateFolders(true, false,
                false, 600000L, 0L));
        assertFalse(ReliableUploader.shouldPullPrivateFolders(true, false,
                true, 600000L, 600001L));
        assertTrue(ReliableUploader.shouldPullPrivateFolders(true, false,
                true, 600000L, 600000L));
    }

    @Test public void badCredentialsAreNotRetriedIndefinitely() {
        assertTrue(ReliableUploader.folderAccessDenied(
                new ReliableUploadClient.ProtocolException(401, "access required")));
        assertTrue(ReliableUploader.folderAccessDenied(
                new ReliableUploadClient.ProtocolException(403, "forbidden")));
        assertFalse(ReliableUploader.folderAccessDenied(
                new ReliableUploadClient.ProtocolException(503, "unavailable")));
        assertFalse(ReliableUploader.folderAccessDenied(
                new java.io.IOException("network unavailable")));
    }

    @Test public void privateFolderWritesCannotSpamWhenAuthorizationFails() {
        assertTrue(ReliableUploader.mayAttemptFolderWrite(false, 1000L, 0L));
        assertFalse(ReliableUploader.mayAttemptFolderWrite(true, 9999999L, 0L));
        assertFalse(ReliableUploader.mayAttemptFolderWrite(false, 29999L, 30000L));
        assertTrue(ReliableUploader.mayAttemptFolderWrite(false, 30000L, 30000L));
        assertFalse(ReliableUploader.mayAttemptFolderWrite(false, 1L, 30000L));
    }

    @Test public void transientErrorsAlsoHaveBackoff() {
        assertEquals(30000L, ReliableUploader.folderPullDelayMs(
                new ReliableUploadClient.ProtocolException(503, "server unavailable")));
        assertEquals(30000L, ReliableUploader.folderPullDelayMs(
                new java.io.IOException("disconnected")));
    }
}
