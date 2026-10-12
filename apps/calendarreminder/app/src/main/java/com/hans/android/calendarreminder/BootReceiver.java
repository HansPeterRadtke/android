package com.hans.android.calendarreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReminderScheduler.ensureChannel(context);
        ReminderScheduler.scheduleAll(context);
        SyncJobService.ensurePeriodic(context);
        try {
            LiveSyncService.ensureRunning(context);
        } catch(RuntimeException ex) {
            // Background-start restrictions vary; the persisted network job
            // is the recovery path until the user opens the application.
            SyncJobService.requestSync(context);
        }
    }
}
