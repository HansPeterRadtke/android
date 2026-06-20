package com.hans.android.taskreminder;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReminderScheduler.ensureChannel(context);
        ReminderScheduler.scheduleAll(context);
    }
}
