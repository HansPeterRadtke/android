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
import com.hans.android.reminder_core.*;
import java.util.*;

public class MainActivity extends Activity {
    private ReminderStore store;
    private LinearLayout list;
    private TextView history;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ReminderStore(this);
        ReminderScheduler.ensureChannel(this);
        requestPermissionsIfNeeded();
        render();
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
        TextView title = text("Task Reminder", 26, true);
        root.addView(title);
        root.addView(text("Create daily or one-shot reminders. Notifications have Complete and Snooze. Every notify, snooze, complete, missed and edit event is written to the app-private history file.", 14, false));
        Button add = button("Add task");
        add.setOnClickListener(v -> editTask(new ReminderTask()));
        root.addView(add);
        Button exact = button("Open exact alarm settings");
        exact.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)); });
        root.addView(exact);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        root.addView(text("History", 22, true));
        Button refresh = button("Refresh history");
        refresh.setOnClickListener(v -> refreshHistory());
        root.addView(refresh);
        history = text("", 12, false);
        history.setTypeface(Typeface.MONOSPACE);
        root.addView(history);
        setContentView(scroll);
        refreshTasks();
        refreshHistory();
    }

    private void refreshTasks() {
        list.removeAllViews();
        ArrayList<ReminderTask> tasks = store.loadTasks();
        if (tasks.isEmpty()) list.addView(text("No tasks yet.", 16, false));
        for (ReminderTask t : tasks) list.addView(taskView(t));
    }

    private View taskView(ReminderTask t) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 18, 18, 18);
        TextView head = text(t.title + "  " + String.format(Locale.US, "%02d:%02d", t.hour, t.minute), 18, true);
        box.addView(head);
        box.addView(text((t.daily ? "Daily" : "One-shot") + " · Snooze " + t.defaultSnoozeMinutes + " min · " + (t.enabled ? "enabled" : "disabled"), 14, false));
        if (t.notes != null && !t.notes.isEmpty()) box.addView(text(t.notes, 14, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = button("Edit"); edit.setOnClickListener(v -> editTask(t)); row.addView(edit);
        Button done = button("Complete now"); done.setOnClickListener(v -> { store.appendHistory(t.id, "completed_manual", t.title); if (t.daily) ReminderScheduler.scheduleTask(this, t, true); Toast.makeText(this, "Completed", Toast.LENGTH_SHORT).show(); refreshHistory(); }); row.addView(done);
        Button snooze = button("Snooze now"); snooze.setOnClickListener(v -> { ReminderScheduler.scheduleSnooze(this, t, t.defaultSnoozeMinutes); Toast.makeText(this, "Snoozed", Toast.LENGTH_SHORT).show(); refreshHistory(); }); row.addView(snooze);
        Button del = button("Delete"); del.setOnClickListener(v -> { ReminderScheduler.cancelTask(this, t.id); store.delete(t.id); refreshTasks(); refreshHistory(); }); row.addView(del);
        box.addView(row);
        return box;
    }

    private void editTask(ReminderTask task) {
        final Dialog d = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        scroll.addView(root);
        EditText title = input(task.title); root.addView(label("Title")); root.addView(title);
        EditText notes = input(task.notes); notes.setMinLines(3); root.addView(label("Notes")); root.addView(notes);
        EditText hour = input(String.valueOf(task.hour)); root.addView(label("Hour 0-23")); root.addView(hour);
        EditText minute = input(String.valueOf(task.minute)); root.addView(label("Minute 0-59")); root.addView(minute);
        EditText snooze = input(String.valueOf(task.defaultSnoozeMinutes)); root.addView(label("Default snooze minutes")); root.addView(snooze);
        CheckBox daily = new CheckBox(this); daily.setText("Repeat daily"); daily.setChecked(task.daily); root.addView(daily);
        CheckBox enabled = new CheckBox(this); enabled.setText("Enabled"); enabled.setChecked(task.enabled); root.addView(enabled);
        Button save = button("Save and schedule");
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
                store.appendHistory(task.id, "saved", task.title + " at " + task.hour + ":" + task.minute);
                ReminderScheduler.cancelTask(this, task.id);
                if (task.enabled) ReminderScheduler.scheduleTask(this, task, true);
                d.dismiss(); refreshTasks(); refreshHistory();
            } catch (Exception e) { Toast.makeText(this, "Bad input: " + e, Toast.LENGTH_LONG).show(); }
        });
        root.addView(save);
        d.setContentView(scroll);
        d.show();
    }

    private void refreshHistory() { history.setText(store.readHistory()); }
    private TextView text(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setPadding(0, 8, 0, 8); if (bold) v.setTypeface(Typeface.DEFAULT_BOLD); return v; }
    private TextView label(String s) { return text(s, 14, true); }
    private EditText input(String s) { EditText e = new EditText(this); e.setText(s == null ? "" : s); return e; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); return b; }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
