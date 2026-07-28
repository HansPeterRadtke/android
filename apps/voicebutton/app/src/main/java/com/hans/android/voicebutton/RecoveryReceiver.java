package com.hans.android.voicebutton;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;

public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PhoneDiagnostics diagnostics = PhoneDiagnostics.initialize(context,
                BuildConfig.VOICE_BASE_URL, BuildConfig.VERSION_NAME);
        String action = intent == null ? "" : intent.getAction();
        if (diagnostics != null) diagnostics.log(PhoneDiagnostics.INFO,
                "recovery_receiver.action", null,
                "RecoveryReceiver received an Android broadcast",
                PhoneDiagnostics.fields("action", action));
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        try {
            ReliableSessionStore store = new ReliableSessionStore(context);
            boolean work = false;
            int pending = 0;
            for (ReliableSessionManifest manifest : store.list()) {
                if (!manifest.isDone()) {
                    work = true;
                    pending++;
                }
            }
            if (diagnostics != null) diagnostics.log(PhoneDiagnostics.INFO,
                    "recovery_receiver.scan", null,
                    "RecoveryReceiver scanned private recording metadata",
                    PhoneDiagnostics.fields("pending_session_count", pending,
                            "local_bytes", store.localBytes()));
            if (!work) return;
            Intent service = new Intent(context, RecordingService.class)
                    .setAction(RecordingService.ACTION_RETRY);
            ContextCompat.startForegroundService(context, service);
            if (diagnostics != null) diagnostics.log(PhoneDiagnostics.INFO,
                    "recovery_receiver.service_started", null,
                    "RecoveryReceiver started the foreground reconciliation service",
                    PhoneDiagnostics.fields("pending_session_count", pending));
        } catch (Exception failure) {
            if (diagnostics != null) diagnostics.error("recovery_receiver.failed", null,
                    "Scanning or restarting recording reconciliation", failure,
                    PhoneDiagnostics.fields("action", action));
            // The next normal app launch performs the same deterministic recovery scan.
        }
    }
}
