package com.hans.android.reminder_core;

import org.json.JSONObject;

public class ReminderTask {
    public long id;
    public String title;
    public String notes;
    public int hour;
    public int minute;
    public boolean daily;
    public boolean enabled;
    public int defaultSnoozeMinutes;
    public long createdAt;
    public long lastScheduledAt;

    public ReminderTask() {
        id = System.currentTimeMillis();
        title = "New task";
        notes = "";
        hour = 9;
        minute = 0;
        daily = true;
        enabled = true;
        defaultSnoozeMinutes = 15;
        createdAt = System.currentTimeMillis();
        lastScheduledAt = 0;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("notes", notes);
        o.put("hour", hour);
        o.put("minute", minute);
        o.put("daily", daily);
        o.put("enabled", enabled);
        o.put("defaultSnoozeMinutes", defaultSnoozeMinutes);
        o.put("createdAt", createdAt);
        o.put("lastScheduledAt", lastScheduledAt);
        return o;
    }

    public static ReminderTask fromJson(JSONObject o) throws Exception {
        ReminderTask t = new ReminderTask();
        t.id = o.optLong("id", System.currentTimeMillis());
        t.title = o.optString("title", "Task");
        t.notes = o.optString("notes", "");
        t.hour = o.optInt("hour", 9);
        t.minute = o.optInt("minute", 0);
        t.daily = o.optBoolean("daily", true);
        t.enabled = o.optBoolean("enabled", true);
        t.defaultSnoozeMinutes = o.optInt("defaultSnoozeMinutes", 15);
        t.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        t.lastScheduledAt = o.optLong("lastScheduledAt", 0);
        return t;
    }
}
