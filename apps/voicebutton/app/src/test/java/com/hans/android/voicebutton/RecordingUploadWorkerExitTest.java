package com.hans.android.voicebutton;

import static org.junit.Assert.*;
import org.junit.Test;

public class RecordingUploadWorkerExitTest {
    @Test public void exitAndOwnershipHandoffNeverRetryInBackground() {
        assertFalse(RecordingUploadWorker.retryAfterWorkerStop(false,false,false));
        assertFalse(RecordingUploadWorker.retryAfterWorkerStop(false,false,true));
        assertFalse(RecordingUploadWorker.retryAfterWorkerStop(true,true,false));
        assertFalse(RecordingUploadWorker.retryAfterWorkerStop(true,false,true));
    }

    @Test public void genuineActiveTransferMayRetryAfterTransientFailure() {
        assertTrue(RecordingUploadWorker.retryAfterWorkerStop(true,false,false));
    }
}
