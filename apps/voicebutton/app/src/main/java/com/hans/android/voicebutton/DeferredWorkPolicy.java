package com.hans.android.voicebutton;

import java.util.concurrent.ExecutorService;

final class DeferredWorkPolicy {
    private DeferredWorkPolicy() {}

    static boolean maySubmit(ExecutorService executor) {
        return executor != null && !executor.isShutdown() && !executor.isTerminated();
    }
}
