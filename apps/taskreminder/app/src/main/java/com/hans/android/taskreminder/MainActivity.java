package com.hans.android.taskreminder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.hans.android.common_ui.AndroidUi;
import com.hans.android.reminder_core.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private ReminderStore store;
    private LinearLayout list;
    private LinearLayout statusBox;
    private TextView updatedAt;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ReminderStore(this);
        ReminderScheduler.ensureChannel(this);
        requestPermissionsIfNeeded();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (list != null) refreshScreen();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root);
        root.addView(AndroidUi.title(this, "Task Reminder"));
        root.addView(AndroidUi.body(this, "Status first. History is still recorded, but the first screen shows what needs attention and what will happen next."));
        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(statusBox);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button add = AndroidUi.button(this, "Add task");
        add.setOnClickListener(v -> editTask(new ReminderTask()));
        actions.addView(add);
        Button history = AndroidUi.button(this, "Open history");
        history.setOnClickListener(v -> showHistory());
        actions.addView(history);
        root.addView(actions);
        Button exact = AndroidUi.button(this, "Fix exact alarm permission");
        exact.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)); });
        root.addView(exact);
        root.addView(AndroidUi.section(this, "Tasks"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        updatedAt = AndroidUi.small(this, "");
        root.addView(updatedAt);
        setContentView(scroll);
        refreshScreen();
    }

    private void refreshScreen() {
        refreshStatus();
        refreshTasks();
        updatedAt.setText("Refreshed " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
    }

    private void refreshStatus() {
        statusBox.removeAllViews();
        ArrayList<ReminderTask> tasks = store.loadTasks();
        int enabled = 0;
        ReminderTask next = null;
        long nextWhen = Long.MAX_VALUE;
        for (ReminderTask t : tasks) {
            if (!t.enabled) continue;
            enabled++;
            Calendar c = DayUtil.nextTime(t.hour, t.minute);
            if (c.getTimeInMillis() < nextWhen) { nextWhen = c.getTimeInMillis(); next = t; }
        }
        boolean notificationsOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean alarmsOk = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarmsOk = am.canScheduleExactAlarms();
        }
        statusBox.addView(AndroidUi.status(this, notificationsOk ? "Notifications allowed" : "Notifications blocked. Reminders cannot appear until permission is granted.", notificationsOk));
        statusBox.addView(AndroidUi.status(this, alarmsOk ? "Exact alarms allowed" : "Exact alarms may be blocked. Open alarm settings before trusting reminders.", alarmsOk));
        String nextText = next == null ? "No enabled task is scheduled." : "Next: " + next.title + " at " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(nextWhen));
        statusBox.addView(AndroidUi.status(this, enabled + " enabled task(s). " + nextText, next != null));
    }

    private void refreshTasks() {
        list.removeAllViews();
        ArrayList<ReminderTask> tasks = store.loadTasks();
        if (tasks.isEmpty()) {
            LinearLayout empty = AndroidUi.card(this);
            empty.addView(AndroidUi.section(this, "No tasks yet"));
            empty.addView(AndroidUi.body(this, "Create one task, choose its due time and default snooze duration, then save and schedule it."));
            list.addView(empty);
            return;
        }
        Collections.sort(tasks, (a, b) -> Long.compare(DayUtil.nextTime(a.hour, a.minute).getTimeInMillis(), DayUtil.nextTime(b.hour, b.minute).getTimeInMillis()));
        for (ReminderTask t : tasks) list.addView(taskView(t));
    }

    private View taskView(ReminderTask t) {
        LinearLayout box = AndroidUi.card(this);
        Calendar next = DayUtil.nextTime(t.hour, t.minute);
        box.addView(AndroidUi.text(this, t.title, 19, true));
        box.addView(AndroidUi.body(this, (t.enabled ? "Enabled" : "Disabled") + " · " + (t.daily ? "Daily" : "One-shot") + " · next " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(next.getTime()) + " · snooze " + t.defaultSnoozeMinutes + " min"));
        if (t.notes != null && !t.notes.isEmpty()) box.addView(AndroidUi.small(this, t.notes));
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button done = AndroidUi.button(this, "Complete today");
        done.setOnClickListener(v -> { ReminderScheduler.cancelTask(this, t.id); store.appendHistory(t.id, "completed_manual", t.title); if (t.daily) ReminderScheduler.scheduleTask(this, t, true); Toast.makeText(this, "Task completed and logged", Toast.LENGTH_SHORT).show(); refreshScreen(); });
        row1.addView(done);
        Button snooze = AndroidUi.button(this, "Snooze " + t.defaultSnoozeMinutes + " min");
        snooze.setOnClickListener(v -> { ReminderScheduler.scheduleSnooze(this, t, t.defaultSnoozeMinutes); Toast.makeText(this, "Snoozed and logged", Toast.LENGTH_SHORT).show(); refreshScreen(); });
        row1.addView(snooze);
        box.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = AndroidUi.button(this, "Edit schedule"); edit.setOnClickListener(v -> editTask(t)); row2.addView(edit);
        Button del = AndroidUi.button(this, "Delete task"); del.setOnClickListener(v -> { ReminderScheduler.cancelTask(this, t.id); store.delete(t.id); refreshScreen(); }); row2.addView(del);
        box.addView(row2);
        return box;
    }

    private void showHistory() {
        Dialog d = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root);
        root.addView(AndroidUi.title(this, "History log"));
        root.addView(AndroidUi.body(this, "Exact append-only detail from the app-private history file."));
        TextView h = AndroidUi.small(this, store.readHistory());
        h.setTypeface(Typeface.MONOSPACE);
        root.addView(h);
        Button close = AndroidUi.button(this, "Close history");
        close.setOnClickListener(v -> d.dismiss());
        root.addView(close);
        d.setContentView(scroll);
        d.show();
    }

    private void editTask(ReminderTask task) {
        final Dialog d = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root);
        root.addView(AndroidUi.title(this, task.title == null || task.title.equals("New task") ? "Create task" : "Edit task"));
        EditText title = input(task.title); root.addView(label("Task name")); root.addView(title);
        EditText notes = input(task.notes); notes.setMinLines(3); root.addView(label("Notes shown in notification")); root.addView(notes);
        EditText hour = input(String.valueOf(task.hour)); root.addView(label("Due hour 0-23")); root.addView(hour);
        EditText minute = input(String.valueOf(task.minute)); root.addView(label("Due minute 0-59")); root.addView(minute);
        EditText snooze = input(String.valueOf(task.defaultSnoozeMinutes)); root.addView(label("Default snooze minutes")); root.addView(snooze);
        CheckBox daily = new CheckBox(this); daily.setText("Repeat daily"); daily.setChecked(task.daily); root.addView(daily);
        CheckBox enabled = new CheckBox(this); enabled.setText("Enabled and scheduled"); enabled.setChecked(task.enabled); root.addView(enabled);
        Button save = AndroidUi.button(this, "Save and schedule reminder");
        save.setOnClickListener(v -> {
            try {
                task.title = title.getText().toString().trim().isEmpty() ? "Task" : title.getText().toString().trim();
                task.notes = notes.getText().toString();
                task.hour = clamp(Integer.parseInt(hour.getText().toString().trim()), 0, 23);
                task.minute = clamp(Integer.parseInt(minute.getText().toString().trim()), 0, 59);
                task.defaultSnoozeMinutes = Math.max(1, Integer.parseInt(snooze.getText().toString().trim()));
                task.daily = daily.isChecked();
                task.enabled = enabled.isChecked();
                store.upsert(task);
                store.appendHistory(task.id, "saved", task.title + " at " + String.format(Locale.US, "%02d:%02d", task.hour, task.minute));
                ReminderScheduler.cancelTask(this, task.id);
                if (task.enabled) ReminderScheduler.scheduleTask(this, task, true);
                d.dismiss(); refreshScreen();
            } catch (Exception e) { Toast.makeText(this, "Cannot save: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        root.addView(save);
        Button cancel = AndroidUi.button(this, "Cancel");
        cancel.setOnClickListener(v -> d.dismiss());
        root.addView(cancel);
        d.setContentView(scroll);
        d.show();
    }

    private TextView label(String s) { return AndroidUi.text(this, s, 14, true); }
    private EditText input(String s) { EditText e = new EditText(this); e.setText(s == null ? "" : s); e.setSingleLine(false); return e; }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
