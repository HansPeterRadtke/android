package com.hans.android.voicebutton;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

final class UploadWorkScheduler {
    private static final String IMMEDIATE_WORK =
            "voicebutton-recording-upload-immediate";
    private static final String PERIODIC_WORK =
            "voicebutton-recording-upload-safety-net";
    private static final String TAG = "voicebutton-recording-upload";

    private UploadWorkScheduler() {}

    static void initialize(Context context) {
        Context app = context.getApplicationContext();
        Constraints connected = connectedConstraint();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                RecordingUploadWorker.class, 15L, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        30L, TimeUnit.SECONDS)
                .addTag(TAG)
                .build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic);
    }

    static void enqueue(Context context, String reason) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                RecordingUploadWorker.class)
                .setConstraints(connectedConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        30L, TimeUnit.SECONDS)
                .addTag(TAG)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(IMMEDIATE_WORK,
                        ExistingWorkPolicy.KEEP, request);
    }

    private static Constraints connectedConstraint() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }
}
