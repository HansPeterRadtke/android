package com.hans.android.reminder_core;

import org.json.JSONObject;
import java.util.*;

public class ReminderTask {
    public static final String REPEAT_HOURLY = "hourly";
    public static final String REPEAT_DAILY = "daily";
    public static final String REPEAT_WEEKLY = "weekly";
    public static final String REPEAT_MONTHLY = "monthly";
    public static final String REPEAT_EVERY_N_DAYS = "every_n_days";
    public static final String REPEAT_EVERY_N_HOURS = "every_n_hours";
    public static final String REPEAT_CUSTOM_INTERVAL = "custom_interval";
    public static final String REPEAT_ONCE = "once";

    public static final String SNOOZE_FIXED = "fixed";
    public static final String SNOOZE_CHOOSE_EACH_TIME = "choose_each_time";
    public static final String ACTION_PROFILE_FAST = "fast";
    public static final String ACTION_PROFILE_DISMISS_CHOICE = "dismiss_choice";
    public static final String ACTION_PROFILE_SNOOZE_CHOICE = "snooze_choice";

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

    public String repeatMode;
    public int weekdaysMask;
    public int dayOfMonth;
    public int intervalDays;
    public int intervalHours;
    public int intervalMinutes;
    public long lastDueAt;
    public long openOccurrenceDueAt;
    public int openSnoozeCount;
    public long completedCount;
    public long dismissedCount;
    public long missedCount;
    public boolean stackMissedOccurrences;
    public int pendingStackCount;
    public boolean showCarryOverDismissAction;
    public String snoozeMode;
    public String notificationActionProfile;

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
        repeatMode = REPEAT_DAILY;
        weekdaysMask = 0;
        dayOfMonth = 1;
        intervalDays = 0;
        intervalHours = 1;
        intervalMinutes = 0;
        lastDueAt = 0;
        openOccurrenceDueAt = 0;
        openSnoozeCount = 0;
        completedCount = 0;
        dismissedCount = 0;
        missedCount = 0;
        stackMissedOccurrences = false;
        pendingStackCount = 0;
        showCarryOverDismissAction = true;
        snoozeMode = SNOOZE_FIXED;
        notificationActionProfile = ACTION_PROFILE_FAST;
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
        o.put("repeatMode", repeatMode);
        o.put("weekdaysMask", weekdaysMask);
        o.put("dayOfMonth", dayOfMonth);
        o.put("intervalDays", intervalDays);
        o.put("intervalHours", intervalHours);
        o.put("intervalMinutes", intervalMinutes);
        o.put("lastDueAt", lastDueAt);
        o.put("openOccurrenceDueAt", openOccurrenceDueAt);
        o.put("openSnoozeCount", openSnoozeCount);
        o.put("completedCount", completedCount);
        o.put("dismissedCount", dismissedCount);
        o.put("missedCount", missedCount);
        o.put("stackMissedOccurrences", stackMissedOccurrences);
        o.put("pendingStackCount", pendingStackCount);
        o.put("showCarryOverDismissAction", showCarryOverDismissAction);
        o.put("snoozeMode", snoozeMode);
        o.put("notificationActionProfile", notificationActionProfile);
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
        t.repeatMode = o.optString("repeatMode", t.daily ? REPEAT_DAILY : REPEAT_ONCE);
        t.weekdaysMask = o.optInt("weekdaysMask", 0);
        t.dayOfMonth = o.optInt("dayOfMonth", 1);
        t.intervalDays = o.optInt("intervalDays", 0);
        t.intervalHours = o.optInt("intervalHours", t.repeatMode.equals(REPEAT_HOURLY) ? 1 : 0);
        t.intervalMinutes = o.optInt("intervalMinutes", 0);
        t.lastDueAt = o.optLong("lastDueAt", 0);
        t.openOccurrenceDueAt = o.optLong("openOccurrenceDueAt", 0);
        t.openSnoozeCount = o.optInt("openSnoozeCount", 0);
        t.completedCount = o.optLong("completedCount", 0);
        t.dismissedCount = o.optLong("dismissedCount", 0);
        t.missedCount = o.optLong("missedCount", 0);
        t.stackMissedOccurrences = o.optBoolean("stackMissedOccurrences", false);
        t.pendingStackCount = o.optInt("pendingStackCount", 0);
        t.showCarryOverDismissAction = o.optBoolean("showCarryOverDismissAction", true);
        t.snoozeMode = o.optString("snoozeMode", SNOOZE_FIXED);
        t.notificationActionProfile = o.optString("notificationActionProfile", ACTION_PROFILE_FAST);
        if (t.notificationActionProfile == null || t.notificationActionProfile.trim().isEmpty()) t.notificationActionProfile = ACTION_PROFILE_FAST;
        return t;
    }

    public String repeatSummary() {
        if (REPEAT_HOURLY.equals(repeatMode)) return "Hourly";
        if (REPEAT_DAILY.equals(repeatMode)) return "Daily";
        if (REPEAT_WEEKLY.equals(repeatMode)) return "Weekly " + weekdaySummary();
        if (REPEAT_MONTHLY.equals(repeatMode)) return "Monthly on day " + dayOfMonth;
        if (REPEAT_EVERY_N_DAYS.equals(repeatMode)) return "Every " + Math.max(1, intervalDays) + " day(s)";
        if (REPEAT_EVERY_N_HOURS.equals(repeatMode)) return "Every " + Math.max(1, intervalHours) + " hour(s)";
        if (REPEAT_CUSTOM_INTERVAL.equals(repeatMode)) return "Custom interval: " + intervalSummary();
        return "One-shot";
    }

    public String stackSummary() {
        String base = stackMissedOccurrences ? "Carry missed/not-done forward automatically" : "Dismiss/missed is gone unless Carry to next is chosen";
        if (pendingStackCount > 0) base += " · pending " + pendingStackCount;
        return base;
    }

    public boolean chooseSnoozeEachTime() {
        return SNOOZE_CHOOSE_EACH_TIME.equals(snoozeMode);
    }

    public String snoozeSummary() {
        return chooseSnoozeEachTime() ? "Ask every time" : "Fixed " + defaultSnoozeMinutes + " minutes";
    }

    public String notificationActionSummary() {
        if (ACTION_PROFILE_DISMISS_CHOICE.equals(notificationActionProfile)) return "Complete · Dismiss · Carry to next";
        if (ACTION_PROFILE_SNOOZE_CHOICE.equals(notificationActionProfile)) return "Complete · Choose snooze · Dismiss";
        return chooseSnoozeEachTime() ? "Complete · Choose snooze · Dismiss" : "Complete · Snooze · Dismiss";
    }

    private String intervalSummary() {
        ArrayList<String> parts = new ArrayList<>();
        if (intervalDays > 0) parts.add(intervalDays + " day(s)");
        if (intervalHours > 0) parts.add(intervalHours + " hour(s)");
        if (intervalMinutes > 0) parts.add(intervalMinutes + " minute(s)");
        return parts.isEmpty() ? "1 hour" : join(parts, ", ");
    }

    public String weekdaySummary() {
        String[] names = new String[]{"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) if ((weekdaysMask & (1 << i)) != 0) out.add(names[i]);
        return out.isEmpty() ? "on any weekday" : join(out, ", ");
    }

    private static String join(ArrayList<String> a, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.size(); i++) { if (i > 0) b.append(sep); b.append(a.get(i)); }
        return b.toString();
    }
}
