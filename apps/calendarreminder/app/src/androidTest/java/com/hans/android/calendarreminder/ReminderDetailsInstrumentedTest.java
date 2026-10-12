package com.hans.android.calendarreminder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReminderDetailsInstrumentedTest {
    private Context target;
    private CalendarStore store;
    private JSONObject past;
    private long pastStart;

    @Before public void setup() throws Exception {
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        target.getSharedPreferences(CalendarStore.PREFS,Context.MODE_PRIVATE).edit().clear().commit();
        new File(target.getFilesDir(),"raspi_calendar_cache.json").delete();
        new File(target.getFilesDir(),"raspi_notified_history.json").delete();
        target.getSystemService(NotificationManager.class).cancelAll();
        store=new CalendarStore(target);
        long now=System.currentTimeMillis();
        pastStart=now-20*60_000L;
        past=makeEvent("regression-past-event",pastStart,now-10*60_000L);
        store.replaceSnapshot(snapshot(new JSONArray().put(past)));
    }

    private static JSONObject makeEvent(String key,long start,long end) throws Exception {
        return new JSONObject()
            .put("key",key)
            .put("title","Past appointment remains accessible")
            .put("description","Full description retained after the event ended.")
            .put("location","Conference Room 42")
            .put("organizer","Meeting organizer")
            .put("meetingUrl","https://meet.example.test/demo")
            .put("url","https://calendar.google.com/")
            .put("attendees",new JSONArray().put(new JSONObject()
                .put("name","Invited participant")
                .put("email","guest@example.test")
                .put("status","accepted")))
            .put("startEpochMs",start)
            .put("endEpochMs",end)
            .put("allDayAlertEpochMs",0)
            .put("reminderMinutes",0)
            .put("allDay",false)
            .put("chatgpt",false);
    }

    private static JSONObject snapshot(JSONArray events) throws Exception {
        return new JSONObject().put("version",1).put("revision",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            .put("lastSync","2026-10-12T07:00:00Z").put("events",events);
    }

    private static void collectText(View view,List<String> texts) {
        if(view instanceof TextView) texts.add(((TextView)view).getText().toString());
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) collectText(group.getChildAt(i),texts);
        }
    }

    private static void assertRenderedText(ActivityScenario<MainActivity> scenario,String... expected) {
        // The shared emulator sometimes has a system-server ANR window over
        // the app. Assert the Activity's actual rendered native view tree without
        // depending on system window focus; physical-screen tests remain separate.
        scenario.onActivity(activity->{
            ArrayList<String> labels=new ArrayList<>();
            collectText(activity.getWindow().getDecorView(),labels);
            for(String wanted:expected) {
                boolean found=false;
                for(String actual:labels) if(actual.contains(wanted)) { found=true; break; }
                assertTrue("Missing '"+wanted+"' in "+labels,found);
            }
        });
    }

    @Test public void endedAppointmentIsListedAndItsDetailsOpen() throws Exception {
        Intent overview=new Intent(target,MainActivity.class);
        overview.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(overview)) {
            assertRenderedText(scenario,"Current and recent appointments",
                "Past appointment remains accessible");
        }
        Intent details=new Intent(target,MainActivity.class)
            .setAction("com.hans.android.calendarreminder.VIEW_EVENT")
            .putExtra(MainActivity.EXTRA_SHOW_EVENT_KEY,"regression-past-event")
            .putExtra(MainActivity.EXTRA_SHOW_EVENT_START,pastStart)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(details)) {
            assertRenderedText(scenario,"PAST APPOINTMENT",
                "Full description retained after the event ended.",
                "Conference Room 42","Meeting organizer",
                "Invited participant");
        }
    }

    @Test public void dismissClearsNotificationButDoesNotAcknowledge() throws Exception {
        if(Build.VERSION.SDK_INT>=33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(target.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        long now=System.currentTimeMillis();
        JSONObject eventJson=makeEvent("dismiss-regression",now-1000,now+20*60_000L);
        store.replaceSnapshot(snapshot(new JSONArray().put(eventJson)));
        CalendarEvent event=CalendarEvent.fromJson(eventJson);
        new ReminderReceiver().onReceive(target,
            new Intent(target,ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_ALARM)
                .putExtra(ReminderScheduler.EXTRA_KEY,event.key)
                .putExtra(ReminderScheduler.EXTRA_AT,event.notifyAt(store.leadMinutes())));
        NotificationManager manager=target.getSystemService(NotificationManager.class);
        int id=event.key.hashCode() & 0x7fffffff;
        assertTrue("Reminder posted before dismiss",isActive(manager,id));
        assertFalse(store.acknowledged(event));
        new ReminderReceiver().onReceive(target,
            new Intent(target,ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_DISMISS)
                .putExtra(ReminderScheduler.EXTRA_KEY,event.key));
        assertTrue("Dismiss clears the active notification",notificationRemovedWithin(manager,id));
        assertFalse("Dismiss must not mark event acknowledged",store.acknowledged(event));
        assertNotNull("Event details remain accessible after dismiss",
            store.findEvent(event.key,event.startMs));
    }

    @Test public void acknowledgePersistsSeparatelyFromDismiss() throws Exception {
        if(Build.VERSION.SDK_INT>=33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(target.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        long now=System.currentTimeMillis();
        JSONObject eventJson=makeEvent("acknowledge-regression",now-1000,now+20*60_000L);
        store.replaceSnapshot(snapshot(new JSONArray().put(eventJson)));
        CalendarEvent event=CalendarEvent.fromJson(eventJson);
        new ReminderReceiver().onReceive(target,
            new Intent(target,ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_ALARM)
                .putExtra(ReminderScheduler.EXTRA_KEY,event.key)
                .putExtra(ReminderScheduler.EXTRA_AT,event.notifyAt(store.leadMinutes())));
        NotificationManager manager=target.getSystemService(NotificationManager.class);
        int id=event.key.hashCode() & 0x7fffffff;
        assertTrue("Reminder posted before acknowledgement",isActive(manager,id));
        new ReminderReceiver().onReceive(target,
            new Intent(target,ReminderReceiver.class)
                .setAction(ReminderScheduler.ACTION_ACKNOWLEDGE)
                .putExtra(ReminderScheduler.EXTRA_KEY,event.key));
        assertTrue("Acknowledgement must be recorded",store.acknowledged(event));
        assertTrue("Acknowledged notification clears",notificationRemovedWithin(manager,id));
        assertNotNull("Acknowledged event still in history",store.findEvent(event.key,event.startMs));
    }

    private static boolean isActive(NotificationManager manager,int id) {
        for(StatusBarNotification item:manager.getActiveNotifications())
            if(item.getId()==id) return true;
        return false;
    }

    private static boolean notificationRemovedWithin(NotificationManager manager,int id) {
        // Cancellation propagates asynchronously through Android's notification
        // manager; don't incorrectly require it to disappear in the same frame.
        long deadline=android.os.SystemClock.uptimeMillis()+4000L;
        while(android.os.SystemClock.uptimeMillis()<deadline) {
            if(!isActive(manager,id)) return true;
            android.os.SystemClock.sleep(80L);
        }
        return !isActive(manager,id);
    }

    @Test public void tappingReminderDoesNotAutoDismissAndItsSnapshotSurvivesSync() throws Exception {
        if(Build.VERSION.SDK_INT>=33) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(target.getPackageName(),
                    Manifest.permission.POST_NOTIFICATIONS);
        }
        long now=System.currentTimeMillis();
        JSONObject live=makeEvent("regression-notification",now-5000L,now+20*60_000L);
        store.replaceSnapshot(snapshot(new JSONArray().put(live)));
        CalendarEvent event=CalendarEvent.fromJson(live);
        Intent alarm=new Intent(target,ReminderReceiver.class)
            .setAction(ReminderScheduler.ACTION_ALARM)
            .putExtra(ReminderScheduler.EXTRA_KEY,event.key)
            .putExtra(ReminderScheduler.EXTRA_AT,event.notifyAt(store.leadMinutes()));
        new ReminderReceiver().onReceive(target,alarm);
        NotificationManager manager=target.getSystemService(NotificationManager.class);
        int id=event.key.hashCode() & 0x7fffffff;
        Notification posted=null;
        for(StatusBarNotification item:manager.getActiveNotifications()) {
            if(item.getId()==id) posted=item.getNotification();
        }
        assertNotNull("Actual event notification must be posted",posted);
        assertEquals("Notification tap must not auto-dismiss",
            0,posted.flags & Notification.FLAG_AUTO_CANCEL);
        assertNotNull("Notification must open unique event content intent",posted.contentIntent);
        assertNotNull("Calendar event actions are required",posted.actions);
        assertEquals("Three explicit actions should be visible",3,posted.actions.length);
        assertEquals("Acknowledge",posted.actions[0].title.toString());
        assertEquals("Snooze 10 min",posted.actions[1].title.toString());
        assertEquals("Dismiss",posted.actions[2].title.toString());
        CharSequence title=posted.extras.getCharSequence(Notification.EXTRA_TITLE);
        assertEquals("Past appointment remains accessible",title.toString());
        String body=posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT,"").toString();
        assertTrue(body.contains("Full description retained"));
        // Replacing the remote snapshot must not delete the notified event.
        store.replaceSnapshot(snapshot(new JSONArray()));
        CalendarEvent archived=store.findEvent(event.key,event.startMs);
        assertNotNull("The full event must remain accessible after sync",archived);
        assertEquals("Conference Room 42",archived.location);
        assertTrue(archived.attendees.get(0).contains("Invited participant"));
        Intent details=new Intent(target,MainActivity.class)
            .putExtra(MainActivity.EXTRA_SHOW_EVENT_KEY,event.key)
            .putExtra(MainActivity.EXTRA_SHOW_EVENT_START,event.startMs)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(details)) {
            assertRenderedText(scenario,"Past appointment remains accessible",
                "Full description retained after the event ended.",
                "Acknowledge event","Dismiss notification");
        }
    }
}
