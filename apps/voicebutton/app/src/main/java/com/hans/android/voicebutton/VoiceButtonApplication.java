package com.hans.android.voicebutton;

import android.app.Application;
import android.os.StrictMode;

public final class VoiceButtonApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .detectCustomSlowCalls().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().detectActivityLeaks()
                    .penaltyLog().build());
            MainThreadWatchdog.start();
        }
        PhoneDiagnostics.initializeAsync(this, BuildConfig.VOICE_BASE_URL,
                BuildConfig.VERSION_NAME);
    }
}
