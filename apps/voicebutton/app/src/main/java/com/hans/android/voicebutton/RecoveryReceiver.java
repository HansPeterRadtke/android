package com.hans.android.voicebutton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;


public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Context app = context.getApplicationContext();
        PhoneDiagnostics.initializeAsync(app, BuildConfig.VOICE_BASE_URL,
                BuildConfig.VERSION_NAME);
        UploadWorkScheduler.initialize(app);
        UploadWorkScheduler.enqueue(app, "boot_or_package_replaced");
        Intent service = new Intent(app, RecordingService.class)
                .setAction(RecordingService.ACTION_RECOVER_AFTER_BOOT);
        ContextCompat.startForegroundService(app, service);
    }
}
