package com.hans.android.voicebutton;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.network.reliable.ReliableUploader;

import java.util.concurrent.TimeUnit;

public final class RecordingUploadWorker extends Worker
        implements UploadWorkCoordinator.BackgroundOwner {
    private static final String CHANNEL_ID =
            "voicebutton_background_recording_sync";
    private static final int NOTIFICATION_ID = 42013;
    private volatile ReliableUploader uploader;
    private volatile boolean preemptedByService;

    public RecordingUploadWorker(@NonNull Context context,
                                 @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        if(!AppSettings.uploadPermitted(getApplicationContext()))return Result.success();
        if (!UploadWorkCoordinator.beginBackground(this)) return Result.success();
        try {
            // Running full recoverAll() in the constructor is not preemptible.
            // The foreground recorder can otherwise wait many seconds for
            // WorkManager to finish walking old library metadata.
            ReliableSessionStore store = ReliableSessionStore.openForBrowsing(
                    getApplicationContext());
            if (preemptedByService || UploadWorkCoordinator.isServiceActive()
                    || isStopped()) return Result.success();
            boolean recovered = store.recoverAll(() ->
                    preemptedByService || UploadWorkCoordinator.isServiceActive()
                    || isStopped() || Thread.currentThread().isInterrupted());
            if (!recovered) return Result.success();
            ReliableUploader value = new ReliableUploader(
                    getApplicationContext(), store, BuildConfig.VOICE_BASE_URL,
                    new QuietListener(), true, false);
            uploader = value;
            if (preemptedByService || UploadWorkCoordinator.isServiceActive()) {
                return Result.success();
            }
            if (!value.hasPendingTransferWork()) return Result.success();
            setForegroundAsync(foregroundInfo()).get(30L, TimeUnit.SECONDS);
            value.start();
            value.signal();
            while (!isStopped() && AppSettings.uploadPermitted(getApplicationContext())) {
                if (!value.hasPendingTransferWork()) {
                    value.stop();
                    value.awaitStopped(5000L);
                    return Result.success();
                }
                if (!value.hasActionableTransferWork()
                        || value.isPermanentlyPaused()) {
                    value.stop();
                    value.awaitStopped(5000L);
                    // A quarantined/blocked file cannot be fixed by immediate
                    // repeat requests. Let a later explicit user action or the
                    // periodic safety net re-evaluate the durable state.
                    return Result.success();
                }
                if (!value.isWorkerAlive()) {
                    return Result.retry();
                }
                Thread.sleep(1000L);
            }
            return retryAfterWorkerStop(
                    AppSettings.uploadPermitted(getApplicationContext()),
                    preemptedByService, isStopped()) ? Result.retry()
                    : Result.success();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return retryAfterWorkerStop(
                    AppSettings.uploadPermitted(getApplicationContext()),
                    preemptedByService, true) ? Result.retry()
                    : Result.success();
        } catch (Exception failure) {
            return AppSettings.uploadPermitted(getApplicationContext())
                    && !preemptedByService ? Result.retry() : Result.success();
        } finally {
            ReliableUploader value = uploader;
            uploader = null;
            if (value != null) {
                value.stop();
                value.awaitStopped(5000L);
            }
            UploadWorkCoordinator.endBackground(this);
        }
    }

    static boolean retryAfterWorkerStop(boolean uploadPermitted,
                                        boolean ownershipPreempted,
                                        boolean workManagerStopped) {
        // Explicit Exit and ownership handoff are deliberate cancellations,
        // not faults requiring rescheduling behind the user's back.
        return uploadPermitted && !ownershipPreempted && !workManagerStopped;
    }

    @Override public void stopForServiceOwnership() {
        preemptedByService = true;
        ReliableUploader value = uploader;
        if (value != null) value.stop();
    }

    @Override public void onStopped() {
        ReliableUploader value = uploader;
        if (value != null) value.stop();
        super.onStopped();
    }

    private ForegroundInfo foregroundInfo() {
        Context context = getApplicationContext();
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Recording synchronization",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(
                    "Durably uploads queued recordings over unreliable networks");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_voice_button)
                .setContentTitle("Synchronizing recordings")
                .setContentText("Queued audio resumes from Jetson's last durable byte")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        "Queued audio resumes from Jetson's last durable byte and remains safe across connection changes, app restarts, and device reboots."))
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        if (Build.VERSION.SDK_INT >= 29) {
            return new ForegroundInfo(NOTIFICATION_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, builder.build());
    }

    private static final class QuietListener implements ReliableUploader.Listener {
        @Override public void onState(String sessionId, String humanState) {}
        @Override public void onChanged() {}
        @Override public void onDiagnostic(String level, String event,
                                           String sessionId, String message,
                                           org.json.JSONObject fields,
                                           Throwable failure) {}
    }
}
