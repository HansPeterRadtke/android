package com.hans.android.calendarreminder;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import java.util.*;

public final class ReminderScheduler {
    public static final String CHANNEL="raspi_calendar_reminders";
    public static final String ACTION_ALARM="com.hans.android.calendarreminder.ALARM";
    public static final String ACTION_SNOOZE="com.hans.android.calendarreminder.SNOOZE";
    public static final String ACTION_ACKNOWLEDGE="com.hans.android.calendarreminder.ACKNOWLEDGE";
    public static final String ACTION_DISMISS="com.hans.android.calendarreminder.DISMISS";
    public static final String ACTION_SNOOZED="com.hans.android.calendarreminder.SNOOZED";
    public static final String ACTION_TEST="com.hans.android.calendarreminder.TEST";
    public static final String EXTRA_KEY="key";
    public static final String EXTRA_AT="notification_time";
    private static final int MAX_SCHEDULED=500;
    private ReminderScheduler() {}

    static void ensureChannel(Context c) {
        NotificationManager manager=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Calendar reminders",NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Appointments from your Raspi calendar");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }
    }

    static boolean exactAvailable(Context c) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT<31 || am.canScheduleExactAlarms();
    }

    static PendingIntent alarmIntent(Context c,String key,long intendedTime,boolean snoozed) {
        Intent i=new Intent(c,ReminderReceiver.class)
            .setAction(snoozed?ACTION_SNOOZED:ACTION_ALARM)
            .setData(Uri.parse("raspi-calendar://"+(snoozed?"snoozed/":"due/")+Uri.encode(key)))
            .putExtra(EXTRA_KEY,key).putExtra(EXTRA_AT,intendedTime);
        return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancel(Context c,CalendarEvent event) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent old=alarmIntent(c,event.key,event.notifyAt(10),false);
        am.cancel(old);old.cancel();
        PendingIntent snoozed=alarmIntent(c,event.key,0,true);
        am.cancel(snoozed);snoozed.cancel();
    }
    private static void cancelDue(Context c,CalendarEvent event) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent alarm=alarmIntent(c,event.key,event.notifyAt(10),false);
        am.cancel(alarm);alarm.cancel();
    }

    static void scheduleAt(Context c,String key,long when,boolean snoozed) {
        AlarmManager am=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi=alarmIntent(c,key,when,snoozed);
        if(exactAvailable(c)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
                return;
            } catch (SecurityException ignored) {
                // Explicitly fall back to an approximate alarm on restricted devices.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);
    }

    static void scheduleTest(Context context) {
        long when=System.currentTimeMillis()+12_000L;
        Intent i=new Intent(context,ReminderReceiver.class)
            .setAction(ACTION_TEST)
            .setData(Uri.parse("raspi-calendar://test-notification"));
        PendingIntent pending=PendingIntent.getBroadcast(context,0,i,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm=(AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        if(exactAvailable(context)) {
            try {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pending);
                return;
            } catch(SecurityException ignored) {}
        }
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pending);
    }

    static synchronized int reschedule(Context c,List<CalendarEvent> previous,List<CalendarEvent> next) {
        HashSet<String> currentKeys=new HashSet<>();
        for(CalendarEvent event:next) currentKeys.add(event.key);
        for(CalendarEvent old:previous) {
            if(currentKeys.contains(old.key)) cancelDue(c,old);
            else cancel(c,old);
        }
        CalendarStore store=new CalendarStore(c);
        long now=System.currentTimeMillis();
        int count=0;
        ArrayList<CalendarEvent> sorted=new ArrayList<>(next);
        sorted.sort(Comparator.comparingLong(e->e.notifyAt(store.leadMinutes())));
        for(CalendarEvent event:sorted) {
            if(count>=MAX_SCHEDULED) break;
            if(!event.isUpcoming(now) || store.isDelivered(event)) continue;
            long at=event.notifyAt(store.leadMinutes());
            if(at<=now) {
                // Notify promptly if a newly synced appointment starts soon.
                if(event.startMs<now || event.startMs-now>30*60_000L) continue;
                at=now+1500;
            }
            scheduleAt(c,event.key,at,false);
            count++;
        }
        return count;
    }
    static int scheduleAll(Context c) {
        CalendarStore store=new CalendarStore(c);
        List<CalendarEvent> cached=store.events();
        return reschedule(c,cached,cached);
    }
}
