package com.hans.android.taskreminder;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.hans.android.reminder_core.*;
import java.util.*;

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
            task.completedCount += 1;
            int beforeStack = task.pendingStackCount;
            if (task.pendingStackCount > 0) task.pendingStackCount -= 1;
            store.appendHistory(id, "completed", "Completed occurrence due " + new Date(task.openOccurrenceDueAt) + " after " + task.openSnoozeCount + " snooze(s)" + (beforeStack > 0 ? " · stack remaining " + task.pendingStackCount : ""));
            closeOccurrence(task);
            store.upsert(task);
            if (!ReminderTask.REPEAT_ONCE.equals(task.repeatMode) && task.enabled) ReminderScheduler.scheduleTask(context, task, true);
            return;
        }
        if (ReminderScheduler.ACTION_DISMISS.equals(action)) {
            NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
            ReminderScheduler.cancelTask(context, id);
            task.dismissedCount += 1;
            store.appendHistory(id, "dismissed", "Dismissed only, not carried forward · due " + new Date(task.openOccurrenceDueAt) + " after " + task.openSnoozeCount + " snooze(s)");
            closeOccurrence(task);
            store.upsert(task);
            if (!ReminderTask.REPEAT_ONCE.equals(task.repeatMode) && task.enabled) ReminderScheduler.scheduleTask(context, task, true);
            return;
        }
        if (ReminderScheduler.ACTION_CARRY_OVER.equals(action)) {
            carryOver(context, store, task, id, "Dismissed and stacked");
            return;
        }
        if (ReminderScheduler.ACTION_SKIP.equals(action)) {
            NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
            ReminderScheduler.cancelTask(context, id);
            task.dismissedCount += 1;
            store.appendHistory(id, "skipped", "Skipped this occurrence due " + new Date(task.openOccurrenceDueAt) + " after " + task.openSnoozeCount + " snooze(s). This means intentionally not doing it this time.");
            closeOccurrence(task);
            store.upsert(task);
            if (!ReminderTask.REPEAT_ONCE.equals(task.repeatMode) && task.enabled) ReminderScheduler.scheduleTask(context, task, true);
            return;
        }
        if (ReminderScheduler.ACTION_NOT_DONE.equals(action)) {
            NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
            ReminderScheduler.cancelTask(context, id);
            task.missedCount += 1;
            store.appendHistory(id, "not_done", "Marked not done manually for occurrence due " + new Date(task.openOccurrenceDueAt) + " after " + task.openSnoozeCount + " snooze(s)");
            closeOccurrence(task);
            store.upsert(task);
            if (!ReminderTask.REPEAT_ONCE.equals(task.repeatMode) && task.enabled) ReminderScheduler.scheduleTask(context, task, true);
            return;
        }
        if (ReminderScheduler.ACTION_SNOOZE.equals(action)) {
            NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
            int minutes = intent.getIntExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, task.defaultSnoozeMinutes);
            ReminderScheduler.scheduleSnooze(context, task, minutes);
            return;
        }
        if (ReminderScheduler.ACTION_DUE.equals(action)) {
            long now = System.currentTimeMillis();
            if (task.openOccurrenceDueAt > 0 && task.lastDueAt > task.openOccurrenceDueAt) {
                task.missedCount += 1;
                store.appendHistory(id, "auto_not_completed", "Previous occurrence due " + new Date(task.openOccurrenceDueAt) + " was still open when next reminder fired · snoozes " + task.openSnoozeCount);
                closeOccurrence(task);
            }
            if (intent.getIntExtra(ReminderScheduler.EXTRA_SNOOZE_MINUTES, 0) == 0) {
                task.lastDueAt = now;
                if (task.openOccurrenceDueAt > 0) {
                    task.missedCount += 1;
                    store.appendHistory(id, "auto_not_completed", "Previous occurrence due " + new Date(task.openOccurrenceDueAt) + " was replaced by new occurrence · snoozes " + task.openSnoozeCount);
                }
                task.openOccurrenceDueAt = now;
                task.openSnoozeCount = 0;
                store.upsert(task);
                store.appendHistory(id, "notified", "New occurrence due now · " + task.repeatSummary());
                if (!ReminderTask.REPEAT_ONCE.equals(task.repeatMode) && task.enabled) ReminderScheduler.scheduleTask(context, task, true);
            }
            showNotification(context, task);
        }
    }


    private void carryOver(Context context, ReminderStore store, ReminderTask task, long id, String reason) {
        NotificationManagerCompat.from(context).cancel((int)(id % 1000000000L));
        ReminderScheduler.cancelTask(context, id);
        task.pendingStackCount += 1;
        task.dismissedCount += 1;
        store.appendHistory(id, "carried_forward", reason + " · carried to next occurrence · pending stack " + task.pendingStackCount + " · due " + new Date(task.openOccurrenceDueAt));
        closeOccurrence(task);
        store.upsert(task);
        if (!ReminderTask.REPEAT_ONCE.equals(task.repeatMode) && task.enabled) ReminderScheduler.scheduleTask(context, task, true);
    }

    private void closeOccurrence(ReminderTask task) {
        task.openOccurrenceDueAt = 0;
        task.openSnoozeCount = 0;
        if (ReminderTask.REPEAT_ONCE.equals(task.repeatMode)) task.enabled = false;
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
        Intent dismiss = new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_DISMISS);
        dismiss.putExtra(ReminderScheduler.EXTRA_TASK_ID, task.id);
        PendingIntent dismissPi = PendingIntent.getBroadcast(context, (int)(task.id % 1000000000L) + 30, dismiss, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent skip = new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_SKIP);
        skip.putExtra(ReminderScheduler.EXTRA_TASK_ID, task.id);
        PendingIntent skipPi = PendingIntent.getBroadcast(context, (int)(task.id % 1000000000L) + 40, skip, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent notDone = new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_NOT_DONE);
        notDone.putExtra(ReminderScheduler.EXTRA_TASK_ID, task.id);
        PendingIntent notDonePi = PendingIntent.getBroadcast(context, (int)(task.id % 1000000000L) + 50, notDone, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent carry = new Intent(context, ReminderReceiver.class).setAction(ReminderScheduler.ACTION_CARRY_OVER);
        carry.putExtra(ReminderScheduler.EXTRA_TASK_ID, task.id);
        PendingIntent carryPi = PendingIntent.getBroadcast(context, (int)(task.id % 1000000000L) + 60, carry, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String detail = task.notes == null ? "" : task.notes.trim();
        String content = detail.isEmpty() ? (task.pendingStackCount > 0 ? "Due now · carried " + task.pendingStackCount : "Due now") : detail;
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(task.title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.checkbox_on_background, "Complete", completePi)
            .addAction(android.R.drawable.ic_popup_reminder, "Snooze", snoozePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPi)
            .addAction(android.R.drawable.ic_menu_upload, "Skip", skipPi)
            .addAction(android.R.drawable.ic_menu_delete, "Not done", notDonePi);
        if (task.showCarryOverDismissAction) b.addAction(android.R.drawable.ic_menu_revert, "Carry to next", carryPi);
        if (!detail.isEmpty()) b.setStyle(new NotificationCompat.BigTextStyle().bigText(detail));
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify((int)(task.id % 1000000000L), b.build());
        }
    }
}
