package com.hans.android.calendarreminder;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Date;

public final class CalendarEvent {
    public final String key, title, description, location, url;
    public final long startMs, endMs, allDayAlertMs;
    public final int googleReminderMinutes;
    public final boolean allDay, chatgpt;

    private CalendarEvent(String key, String title, String description, String location,
                          String url, long startMs, long endMs, long allDayAlertMs,
                          int googleReminderMinutes, boolean allDay, boolean chatgpt) {
        this.key=key; this.title=title; this.description=description;
        this.location=location; this.url=url; this.startMs=startMs; this.endMs=endMs;
        this.allDayAlertMs=allDayAlertMs; this.googleReminderMinutes=googleReminderMinutes;
        this.allDay=allDay; this.chatgpt=chatgpt;
    }

    public static CalendarEvent fromJson(JSONObject j) throws JSONException {
        String key=j.getString("key");
        long start=j.getLong("startEpochMs"), end=j.getLong("endEpochMs");
        if (key.length()<1 || key.length()>500 || start<100000000000L || end<start ||
            end>4102444800000L) throw new JSONException("Invalid event identity/date");
        int offset=j.isNull("reminderMinutes") ? -1 : j.optInt("reminderMinutes",-1);
        if (offset>40320 || offset < -1) throw new JSONException("Invalid reminder offset");
        return new CalendarEvent(key,j.optString("title","(Untitled)"),
            j.optString("description",""),j.optString("location",""),j.optString("url",""),
            start,end,j.optLong("allDayAlertEpochMs",0),offset,
            j.optBoolean("allDay",false),j.optBoolean("chatgpt",false));
    }

    public int reminderMinutes(int fallback) {
        return googleReminderMinutes>=0 ? googleReminderMinutes : fallback;
    }

    public long notifyAt(int fallback) {
        if (allDay) return allDayAlertMs > 0 ? allDayAlertMs : startMs;
        return startMs - reminderMinutes(fallback)*60000L;
    }

    public String deliveryKey(int fallback) {
        return key+"|"+notifyAt(fallback);
    }

    public boolean isUpcoming(long now) {
        return startMs>=now || (allDay && endMs>now);
    }
}
