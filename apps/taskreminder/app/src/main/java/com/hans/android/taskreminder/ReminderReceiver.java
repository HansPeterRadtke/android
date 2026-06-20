package com.hans.android.taskreminder;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.hans.android.reminder_core.*;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        ReminderScheduler.ensureChannel(context);
        ReminderStore store = new ReminderStore(context);
        long id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1);
        ReminderTask task = store.findTask(id);
        if (task == null) return;
        String action = intent.getAction();
        if (ReminderScheduler.ACTION_COMPLETE.equals(action)) {
            NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
            ReminderScheduler.cancelTask(context, id);
            store.appendHistory(id, "completed", task.title);
            if (task.daily) ReminderScheduler.scheduleTask(context, task, true);
            return;
        }
        if (ReminderScheduler.ACTION_SNOOZE.equals(action)) {
            NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
            int minutes = intent.getIntExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, task.defaultSnoozeMinutes);
            ReminderScheduler.scheduleSnooze(context, task, minutes);
            return;
        }
        if (ReminderScheduler.ACTION_MISSED.equals(action)) {
            store.appendHistory(id, "missed", "No completion recorded before day boundary for " + task.title);
            if (task.daily) ReminderScheduler.scheduleTask(context, task, true);
            return;
        }
        if (ReminderScheduler.ACTION_DUE.equals(action)) {
            store.appendHistory(id, "notified", task.title);
            showNotification(context, task);
        }
    }

    private void showNotification(Context context, ReminderTask task) {
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(context, (int)(task.id % 1000000000L), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent complete = new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_COMPLETE);
        complete.putExtra(ReminderScheduler.EXTRA_TASK_ID, task.id);
        PendingIntent completePi = PendingIntent.getBroadcast(context, (int)(task.id % 1000000000L) + 10, complete, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent snooze = new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_SNOOZE);
        snooze.putExtra(ReminderScheduler.EXTRA_TASK_ID, task.id);
        snooze.putExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, task.defaultSnoozeMinutes);
        PendingIntent snoozePi = PendingIntent.getBroadcast(context, (int)(task.id % 1000000000L) + 20, snooze, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(task.title)
            .setContentText("Due now. Snooze: " + task.defaultSnoozeMinutes + " min")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(task.notes == null || task.notes.isEmpty() ? "Due now." : task.notes))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.checkbox_on_background, "Complete", completePi)
            .addAction(android.R.drawable.ic_popup_reminder, "Snooze", snoozePi);
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify((int)(task.id % 1000000000L), b.build());
        }
    }
}
