package com.hans.android.calendarreminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReminderScheduler.ensureChannel(context);
        ReminderScheduler.scheduleAll(context);
        SyncJobService.ensurePeriodic(context);
        // JobScheduler refreshes silently; no foreground-service notification.
        LiveSyncService.stop(context);
    }
}
