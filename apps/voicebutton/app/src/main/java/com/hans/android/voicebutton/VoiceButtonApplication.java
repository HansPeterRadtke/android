package com.hans.android.voicebutton;

import android.app.Application;

public final class VoiceButtonApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PhoneDiagnostics.initialize(this, BuildConfig.VOICE_BASE_URL, BuildConfig.VERSION_NAME);
    }
}
