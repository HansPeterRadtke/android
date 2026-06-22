package com.hans.android.reminder_core;

import java.util.*;

public final class RepeatCalculator {
    private RepeatCalculator() {}

    public static Calendar nextDue(ReminderTask task, long nowMillis) {
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(nowMillis);
        if (ReminderTask.REPEAT_HOURLY.equals(task.repeatMode)) return afterInterval(task, nowMillis, 0, 1, 0);
        if (ReminderTask.REPEAT_EVERY_N_HOURS.equals(task.repeatMode)) return afterInterval(task, nowMillis, 0, Math.max(1, task.intervalHours), 0);
        if (ReminderTask.REPEAT_CUSTOM_INTERVAL.equals(task.repeatMode)) return afterInterval(task, nowMillis, Math.max(0, task.intervalDays), Math.max(0, task.intervalHours), Math.max(0, task.intervalMinutes));
        if (ReminderTask.REPEAT_EVERY_N_DAYS.equals(task.repeatMode)) return afterInterval(task, nowMillis, Math.max(1, task.intervalDays), 0, 0);
        if (ReminderTask.REPEAT_WEEKLY.equals(task.repeatMode)) return nextWeekly(task, nowMillis);
        if (ReminderTask.REPEAT_MONTHLY.equals(task.repeatMode)) return nextMonthly(task, nowMillis);
        if (ReminderTask.REPEAT_ONCE.equals(task.repeatMode)) return nextDailyTime(task, nowMillis, false);
        return nextDailyTime(task, nowMillis, true);
    }

    private static Calendar afterInterval(ReminderTask task, long nowMillis, int days, int hours, int minutes) {
        Calendar c = Calendar.getInstance();
        long anchor = task.lastDueAt > 0 ? task.lastDueAt : task.createdAt;
        if (anchor <= 0) anchor = nowMillis;
        c.setTimeInMillis(anchor);
        if (days == 0 && hours == 0 && minutes == 0) hours = 1;
        while (c.getTimeInMillis() <= nowMillis) {
            if (days != 0) c.add(Calendar.DAY_OF_YEAR, days);
            if (hours != 0) c.add(Calendar.HOUR_OF_DAY, hours);
            if (minutes != 0) c.add(Calendar.MINUTE, minutes);
        }
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static Calendar nextDailyTime(ReminderTask task, long nowMillis, boolean repeating) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMillis);
        c.set(Calendar.HOUR_OF_DAY, task.hour);
        c.set(Calendar.MINUTE, task.minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= nowMillis) c.add(Calendar.DAY_OF_YEAR, 1);
        return c;
    }

    private static Calendar nextWeekly(ReminderTask task, long nowMillis) {
        int todayBit = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;
        int mask = task.weekdaysMask == 0 ? (1 << todayBit) : task.weekdaysMask;
        Calendar best = null;
        for (int add = 0; add <= 14; add++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(nowMillis);
            c.add(Calendar.DAY_OF_YEAR, add);
            int bit = c.get(Calendar.DAY_OF_WEEK) - 1;
            if ((mask & (1 << bit)) == 0) continue;
            c.set(Calendar.HOUR_OF_DAY, task.hour);
            c.set(Calendar.MINUTE, task.minute);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() > nowMillis && (best == null || c.getTimeInMillis() < best.getTimeInMillis())) best = c;
        }
        return best == null ? nextDailyTime(task, nowMillis, true) : best;
    }

    private static Calendar nextMonthly(ReminderTask task, long nowMillis) {
        Calendar best = null;
        for (int add = 0; add <= 13; add++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(nowMillis);
            c.add(Calendar.MONTH, add);
            int max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
            c.set(Calendar.DAY_OF_MONTH, Math.max(1, Math.min(max, task.dayOfMonth)));
            c.set(Calendar.HOUR_OF_DAY, task.hour);
            c.set(Calendar.MINUTE, task.minute);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() > nowMillis && (best == null || c.getTimeInMillis() < best.getTimeInMillis())) best = c;
        }
        return best == null ? nextDailyTime(task, nowMillis, true) : best;
    }
}
