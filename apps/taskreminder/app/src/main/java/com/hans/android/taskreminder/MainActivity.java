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
    private static final int MODE_EDIT = 3;
    private int mode = MODE_TODAY;
    private ReminderStore store;
    private LinearLayout root;
    private ReminderTask draft;
    private String feedback = "";

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
        root.addView(AndroidUi.small(this, "Updated " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())));
        root.addView(trustBanner());
        if (feedback != null && !feedback.isEmpty()) root.addView(feedbackBanner());
        if (mode != MODE_EDIT) root.addView(modeNav());
        if (mode == MODE_TODAY) renderToday();
        if (mode == MODE_MANAGE) renderManage();
        if (mode == MODE_HISTORY) renderHistory(false);
        if (mode == MODE_EDIT) renderEdit();
        setContentView(scroll);
    }

    private View feedbackBanner() {
        LinearLayout b = AndroidUi.banner(this, AndroidUi.BLUE);
        b.addView(AndroidUi.text(this, "Action result", 14, true, AndroidUi.BLUE));
        b.addView(AndroidUi.body(this, feedback));
        return b;
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
        if (Build.VERSION.SDK_INT >= 31) alarmsOk = ((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms();
        boolean ok = notificationsOk && alarmsOk;
        LinearLayout b = AndroidUi.banner(this, ok ? AndroidUi.GREEN : AndroidUi.RED);
        b.addView(AndroidUi.text(this, ok ? "OK: reminders can notify you" : "BLOCKED: reminders may not appear", 18, true, ok ? AndroidUi.GREEN : AndroidUi.RED));
        b.addView(AndroidUi.body(this, (notificationsOk ? "Notifications allowed" : "Notifications blocked") + " · " + (alarmsOk ? "Exact alarms allowed" : "Exact alarms blocked or restricted")));
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
        metrics.addView(AndroidUi.metric(this, "done", String.valueOf(stats.completedToday), AndroidUi.GREEN));
        metrics.addView(AndroidUi.metric(this, "snoozed", String.valueOf(stats.snoozedToday), AndroidUi.ORANGE));
        root.addView(metrics);
        if (tasks.isEmpty()) {
            LinearLayout empty = AndroidUi.card(this);
            empty.addView(AndroidUi.text(this, "No reminders are active", 20, true, AndroidUi.INK));
            empty.addView(AndroidUi.body(this, "Create your first task and schedule it. Until then there is nothing to notify you about."));
            Button add = AndroidUi.button(this, "Create first task");
            add.setOnClickListener(v -> openEdit(new ReminderTask()));
            empty.addView(add);
            root.addView(empty);
            return;
        }
        ReminderTask next = tasks.get(0);
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
            if (shown >= 4) break;
            root.addView(taskCard(t, false));
            shown++;
        }
    }

    private void renderManage() {
        root.addView(AndroidUi.section(this, "Manage tasks"));
        Button add = AndroidUi.button(this, "Add new reminder task");
        add.setOnClickListener(v -> openEdit(new ReminderTask()));
        root.addView(add);
        ArrayList<ReminderTask> tasks = sortedTasks(false);
        if (tasks.isEmpty()) {
            LinearLayout empty = AndroidUi.card(this);
            empty.addView(AndroidUi.text(this, "No saved tasks", 20, true, AndroidUi.INK));
            empty.addView(AndroidUi.body(this, "Use Add new reminder task to create a daily or one-shot reminder."));
            root.addView(empty);
            return;
        }
        for (ReminderTask t : tasks) root.addView(taskCard(t, true));
    }

    private View taskCard(ReminderTask t, boolean management) {
        LinearLayout box = AndroidUi.card(this);
        Calendar next = DayUtil.nextTime(t.hour, t.minute);
        box.addView(AndroidUi.text(this, t.title, 19, true, t.enabled ? AndroidUi.INK : AndroidUi.MUTED));
        box.addView(AndroidUi.body(this, (t.enabled ? "Enabled" : "Disabled") + " · " + (t.daily ? "Daily" : "One-shot") + " · " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(next.getTime()) + " · snooze " + t.defaultSnoozeMinutes + " min"));
        if (!t.enabled) box.addView(AndroidUi.small(this, "Actions disabled: this task is not scheduled. Use Edit schedule to enable it."));
        if (t.notes != null && !t.notes.trim().isEmpty()) box.addView(AndroidUi.small(this, t.notes));
        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        Button done = AndroidUi.button(this, "Complete today");
        done.setEnabled(t.enabled);
        done.setOnClickListener(v -> completeTask(t));
        primary.addView(done);
        Button snooze = AndroidUi.button(this, "Snooze");
        snooze.setEnabled(t.enabled);
        snooze.setOnClickListener(v -> snoozeTask(t));
        primary.addView(snooze);
        box.addView(primary);
        if (management) {
            LinearLayout secondary = new LinearLayout(this);
            secondary.setOrientation(LinearLayout.HORIZONTAL);
            Button edit = AndroidUi.button(this, "Edit schedule");
            edit.setOnClickListener(v -> openEdit(t));
            secondary.addView(edit);
            Button del = AndroidUi.button(this, "Delete task");
            del.setOnClickListener(v -> confirmDelete(t));
            secondary.addView(del);
            box.addView(secondary);
        }
        return box;
    }

    private void renderEdit() {
        if (draft == null) draft = new ReminderTask();
        root.addView(AndroidUi.section(this, draft.title == null || draft.title.equals("New task") ? "Create reminder" : "Edit selected reminder"));
        LinearLayout context = AndroidUi.banner(this, AndroidUi.BLUE);
        context.addView(AndroidUi.text(this, "Selected task", 14, true, AndroidUi.BLUE));
        context.addView(AndroidUi.body(this, (draft.title == null || draft.title.trim().isEmpty() ? "Untitled task" : draft.title) + " · " + String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute) + " · " + (draft.daily ? "Daily" : "One-shot")));
        context.addView(AndroidUi.small(this, "Configure one object here. Focused pickers are used for time, repeat mode, and snooze duration."));
        root.addView(context);

        LinearLayout card = AndroidUi.card(this);
        EditText title = input(draft.title); card.addView(label("Task name")); card.addView(title);
        EditText notes = input(draft.notes); notes.setMinLines(3); card.addView(label("Notification notes")); card.addView(notes);
        card.addView(summaryRow("Due time", String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute), "Choose due time", v -> showTimePicker(title, notes)));
        card.addView(summaryRow("Repeat", draft.daily ? "Daily" : "One-shot", "Choose repeat mode", v -> showRepeatPicker(title, notes)));
        card.addView(summaryRow("Snooze", draft.defaultSnoozeMinutes + " minutes", "Choose snooze", v -> showSnoozePicker(title, notes)));
        CheckBox enabled = new CheckBox(this); enabled.setText("Enabled and scheduled"); enabled.setChecked(draft.enabled); card.addView(enabled);
        TextView validation = AndroidUi.small(this, "Save is enabled when the task has a name and a snooze duration of at least one minute.");
        card.addView(validation);
        Button save = AndroidUi.button(this, "Save and schedule");
        save.setOnClickListener(v -> saveDraft(title, notes, enabled));
        card.addView(save);
        Button cancel = AndroidUi.button(this, "Cancel editing");
        cancel.setOnClickListener(v -> { draft = null; mode = MODE_MANAGE; feedback = "Editing cancelled. No reminder changed."; render(); });
        card.addView(cancel);
        root.addView(card);
    }

    private View summaryRow(String label, String value, String action, View.OnClickListener click) {
        LinearLayout box = AndroidUi.card(this);
        box.addView(AndroidUi.text(this, label, 14, true, AndroidUi.MUTED));
        box.addView(AndroidUi.text(this, value, 20, true, AndroidUi.INK));
        Button b = AndroidUi.button(this, action);
        b.setOnClickListener(click);
        box.addView(b);
        return box;
    }

    private void captureDraft(EditText title, EditText notes) {
        draft.title = title.getText().toString().trim();
        draft.notes = notes.getText().toString();
    }

    private void showTimePicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        TimePickerDialog d = new TimePickerDialog(this, (view, hour, minute) -> { draft.hour = hour; draft.minute = minute; feedback = "Due time selected: " + String.format(Locale.US, "%02d:%02d", hour, minute); render(); }, draft.hour, draft.minute, true);
        d.setTitle("Choose due time");
        d.show();
    }

    private void showRepeatPicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        String[] choices = new String[]{"Daily", "One-shot"};
        int checked = draft.daily ? 0 : 1;
        new AlertDialog.Builder(this)
            .setTitle("Choose repeat mode")
            .setSingleChoiceItems(choices, checked, (dialog, which) -> { draft.daily = which == 0; feedback = "Repeat mode selected: " + choices[which]; dialog.dismiss(); render(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSnoozePicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        String[] choices = new String[]{"5 minutes", "10 minutes", "15 minutes", "30 minutes", "60 minutes", "Custom"};
        new AlertDialog.Builder(this)
            .setTitle("Choose default snooze")
            .setItems(choices, (dialog, which) -> {
                int[] vals = new int[]{5,10,15,30,60};
                if (which < vals.length) { draft.defaultSnoozeMinutes = vals[which]; feedback = "Snooze selected: " + vals[which] + " minutes"; render(); }
                else showCustomSnooze();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showCustomSnooze() {
        final EditText input = new EditText(this);
        input.setText(String.valueOf(draft.defaultSnoozeMinutes));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
            .setTitle("Custom snooze minutes")
            .setMessage("Enter a whole number of minutes. This affects future Snooze actions for this task.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use this snooze", (d, w) -> {
                try {
                    draft.defaultSnoozeMinutes = Math.max(1, Integer.parseInt(input.getText().toString().trim()));
                    feedback = "Custom snooze selected: " + draft.defaultSnoozeMinutes + " minutes";
                } catch (Exception e) {
                    feedback = "Cannot use custom snooze: enter a whole number of minutes.";
                }
                render();
            })
            .show();
    }

    private void saveDraft(EditText title, EditText notes, CheckBox enabled) {
        captureDraft(title, notes);
        if (draft.title == null || draft.title.trim().isEmpty() || draft.title.equals("Task")) {
            feedback = "Cannot save yet: enter a specific task name.";
            render();
            return;
        }
        if (draft.defaultSnoozeMinutes < 1) {
            feedback = "Cannot save yet: snooze must be at least one minute.";
            render();
            return;
        }
        draft.enabled = enabled.isChecked();
        store.upsert(draft);
        store.appendHistory(draft.id, "saved", draft.title + " at " + String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute));
        ReminderScheduler.cancelTask(this, draft.id);
        if (draft.enabled) ReminderScheduler.scheduleTask(this, draft, true);
        feedback = draft.enabled ? "Saved and scheduled: " + draft.title : "Saved disabled task: " + draft.title + ". Reason: Enabled and scheduled is off.";
        draft = null;
        mode = MODE_TODAY;
        render();
    }

    private void renderHistory(boolean raw) {
        HistoryStats s = historyStats();
        root.addView(AndroidUi.section(this, "History"));
        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(AndroidUi.metric(this, "done", String.valueOf(s.completedToday), AndroidUi.GREEN));
        metrics.addView(AndroidUi.metric(this, "snoozed", String.valueOf(s.snoozedToday), AndroidUi.ORANGE));
        metrics.addView(AndroidUi.metric(this, "missed", String.valueOf(s.missedToday), AndroidUi.RED));
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
        root.addView(AndroidUi.small(this, "Updated " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())));
        root.addView(trustBanner());
        if (feedback != null && !feedback.isEmpty()) root.addView(feedbackBanner());
        root.addView(modeNav());
    }

    private void completeTask(ReminderTask t) {
        ReminderScheduler.cancelTask(this, t.id);
        store.appendHistory(t.id, "completed_manual", t.title);
        if (t.daily) ReminderScheduler.scheduleTask(this, t, true);
        feedback = "Completed and logged: " + t.title;
        render();
    }

    private void snoozeTask(ReminderTask t) {
        ReminderScheduler.scheduleSnooze(this, t, t.defaultSnoozeMinutes);
        feedback = "Snoozed " + t.title + " for " + t.defaultSnoozeMinutes + " minutes.";
        render();
    }

    private void confirmDelete(ReminderTask t) {
        new AlertDialog.Builder(this)
            .setTitle("Delete task?")
            .setMessage("This removes future reminders for " + t.title + ". Existing history stays in the app-private log. Scheduled alarms for this task will be cancelled.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete task", (d, w) -> { ReminderScheduler.cancelTask(this, t.id); store.delete(t.id); feedback = "Deleted task and cancelled future reminders: " + t.title; render(); })
            .show();
    }

    private void openEdit(ReminderTask task) {
        draft = copyTask(task);
        if (task.title == null || task.title.equals("New task")) draft.title = "";
        mode = MODE_EDIT;
        feedback = draft.title == null || draft.title.trim().isEmpty() ? "Creating a new selected task." : "Editing selected task: " + draft.title;
        render();
    }

    private ReminderTask copyTask(ReminderTask t) {
        ReminderTask c = new ReminderTask();
        c.id = t.id; c.title = t.title; c.notes = t.notes; c.hour = t.hour; c.minute = t.minute; c.daily = t.daily; c.enabled = t.enabled; c.defaultSnoozeMinutes = t.defaultSnoozeMinutes; c.createdAt = t.createdAt; c.lastScheduledAt = t.lastScheduledAt;
        return c;
    }

    private ArrayList<ReminderTask> sortedTasks(boolean enabledOnly) {
        ArrayList<ReminderTask> out = new ArrayList<>();
        for (ReminderTask t : store.loadTasks()) if (!enabledOnly || t.enabled) out.add(t);
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
    private static class HistoryStats { int completedToday; int snoozedToday; int missedToday; String recentText; }
}
