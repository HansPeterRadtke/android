package com.hans.android.voicebutton;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public class UploadWorkCoordinatorTest {
    @After public void restoreStoppedServiceState() {
        UploadWorkCoordinator.markServiceStopped();
    }

    @Test public void servicePreemptsBackgroundBeforeTakingStoreOwnership()
            throws Exception {
        FakeOwner owner = new FakeOwner();
        assertTrue(UploadWorkCoordinator.beginBackground(owner));
        CountDownLatch serviceOwnsStore = new CountDownLatch(1);
        Thread service = new Thread(() -> {
            UploadWorkCoordinator.markServiceStarting();
            UploadWorkCoordinator.awaitServiceOwnership();
            serviceOwnsStore.countDown();
        });
        service.start();
        assertTrue(owner.stopRequested.await(2L, TimeUnit.SECONDS));
        assertFalse(serviceOwnsStore.await(100L, TimeUnit.MILLISECONDS));
        UploadWorkCoordinator.endBackground(owner);
        assertTrue(serviceOwnsStore.await(2L, TimeUnit.SECONDS));
        assertFalse(UploadWorkCoordinator.beginBackground(new FakeOwner()));
        service.join(2000L);
    }

    private static final class FakeOwner
            implements UploadWorkCoordinator.BackgroundOwner {
        final CountDownLatch stopRequested = new CountDownLatch(1);
        @Override public void stopForServiceOwnership() {
            stopRequested.countDown();
        }
    }
}
