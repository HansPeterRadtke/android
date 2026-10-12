package com.hans.android.calendarreminder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ReminderReceiver extends BroadcastReceiver {
    private static final int TEST_NOTIFICATION_ID=13111;

    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null) return;
        if(ReminderScheduler.ACTION_TEST.equals(intent.getAction())) {
            showTestNotification(context);
            return;
        }
        String key=intent.getStringExtra(ReminderScheduler.EXTRA_KEY);
        if(key==null || key.isEmpty()) return;
        CalendarStore store=new CalendarStore(context);
        String action=intent.getAction();
        CalendarEvent event=null;
        // Never resurrect a cancelled or deleted appointment from history.
        // History is for viewing already-fired reminders, not scheduling new ones.
        if(ReminderScheduler.ACTION_ALARM.equals(action) ||
            ReminderScheduler.ACTION_SNOOZED.equals(action)) {
            for(CalendarEvent current:store.events()) {
                if(current.key.equals(key)) { event=current; break; }
            }
        } else {
            event=store.findEvent(key,0);
        }
        if(event==null) return;
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        int notificationId=event.key.hashCode() & 0x7fffffff;
        if(ReminderScheduler.ACTION_ACKNOWLEDGE.equals(action)) {
            store.acknowledge(event);
            manager.cancel(notificationId);
            return;
        }
        if(ReminderScheduler.ACTION_DISMISS.equals(action)) {
            manager.cancel(notificationId);
            return;
        }
        if(ReminderScheduler.ACTION_SNOOZE.equals(action)) {
            manager.cancel(notificationId);
            ReminderScheduler.scheduleAt(context,key,System.currentTimeMillis()+10*60_000L,true);
            return;
        }
        boolean snoozed=ReminderScheduler.ACTION_SNOOZED.equals(action);
        if(!snoozed && !ReminderScheduler.ACTION_ALARM.equals(action)) return;
        if(!notificationsAllowed(context)) {
            store.markError("Android blocked notifications. Enable notification permission.");
            return;
        }
        if(store.acknowledged(event)) {
            manager.cancel(notificationId);
            return;
        }
        if(!snoozed) {
            if(store.isDelivered(event)) return;
            long now=System.currentTimeMillis();
            if(event.endMs<now-3_600_000L) return;
            long expected=intent.getLongExtra(ReminderScheduler.EXTRA_AT,-1);
            long due=event.notifyAt(store.leadMinutes());
            if(expected<due && due>now+2000) return;
        }

        ReminderScheduler.ensureChannel(context);
        PendingIntent detailsIntent=eventDetailsIntent(context,event);
        Intent snooze=new Intent(context,ReminderReceiver.class)
            .setAction(ReminderScheduler.ACTION_SNOOZE)
            .setData(Uri.parse("raspi-calendar://snooze-action/"+Uri.encode(key)))
            .putExtra(ReminderScheduler.EXTRA_KEY,key);
        PendingIntent snoozePi=PendingIntent.getBroadcast(context,0,snooze,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent acknowledge=new Intent(context,ReminderReceiver.class)
            .setAction(ReminderScheduler.ACTION_ACKNOWLEDGE)
            .setData(Uri.parse("raspi-calendar://acknowledge-action/"+Uri.encode(key)))
            .putExtra(ReminderScheduler.EXTRA_KEY,key);
        PendingIntent acknowledgePi=PendingIntent.getBroadcast(context,0,acknowledge,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent dismiss=new Intent(context,ReminderReceiver.class)
            .setAction(ReminderScheduler.ACTION_DISMISS)
            .setData(Uri.parse("raspi-calendar://dismiss-action/"+Uri.encode(key)))
            .putExtra(ReminderScheduler.EXTRA_KEY,key);
        PendingIntent dismissPi=PendingIntent.getBroadcast(context,0,dismiss,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        String time=new SimpleDateFormat(event.allDay?"EEE, d MMM":"EEE, d MMM • HH:mm",
            Locale.getDefault()).format(new Date(event.startMs));
        String summary=time+(event.location.isEmpty()?"":" • "+event.location);
        StringBuilder details=new StringBuilder(time);
        if(event.endMs>event.startMs && !event.allDay)
            details.append(" – ").append(new SimpleDateFormat("HH:mm",Locale.getDefault())
                .format(new Date(event.endMs)));
        if(!event.location.isEmpty()) details.append("\nLocation: ").append(event.location);
        if(!event.description.isEmpty()) details.append("\n\n").append(event.description);
        if(!event.organizer.isEmpty()) details.append("\n\nOrganizer: ").append(event.organizer);
        if(!event.attendees.isEmpty()) {
            details.append("\nParticipants:");
            for(String guest:event.attendees) details.append("\n").append(guest);
        }
        String preview=details.length()>1800?details.substring(0,1800)+"…":details.toString();
        Notification.Builder notification=new Notification.Builder(context,ReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(event.title)
            .setContentText(summary)
            .setStyle(new Notification.BigTextStyle().bigText(preview))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setShowWhen(true)
            .setWhen(event.startMs)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(detailsIntent)
            .addAction(new Notification.Action.Builder(android.R.drawable.checkbox_on_background,
                "Acknowledge",acknowledgePi).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_popup_reminder,
                "Snooze 10 min",snoozePi).build())
            // Android notification surfaces expose at most three ordinary action buttons.
            // Tapping the notification body already opens the full event details.
            // Keep Acknowledge, Snooze and Dismiss available as direct actions.
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",dismissPi).build());
        try {
            manager.notify(notificationId,notification.build());
            if(!snoozed) store.markDelivered(event);
        } catch(SecurityException ignored) {
            store.markError("Android blocked notifications. Enable notification permission.");
        }
    }

    static PendingIntent eventDetailsIntent(Context context,CalendarEvent event) {
        Intent open=new Intent(context,MainActivity.class)
            .setAction("com.hans.android.calendarreminder.VIEW_EVENT")
            .setData(Uri.parse("raspi-calendar://event/"+Uri.encode(event.key)+"/"+event.startMs))
            .putExtra(MainActivity.EXTRA_SHOW_EVENT_KEY,event.key)
            .putExtra(MainActivity.EXTRA_SHOW_EVENT_START,event.startMs)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // Unique Intent data prevents two different event reminders from
        // overwriting each other's content intent.
        return PendingIntent.getActivity(context,0,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private static boolean notificationsAllowed(Context context) {
        return Build.VERSION.SDK_INT<33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
    }

    private static void showTestNotification(Context context) {
        if(!notificationsAllowed(context)) return;
        ReminderScheduler.ensureChannel(context);
        Intent open=new Intent(context,MainActivity.class)
            .setAction("com.hans.android.calendarreminder.VIEW_TEST")
            .setData(Uri.parse("raspi-calendar://test-notification"))
            .putExtra(MainActivity.EXTRA_SHOW_TEST_DETAILS,true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent details=PendingIntent.getActivity(context,TEST_NOTIFICATION_ID,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification example=new Notification.Builder(context,ReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Raspi Calendar notification test")
            .setContentText("Tap to read the test details.")
            .setStyle(new Notification.BigTextStyle().bigText(
                "This is a test notification, not a real appointment. Tap it to open the test details in Raspi Calendar. The notification remains in the notification shade until you dismiss it."))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(false)
            .setContentIntent(details)
            .build();
        ((NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(TEST_NOTIFICATION_ID,example);
    }
}
