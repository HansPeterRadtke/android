package com.hans.android.taskreminder;

import android.app.*;
import android.content.*;
import android.os.Build;
import com.hans.android.reminder_core.*;
import java.util.*;

public final class ReminderScheduler {
    public static final String ACTION_DUE = "com.hans.android.taskreminder.DUE";
    public static final String ACTION_COMPLETE = "com.hans.android.taskreminder.COMPLETE";
    public static final String ACTION_SNOOZE = "com.hans.android.taskreminder.SNOOZE";
    public static final String ACTION_DISMISS = "com.hans.android.taskreminder.DISMISS";
    public static final String ACTION_SKIP = "com.hans.android.taskreminder.SKIP";
    public static final String ACTION_NOT_DONE = "com.hans.android.taskreminder.NOT_DONE";
    public static final String ACTION_CARRY_OVER = "com.hans.android.taskreminder.CARRY_OVER";
    public static final String ACTION_MISSED = "com.hans.android.taskreminder.MISSED";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_SNOOZE_MINUTES = "snooze_minutes";
    public static final String CHANNEL_ID = "task_reminders";

    private ReminderScheduler() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Task reminder notifications with complete, snooze, dismiss, skip and not-done actions");
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    public static void scheduleAll(Context context) {
        ReminderStore store = new ReminderStore(context);
        for (ReminderTask t : store.loadTasks()) if (t.enabled) scheduleTask(context, t, false);
    }

    public static void scheduleTask(Context context, ReminderTask task, boolean log) {
        if (!task.enabled) return;
        Calendar due = RepeatCalculator.nextDue(task, System.currentTimeMillis());
        task.lastScheduledAt = due.getTimeInMillis();
        new ReminderStore(context).upsert(task);
        scheduleAt(context, ACTION_DUE, task.id, due.getTimeInMillis(), 0, 1000);
        if (log) new ReminderStore(context).appendHistory(task.id, "scheduled", task.repeatSummary() + " · next " + new Date(due.getTimeInMillis()));
    }

    public static void scheduleSnooze(Context context, ReminderTask task, int minutes) {
        int m = Math.max(1, minutes);
        long when = System.currentTimeMillis() + m * 60_000L;
        task.openSnoozeCount += 1;
        new ReminderStore(context).upsert(task);
        scheduleAt(context, ACTION_DUE, task.id, when, m, 3000);
        new ReminderStore(context).appendHistory(task.id, "snoozed", "Snoozed occurrence due " + new Date(task.openOccurrenceDueAt) + " for " + m + " minutes · count " + task.openSnoozeCount);
    }

    private static void scheduleAt(Context context, String action, long taskId, long when, int snoozeMinutes, int offset) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(action);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        intent.putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes);
        PendingIntent pi = PendingIntent.getBroadcast(context, (int)(taskId % 1000000000L) + offset, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.set(AlarmManager.RTC_WAKEUP, when, pi);
    }

    public static void cancelTask(Context context, long taskId) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int offset : new int[]{1000,2000,3000,4000,5000,6000}) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(context, (int)(taskId % 1000000000L) + offset, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        }
    }
}
