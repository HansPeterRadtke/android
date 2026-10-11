package com.hans.android.calendarreminder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import java.util.List;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(intent==null) return;
        if(ReminderScheduler.ACTION_TEST.equals(intent.getAction())) {
            if(Build.VERSION.SDK_INT>=33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                return;
            ReminderScheduler.ensureChannel(context);
            NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification message=new Notification.Builder(context,ReminderScheduler.CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Raspi Calendar test")
                .setContentText("Your phone can display appointment reminders.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true).build();
            manager.notify(13111,message);
            return;
        }
        String key=intent.getStringExtra(ReminderScheduler.EXTRA_KEY);
        if(key==null || key.isEmpty()) return;
        CalendarStore store=new CalendarStore(context);
        CalendarEvent event=null;
        for(CalendarEvent item:store.events()) if(item.key.equals(key)) { event=item; break; }
        if(event==null) return; // An event was removed or rescheduled after alarm creation.
        String action=intent.getAction();
        NotificationManager notifications=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        int notificationId=event.key.hashCode() & 0x7fffffff;
        if(ReminderScheduler.ACTION_SNOOZE.equals(action)) {
            notifications.cancel(notificationId);
            ReminderScheduler.scheduleAt(context,key,System.currentTimeMillis()+10*60_000L,true);
            return;
        }
        boolean snoozed=ReminderScheduler.ACTION_SNOOZED.equals(action);
        if(!snoozed && !ReminderScheduler.ACTION_ALARM.equals(action)) return;
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return;
        if(!snoozed) {
            if(store.isDelivered(event)) return;
            long now=System.currentTimeMillis();
            if(event.endMs<now-3_600_000L) return;
            // Ignore a stale pending broadcast rescheduled for a different instant.
            long expected=intent.getLongExtra(ReminderScheduler.EXTRA_AT,-1);
            long due=event.notifyAt(store.leadMinutes());
            if(expected < due && due>now+2000) return;
        }
        ReminderScheduler.ensureChannel(context);
        Intent open=new Intent(context,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent snooze=new Intent(context,ReminderReceiver.class)
            .setAction(ReminderScheduler.ACTION_SNOOZE)
            .setData(Uri.parse("raspi-calendar://snooze-action/"+Uri.encode(key)))
            .putExtra(ReminderScheduler.EXTRA_KEY,key);
        PendingIntent snoozePi=PendingIntent.getBroadcast(context,0,snooze,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String details=event.location.isEmpty()?event.description:event.location+(event.description.isEmpty()?"":"\n"+event.description);
        if(details.length()>750) details=details.substring(0,750);
        Notification.Builder b=new Notification.Builder(context,ReminderScheduler.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(event.title)
            .setContentText(event.allDay?"All-day event":(event.location.isEmpty()?"Appointment reminder":event.location))
            .setStyle(new Notification.BigTextStyle().bigText(details.isEmpty()?"Upcoming appointment":details))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(content)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_popup_reminder,"Snooze 10 min",snoozePi).build());
        try {
            notifications.notify(notificationId,b.build());
            if(!snoozed) store.markDelivered(event);
        } catch (SecurityException ignored) {
            store.markError("Android blocked notifications. Enable notification permission.");
        }
    }
}
