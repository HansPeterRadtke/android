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
    private static final int MODE_TODAY = 0;
    private static final int MODE_MANAGE = 1;
    private static final int MODE_HISTORY = 2;
    private int mode = MODE_TODAY;
    private ReminderStore store;
    private LinearLayout root;
    private TextView refreshText;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ReminderStore(this);
        ReminderScheduler.ensureChannel(this);
        requestPermissionsIfNeeded();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (root != null) render();
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUi.dp(this, 18), AndroidUi.dp(this, 18), AndroidUi.dp(this, 18), AndroidUi.dp(this, 24));
        root.setBackgroundColor(AndroidUi.BG);
        scroll.setBackgroundColor(AndroidUi.BG);
        scroll.addView(root);
        root.addView(AndroidUi.title(this, "Task Reminder"));
        refreshText = AndroidUi.small(this, "Updated " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
        root.addView(refreshText);
        root.addView(trustBanner());
        root.addView(modeNav());
        if (mode == MODE_TODAY) renderToday();
        if (mode == MODE_MANAGE) renderManage();
        if (mode == MODE_HISTORY) renderHistory(false);
        setContentView(scroll);
    }

    private View modeNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        Button today = AndroidUi.modeButton(this, "Today", mode == MODE_TODAY);
        today.setOnClickListener(v -> { mode = MODE_TODAY; render(); });
        Button manage = AndroidUi.modeButton(this, "Manage", mode == MODE_MANAGE);
        manage.setOnClickListener(v -> { mode = MODE_MANAGE; render(); });
        Button history = AndroidUi.modeButton(this, "History", mode == MODE_HISTORY);
        history.setOnClickListener(v -> { mode = MODE_HISTORY; render(); });
        nav.addView(today); nav.addView(manage); nav.addView(history);
        return nav;
    }

    private View trustBanner() {
        boolean notificationsOk = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean alarmsOk = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            alarmsOk = am.canScheduleExactAlarms();
        }
        boolean ok = notificationsOk && alarmsOk;
        LinearLayout b = AndroidUi.banner(this, ok ? AndroidUi.GREEN : AndroidUi.RED);
        b.addView(AndroidUi.text(this, ok ? "OK: reminders can notify you" : "BLOCKED: reminders may not appear", 18, true, ok ? AndroidUi.GREEN : AndroidUi.RED));
        String detail = (notificationsOk ? "Notifications allowed" : "Notifications blocked") + " · " + (alarmsOk ? "Exact alarms allowed" : "Exact alarms blocked or restricted");
        b.addView(AndroidUi.body(this, detail));
        if (!notificationsOk) {
            Button p = AndroidUi.button(this, "Allow notification permission");
            p.setOnClickListener(v -> requestPermissionsIfNeeded());
            b.addView(p);
        }
        if (!alarmsOk) {
            Button a = AndroidUi.button(this, "Open exact alarm settings");
            a.setOnClickListener(v -> { if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)); });
            b.addView(a);
        }
        return b;
    }

    private void renderToday() {
        ArrayList<ReminderTask> tasks = sortedTasks(true);
        HistoryStats stats = historyStats();
        root.addView(AndroidUi.section(this, "Today"));
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(AndroidUi.metric(this, "enabled", String.valueOf(tasks.size()), AndroidUi.BLUE));
        metrics.addView(AndroidUi.metric(this, "completed", String.valueOf(stats.completedToday), AndroidUi.GREEN));
        metrics.addView(AndroidUi.metric(this, "snoozed", String.valueOf(stats.snoozedToday), AndroidUi.ORANGE));
        root.addView(metrics);
        ReminderTask next = tasks.isEmpty() ? null : tasks.get(0);
        if (next == null) {
            LinearLayout empty = AndroidUi.card(this);
            empty.addView(AndroidUi.text(this, "No reminders are active", 20, true, AndroidUi.INK));
            empty.addView(AndroidUi.body(this, "Create your first task and schedule it. Until then there is nothing to notify you about."));
            Button add = AndroidUi.button(this, "Create first task");
            add.setOnClickListener(v -> editTask(new ReminderTask()));
            empty.addView(add);
            root.addView(empty);
            return;
        }
        LinearLayout hero = AndroidUi.banner(this, AndroidUi.BLUE);
        Calendar c = DayUtil.nextTime(next.hour, next.minute);
        hero.addView(AndroidUi.text(this, "Next reminder", 14, true, AndroidUi.BLUE));
        hero.addView(AndroidUi.text(this, next.title, 24, true, AndroidUi.INK));
        hero.addView(AndroidUi.body(this, new SimpleDateFormat("EEEE HH:mm", Locale.US).format(c.getTime()) + " · snooze " + next.defaultSnoozeMinutes + " min"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button done = AndroidUi.button(this, "Complete today");
        done.setOnClickListener(v -> completeTask(next));
        Button snooze = AndroidUi.button(this, "Snooze " + next.defaultSnoozeMinutes + " min");
        snooze.setOnClickListener(v -> snoozeTask(next));
        row.addView(done); row.addView(snooze);
        hero.addView(row);
        root.addView(hero);
        root.addView(AndroidUi.section(this, "Upcoming"));
        int shown = 0;
        for (ReminderTask t : tasks) {
            if (shown >= 5) break;
            root.addView(compactTaskCard(t, false));
            shown++;
        }
    }

    private void renderManage() {
        root.addView(AndroidUi.section(this, "Manage tasks"));
        Button add = AndroidUi.button(this, "Add new reminder task");
        add.setOnClickListener(v -> editTask(new ReminderTask()));
        root.addView(add);
        ArrayList<ReminderTask> tasks = sortedTasks(false);
        if (tasks.isEmpty()) {
            LinearLayout empty = AndroidUi.card(this);
            empty.addView(AndroidUi.text(this, "No saved tasks", 20, true, AndroidUi.INK));
            empty.addView(AndroidUi.body(this, "Use Add new reminder task to create a daily or one-shot reminder."));
            root.addView(empty);
            return;
        }
        for (ReminderTask t : tasks) root.addView(compactTaskCard(t, true));
    }

    private View compactTaskCard(ReminderTask t, boolean management) {
        LinearLayout box = AndroidUi.card(this);
        Calendar next = DayUtil.nextTime(t.hour, t.minute);
        box.addView(AndroidUi.text(this, t.title, 19, true, t.enabled ? AndroidUi.INK : AndroidUi.MUTED));
        box.addView(AndroidUi.body(this, (t.enabled ? "Enabled" : "Disabled") + " · " + (t.daily ? "Daily" : "One-shot") + " · " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(next.getTime()) + " · snooze " + t.defaultSnoozeMinutes + " min"));
        if (t.notes != null && !t.notes.trim().isEmpty()) box.addView(AndroidUi.small(this, t.notes));
        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        Button done = AndroidUi.button(this, "Complete today");
        done.setOnClickListener(v -> completeTask(t));
        primary.addView(done);
        Button snooze = AndroidUi.button(this, "Snooze");
        snooze.setOnClickListener(v -> snoozeTask(t));
        primary.addView(snooze);
        box.addView(primary);
        if (management) {
            LinearLayout secondary = new LinearLayout(this);
            secondary.setOrientation(LinearLayout.HORIZONTAL);
            Button edit = AndroidUi.button(this, "Edit schedule");
            edit.setOnClickListener(v -> editTask(t));
            secondary.addView(edit);
            Button del = AndroidUi.button(this, "Delete task");
            del.setOnClickListener(v -> confirmDelete(t));
            secondary.addView(del);
            box.addView(secondary);
        }
        return box;
    }

    private void renderHistory(boolean raw) {
        HistoryStats s = historyStats();
        root.addView(AndroidUi.section(this, "History"));
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(AndroidUi.metric(this, "today done", String.valueOf(s.completedToday), AndroidUi.GREEN));
        metrics.addView(AndroidUi.metric(this, "today snoozed", String.valueOf(s.snoozedToday), AndroidUi.ORANGE));
        metrics.addView(AndroidUi.metric(this, "today missed", String.valueOf(s.missedToday), AndroidUi.RED));
        root.addView(metrics);
        LinearLayout card = AndroidUi.card(this);
        card.addView(AndroidUi.text(this, raw ? "Raw app-private log" : "Recent readable events", 20, true, AndroidUi.INK));
        TextView h = AndroidUi.small(this, raw ? store.readHistory() : s.recentText);
        h.setTypeface(Typeface.MONOSPACE);
        card.addView(h);
        Button toggle = AndroidUi.button(this, raw ? "Show readable summary" : "Open raw log");
        toggle.setOnClickListener(v -> { root.removeAllViews(); renderHeaderOnly(); renderHistory(!raw); });
        card.addView(toggle);
        root.addView(card);
    }

    private void renderHeaderOnly() {
        root.addView(AndroidUi.title(this, "Task Reminder"));
        root.addView(refreshText = AndroidUi.small(this, "Updated " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())));
        root.addView(trustBanner());
        root.addView(modeNav());
    }

    private void completeTask(ReminderTask t) {
        ReminderScheduler.cancelTask(this, t.id);
        store.appendHistory(t.id, "completed_manual", t.title);
        if (t.daily) ReminderScheduler.scheduleTask(this, t, true);
        Toast.makeText(this, "Completed and logged", Toast.LENGTH_SHORT).show();
        render();
    }

    private void snoozeTask(ReminderTask t) {
        ReminderScheduler.scheduleSnooze(this, t, t.defaultSnoozeMinutes);
        Toast.makeText(this, "Snoozed and logged", Toast.LENGTH_SHORT).show();
        render();
    }

    private void confirmDelete(ReminderTask t) {
        new AlertDialog.Builder(this)
            .setTitle("Delete task?")
            .setMessage("This removes future reminders for " + t.title + ". Existing history stays in the log.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete task", (d, w) -> { ReminderScheduler.cancelTask(this, t.id); store.delete(t.id); render(); })
            .show();
    }

    private void editTask(ReminderTask task) {
        final Dialog d = new Dialog(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(AndroidUi.dp(this, 22), AndroidUi.dp(this, 22), AndroidUi.dp(this, 22), AndroidUi.dp(this, 22));
        scroll.addView(form);
        form.addView(AndroidUi.title(this, task.title == null || task.title.equals("New task") ? "Create reminder" : "Edit reminder"));
        EditText title = input(task.title); form.addView(label("Task name")); form.addView(title);
        EditText notes = input(task.notes); notes.setMinLines(3); form.addView(label("Notification notes")); form.addView(notes);
        EditText hour = input(String.valueOf(task.hour)); form.addView(label("Due hour 0-23")); form.addView(hour);
        EditText minute = input(String.valueOf(task.minute)); form.addView(label("Due minute 0-59")); form.addView(minute);
        EditText snooze = input(String.valueOf(task.defaultSnoozeMinutes)); form.addView(label("Snooze minutes")); form.addView(snooze);
        CheckBox daily = new CheckBox(this); daily.setText("Repeat daily"); daily.setChecked(task.daily); form.addView(daily);
        CheckBox enabled = new CheckBox(this); enabled.setText("Enabled and scheduled"); enabled.setChecked(task.enabled); form.addView(enabled);
        Button save = AndroidUi.button(this, "Save and schedule");
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
                d.dismiss(); mode = MODE_TODAY; render();
            } catch (Exception e) { Toast.makeText(this, "Cannot save: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        });
        form.addView(save);
        Button cancel = AndroidUi.button(this, "Cancel");
        cancel.setOnClickListener(v -> d.dismiss());
        form.addView(cancel);
        d.setContentView(scroll);
        d.show();
    }

    private ArrayList<ReminderTask> sortedTasks(boolean enabledOnly) {
        ArrayList<ReminderTask> all = store.loadTasks();
        ArrayList<ReminderTask> out = new ArrayList<>();
        for (ReminderTask t : all) if (!enabledOnly || t.enabled) out.add(t);
        Collections.sort(out, (a, b) -> Long.compare(DayUtil.nextTime(a.hour, a.minute).getTimeInMillis(), DayUtil.nextTime(b.hour, b.minute).getTimeInMillis()));
        return out;
    }

    private HistoryStats historyStats() {
        String raw = store.readHistory();
        String today = DayUtil.todayKey();
        HistoryStats s = new HistoryStats();
        String[] lines = raw.split("\\n");
        StringBuilder recent = new StringBuilder();
        int shown = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i];
            if (line.trim().isEmpty() || line.equals("No history yet.")) continue;
            if (line.contains("\t" + today + "\t") && line.contains("\tcompleted")) s.completedToday++;
            if (line.contains("\t" + today + "\t") && line.contains("\tsnoozed")) s.snoozedToday++;
            if (line.contains("\t" + today + "\t") && line.contains("\tmissed")) s.missedToday++;
            if (shown < 12) { recent.append(humanEvent(line)).append("\n"); shown++; }
        }
        s.recentText = recent.length() == 0 ? "No history yet." : recent.toString();
        return s;
    }

    private String humanEvent(String line) {
        String[] p = line.split("\t", 5);
        if (p.length < 5) return line;
        return p[0] + "  " + p[3] + "  " + p[4];
    }

    private TextView label(String s) { return AndroidUi.text(this, s, 14, true, AndroidUi.INK); }
    private EditText input(String s) { EditText e = new EditText(this); e.setText(s == null ? "" : s); e.setSingleLine(false); return e; }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static class HistoryStats {
        int completedToday;
        int snoozedToday;
        int missedToday;
        String recentText;
    }
}
