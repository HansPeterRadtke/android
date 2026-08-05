package com.hans.android.voicebutton;

final class UploadWorkCoordinator {
    interface BackgroundOwner {
        void stopForServiceOwnership();
    }

    private static final Object LOCK = new Object();
    private static boolean serviceActive;
    private static BackgroundOwner backgroundOwner;

    private UploadWorkCoordinator() {}

    static void markServiceStarting() {
        BackgroundOwner owner;
        synchronized (LOCK) {
            serviceActive = true;
            owner = backgroundOwner;
        }
        if (owner != null) owner.stopForServiceOwnership();
    }

    static void awaitServiceOwnership() {
        while (true) {
            BackgroundOwner owner;
            synchronized (LOCK) {
                if (backgroundOwner == null) return;
                owner = backgroundOwner;
            }
            owner.stopForServiceOwnership();
            synchronized (LOCK) {
                if (backgroundOwner == null) return;
                try {
                    LOCK.wait(250L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while taking recording-store ownership",
                            interrupted);
                }
            }
        }
    }

    static void markServiceStopped() {
        synchronized (LOCK) {
            serviceActive = false;
            LOCK.notifyAll();
        }
    }

    static boolean beginBackground(BackgroundOwner owner) {
        synchronized (LOCK) {
            if (serviceActive || backgroundOwner != null) return false;
            backgroundOwner = owner;
            return true;
        }
    }

    static void endBackground(BackgroundOwner owner) {
        synchronized (LOCK) {
            if (backgroundOwner == owner) backgroundOwner = null;
            LOCK.notifyAll();
        }
    }

    static boolean isServiceActive() {
        synchronized (LOCK) { return serviceActive; }
    }
}
