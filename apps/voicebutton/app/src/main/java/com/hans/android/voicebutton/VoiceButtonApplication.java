package com.hans.android.voicebutton;

import android.app.Application;
import android.os.StrictMode;

public final class VoiceButtonApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        com.hans.android.audio.reliable.RuntimePolicy.load(this);
        CrashRecorder.install(this);
        // WorkManager or Android package events can recreate our process after
        // an explicit Exit. Do not restart periodic debug polling or telemetry
        // merely because Android briefly launched the application process.
        boolean activelyOpened = !AppSettings.pausedAfterExit(this);
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .detectCustomSlowCalls().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().detectActivityLeaks()
                    .penaltyLog().build());
            if (activelyOpened) MainThreadWatchdog.start();
        }
        if (activelyOpened) {
            PhoneDiagnostics.initializeAsync(this, BuildConfig.VOICE_BASE_URL,
                    BuildConfig.VERSION_NAME);
        }
        // WorkManager database initialization and periodic-job reconciliation
        // must not delay the first Activity frame or microphone readiness.
        // The already-persisted scheduler jobs survive process restarts.
        Thread scheduler = new Thread(() -> {
            try {
                UploadWorkScheduler.initialize(getApplicationContext());
            } catch (RuntimeException schedulingFailure) {
                android.util.Log.e("VoiceButton", "Background upload scheduling failed",
                        schedulingFailure);
            }
        }, "voicebutton-work-scheduler-init");
        scheduler.setDaemon(true);
        scheduler.start();
    }
}
