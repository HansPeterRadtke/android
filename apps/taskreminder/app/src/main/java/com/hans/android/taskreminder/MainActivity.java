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
        handleLaunchIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        if (root != null) render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (root != null) render();
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_SNOOZE_CHOICE, false)) return;
        long id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1);
        ReminderTask task = store.findTask(id);
        intent.removeExtra(ReminderScheduler.EXTRA_OPEN_SNOOZE_CHOICE);
        intent.removeExtra(ReminderScheduler.EXTRA_TASK_ID);
        if (task != null) showSnoozeNowDialog(task);
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
        if (mode == MODE_TODAY) { renderActiveOccurrenceActions(); renderToday(); }
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


    private void renderActiveOccurrenceActions() {
        ArrayList<ReminderTask> open = new ArrayList<>();
        for (ReminderTask t : store.loadTasks()) if (t.enabled && t.openOccurrenceDueAt > 0) open.add(t);
        if (open.isEmpty()) return;
        root.addView(AndroidUi.section(this, "Active reminder"));
        for (ReminderTask t : open) {
            LinearLayout box = AndroidUi.banner(this, AndroidUi.ORANGE);
            box.addView(AndroidUi.text(this, t.title, 20, true, AndroidUi.INK));
            box.addView(AndroidUi.body(this, "Due " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(new Date(t.openOccurrenceDueAt)) + " · snoozed " + t.openSnoozeCount + " · carried " + t.pendingStackCount));
            box.addView(actionButton("Complete now", "Log this occurrence as done and schedule the next one.", v -> completeTask(t)));
            box.addView(actionButton(snoozeActionTitle(t), t.chooseSnoozeEachTime() ? "Choose the snooze duration now." : "Keep this occurrence open and remind again after the configured snooze.", v -> handleSnooze(t)));
            box.addView(actionButton("Choose not-done outcome", "Dismiss, carry forward, skip, or mark not done with a clear consequence.", v -> showOccurrenceOutcomeDialog(t)));
            root.addView(box);
        }
    }


    private View actionButton(String title, String reason, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        Button b = AndroidUi.button(this, title);
        b.setOnClickListener(click);
        box.addView(b);
        if (reason != null && !reason.trim().isEmpty()) box.addView(AndroidUi.small(this, reason));
        return box;
    }

    private void showOccurrenceOutcomeDialog(ReminderTask t) {
        String[] choices = new String[]{
            "Dismiss only — close this occurrence and do not carry it forward",
            "Carry to next occurrence — stack one more pending occurrence",
            "Skip this occurrence — intentionally skip it this time",
            "Mark not done — count it as missed" + (t.stackMissedOccurrences ? " and carry it forward" : "")
        };
        new AlertDialog.Builder(this)
            .setTitle("Choose not-done outcome")
            .setItems(choices, (dialog, which) -> {
                if (which == 0) dismissTask(t);
                if (which == 1) carryTask(t);
                if (which == 2) skipTask(t);
                if (which == 3) notDoneTask(t);
            })
            .setNegativeButton("Cancel", null)
            .show();
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
        Calendar c = RepeatCalculator.nextDue(next, System.currentTimeMillis());
        hero.addView(AndroidUi.text(this, "Next reminder", 14, true, AndroidUi.BLUE));
        hero.addView(AndroidUi.text(this, next.title, 24, true, AndroidUi.INK));
        hero.addView(AndroidUi.body(this, "selected " + String.format(Locale.US, "%02d:%02d", next.hour, next.minute) + " · next " + new SimpleDateFormat("EEEE HH:mm", Locale.US).format(c.getTime()) + " · snooze " + next.defaultSnoozeMinutes + " min"));
        hero.addView(actionButton("Complete now", "Log this occurrence as done.", v -> completeTask(next)));
        hero.addView(actionButton(snoozeActionTitle(next), next.chooseSnoozeEachTime() ? "Choose the snooze duration when you press it." : "Remind again after the configured snooze.", v -> handleSnooze(next)));
        hero.addView(actionButton("Choose not-done outcome", "Dismiss, carry forward, skip, or mark not done.", v -> showOccurrenceOutcomeDialog(next)));
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
        Calendar next = RepeatCalculator.nextDue(t, System.currentTimeMillis());
        box.addView(AndroidUi.text(this, t.title, 19, true, t.enabled ? AndroidUi.INK : AndroidUi.MUTED));
        box.addView(AndroidUi.body(this, (t.enabled ? "Enabled" : "Disabled") + " · " + t.repeatSummary() + " · selected " + String.format(Locale.US, "%02d:%02d", t.hour, t.minute) + " · next " + new SimpleDateFormat("EEE HH:mm", Locale.US).format(next.getTime()) + " · snooze " + t.defaultSnoozeMinutes + " min"));
        if (!t.enabled) box.addView(AndroidUi.small(this, "Actions disabled: this task is not scheduled. Use Edit schedule to enable it."));
        box.addView(AndroidUi.small(this, "History totals: completed " + t.completedCount + " · dismissed " + t.dismissedCount + " · not completed " + t.missedCount + " · current snoozes " + t.openSnoozeCount + " · stack pending " + t.pendingStackCount));
        if (t.notes != null && !t.notes.trim().isEmpty()) box.addView(AndroidUi.small(this, t.notes));
        if (t.enabled) {
            box.addView(actionButton("Complete now", "Log this occurrence as done.", v -> completeTask(t)));
            box.addView(actionButton(snoozeActionTitle(t), t.chooseSnoozeEachTime() ? "Choose the snooze duration when you press it." : "Remind again after " + t.defaultSnoozeMinutes + " minutes.", v -> handleSnooze(t)));
            box.addView(actionButton("Choose not-done outcome", "Dismiss, carry forward, skip, or mark not done.", v -> showOccurrenceOutcomeDialog(t)));
        } else {
            box.addView(AndroidUi.small(this, "Actions disabled: enable and schedule this task first."));
        }
        if (management) {
            box.addView(actionButton("Edit schedule", "Change repeat, time, snooze, stacking, or notification behavior.", v -> openEdit(t)));
            box.addView(actionButton("Delete task", "Cancel future reminders. History stays in the log.", v -> confirmDelete(t)));
        }
        return box;
    }

    private void renderEdit() {
        if (draft == null) draft = new ReminderTask();
        root.addView(AndroidUi.section(this, draft.title == null || draft.title.equals("New task") ? "Create reminder" : "Edit selected reminder"));
        LinearLayout context = AndroidUi.banner(this, AndroidUi.BLUE);
        context.addView(AndroidUi.text(this, "Selected task", 14, true, AndroidUi.BLUE));
        context.addView(AndroidUi.body(this, (draft.title == null || draft.title.trim().isEmpty() ? "Untitled task" : draft.title) + " · " + String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute) + " · " + draft.repeatSummary()));
        context.addView(AndroidUi.small(this, "Configure one object here. Focused pickers are used for time, repeat mode, and snooze duration."));
        root.addView(context);

        LinearLayout card = AndroidUi.card(this);
        EditText title = input(draft.title); card.addView(label("Task name")); card.addView(title);
        EditText notes = input(draft.notes); notes.setMinLines(3); card.addView(label("Notification notes")); card.addView(notes);
        card.addView(summaryRow("Due time", String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute), "Choose due time", v -> showTimePicker(title, notes)));
        card.addView(summaryRow("Repeat", draft.repeatSummary(), "Choose repeat mode", v -> showRepeatPicker(title, notes)));
        card.addView(summaryRow("Snooze behavior", draft.snoozeSummary(), "Choose snooze behavior", v -> showSnoozeBehaviorPicker(title, notes)));
        card.addView(summaryRow("Missed and dismissed stacking", draft.stackSummary(), "Choose stacking", v -> showStackPicker(title, notes)));
        card.addView(summaryRow("Notification actions", draft.notificationActionSummary(), "Choose notification actions", v -> showNotificationActionPicker(title, notes)));
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
        String[] choices = new String[]{"Hourly", "Daily", "Weekly", "Monthly", "Every N days", "Every N hours", "Custom interval", "One-shot"};
        String[] modes = new String[]{ReminderTask.REPEAT_HOURLY, ReminderTask.REPEAT_DAILY, ReminderTask.REPEAT_WEEKLY, ReminderTask.REPEAT_MONTHLY, ReminderTask.REPEAT_EVERY_N_DAYS, ReminderTask.REPEAT_EVERY_N_HOURS, ReminderTask.REPEAT_CUSTOM_INTERVAL, ReminderTask.REPEAT_ONCE};
        int checked = 1;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(draft.repeatMode)) checked = i;
        new AlertDialog.Builder(this)
            .setTitle("Choose repeat pattern")
            .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                draft.repeatMode = modes[which];
                draft.daily = ReminderTask.REPEAT_DAILY.equals(draft.repeatMode);
                if (ReminderTask.REPEAT_HOURLY.equals(draft.repeatMode)) { draft.intervalHours = 1; draft.intervalDays = 0; draft.intervalMinutes = 0; }
                if (ReminderTask.REPEAT_WEEKLY.equals(draft.repeatMode) && draft.weekdaysMask == 0) draft.weekdaysMask = 1 << (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1);
                if (ReminderTask.REPEAT_MONTHLY.equals(draft.repeatMode) && draft.dayOfMonth < 1) draft.dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
                if (ReminderTask.REPEAT_EVERY_N_DAYS.equals(draft.repeatMode) && draft.intervalDays < 1) draft.intervalDays = 1;
                if (ReminderTask.REPEAT_EVERY_N_HOURS.equals(draft.repeatMode) && draft.intervalHours < 1) draft.intervalHours = 1;
                if (ReminderTask.REPEAT_CUSTOM_INTERVAL.equals(draft.repeatMode)) { draft.intervalDays = 0; draft.intervalHours = 0; draft.intervalMinutes = 15; }
                feedback = "Repeat pattern selected: " + draft.repeatSummary();
                dialog.dismiss();
                if (ReminderTask.REPEAT_WEEKLY.equals(draft.repeatMode)) showWeekdayPicker(title, notes);
                else if (ReminderTask.REPEAT_MONTHLY.equals(draft.repeatMode)) showMonthDayPicker(title, notes);
                else if (ReminderTask.REPEAT_EVERY_N_DAYS.equals(draft.repeatMode) || ReminderTask.REPEAT_EVERY_N_HOURS.equals(draft.repeatMode) || ReminderTask.REPEAT_CUSTOM_INTERVAL.equals(draft.repeatMode)) showIntervalEditor(title, notes);
                else render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showWeekdayPicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        String[] labels = new String[]{"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        boolean[] checked = new boolean[7];
        for (int i = 0; i < 7; i++) checked[i] = (draft.weekdaysMask & (1 << i)) != 0;
        new AlertDialog.Builder(this)
            .setTitle("Choose weekdays")
            .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                if (isChecked) draft.weekdaysMask |= (1 << which); else draft.weekdaysMask &= ~(1 << which);
            })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use weekdays", (dialog, which) -> {
                if (draft.weekdaysMask == 0) draft.weekdaysMask = 1 << (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1);
                feedback = "Weekly repeat selected: " + draft.weekdaySummary();
                render();
            })
            .show();
    }

    private void showMonthDayPicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(String.valueOf(Math.max(1, Math.min(31, draft.dayOfMonth))));
        new AlertDialog.Builder(this)
            .setTitle("Choose day of month")
            .setMessage("Use 1 to 31. Months with fewer days use the last valid day.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use day", (dialog, which) -> {
                try { draft.dayOfMonth = Math.max(1, Math.min(31, Integer.parseInt(input.getText().toString().trim()))); feedback = "Monthly repeat selected: day " + draft.dayOfMonth; }
                catch (Exception e) { feedback = "Cannot use month day: enter a number from 1 to 31."; }
                render();
            })
            .show();
    }

    private void showIntervalEditor(EditText title, EditText notes) {
        captureDraft(title, notes);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(AndroidUi.dp(this, 12), AndroidUi.dp(this, 8), AndroidUi.dp(this, 12), AndroidUi.dp(this, 4));
        EditText days = input(String.valueOf(Math.max(0, draft.intervalDays))); days.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText hours = input(String.valueOf(Math.max(0, draft.intervalHours))); hours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        EditText minutes = input(String.valueOf(Math.max(0, draft.intervalMinutes))); minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (ReminderTask.REPEAT_EVERY_N_DAYS.equals(draft.repeatMode)) { hours.setText("0"); minutes.setText("0"); }
        if (ReminderTask.REPEAT_EVERY_N_HOURS.equals(draft.repeatMode)) { days.setText("0"); minutes.setText("0"); }
        form.addView(label("Days")); form.addView(days);
        form.addView(label("Hours")); form.addView(hours);
        form.addView(label("Minutes")); form.addView(minutes);
        new AlertDialog.Builder(this)
            .setTitle("Choose repeat interval")
            .setMessage("Examples: every 23 days, or every 16 hours and 25 minutes. At least one value must be greater than zero.")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use interval", (dialog, which) -> {
                try {
                    draft.intervalDays = Math.max(0, Integer.parseInt(days.getText().toString().trim()));
                    draft.intervalHours = Math.max(0, Integer.parseInt(hours.getText().toString().trim()));
                    draft.intervalMinutes = Math.max(0, Integer.parseInt(minutes.getText().toString().trim()));
                    if (ReminderTask.REPEAT_EVERY_N_DAYS.equals(draft.repeatMode) && draft.intervalDays < 1) draft.intervalDays = 1;
                    if (ReminderTask.REPEAT_EVERY_N_HOURS.equals(draft.repeatMode) && draft.intervalHours < 1) draft.intervalHours = 1;
                    if (draft.intervalDays == 0 && draft.intervalHours == 0 && draft.intervalMinutes == 0) draft.intervalHours = 1;
                    feedback = "Repeat interval selected: " + draft.repeatSummary();
                } catch (Exception e) { feedback = "Cannot use interval: enter whole numbers only."; }
                render();
            })
            .show();
    }


    private void showStackPicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        String[] choices = new String[]{
            "Do not carry missed/not-done forward automatically",
            "Carry missed/not-done forward automatically"
        };
        int checked = draft.stackMissedOccurrences ? 1 : 0;
        new AlertDialog.Builder(this)
            .setTitle("Choose stacking behavior")
            .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                draft.stackMissedOccurrences = which == 1;
                feedback = "Stacking selected: " + draft.stackSummary();
                dialog.dismiss();
                render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }



    private void showNotificationActionPicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        String[] choices = new String[]{
            "Fast: Complete · Snooze · Dismiss",
            "Dismiss choice: Complete · Dismiss · Carry to next",
            "Snooze choice: Complete · Choose snooze · Dismiss"
        };
        int checked = 0;
        if (ReminderTask.ACTION_PROFILE_DISMISS_CHOICE.equals(draft.notificationActionProfile)) checked = 1;
        if (ReminderTask.ACTION_PROFILE_SNOOZE_CHOICE.equals(draft.notificationActionProfile)) checked = 2;
        new AlertDialog.Builder(this)
            .setTitle("Choose notification actions")
            .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                if (which == 0) draft.notificationActionProfile = ReminderTask.ACTION_PROFILE_FAST;
                if (which == 1) draft.notificationActionProfile = ReminderTask.ACTION_PROFILE_DISMISS_CHOICE;
                if (which == 2) draft.notificationActionProfile = ReminderTask.ACTION_PROFILE_SNOOZE_CHOICE;
                feedback = "Notification actions selected: " + draft.notificationActionSummary();
                dialog.dismiss();
                render();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSnoozeBehaviorPicker(EditText title, EditText notes) {
        captureDraft(title, notes);
        String[] choices = new String[]{"Fixed 5 minutes", "Fixed 10 minutes", "Fixed 15 minutes", "Fixed 30 minutes", "Fixed 60 minutes", "Fixed custom minutes", "Ask every time I snooze"};
        int checked = draft.chooseSnoozeEachTime() ? 6 : -1;
        int[] vals = new int[]{5,10,15,30,60};
        for (int i = 0; i < vals.length; i++) if (!draft.chooseSnoozeEachTime() && draft.defaultSnoozeMinutes == vals[i]) checked = i;
        if (checked < 0) checked = 5;
        new AlertDialog.Builder(this)
            .setTitle("Choose snooze behavior")
            .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                if (which < vals.length) {
                    draft.snoozeMode = ReminderTask.SNOOZE_FIXED;
                    draft.defaultSnoozeMinutes = vals[which];
                    feedback = "Snooze selected: fixed " + draft.defaultSnoozeMinutes + " minutes";
                    dialog.dismiss();
                    render();
                } else if (which == 5) {
                    dialog.dismiss();
                    showCustomDefaultSnooze();
                } else {
                    draft.snoozeMode = ReminderTask.SNOOZE_CHOOSE_EACH_TIME;
                    feedback = "Snooze selected: ask every time";
                    dialog.dismiss();
                    render();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showCustomDefaultSnooze() {
        final EditText input = new EditText(this);
        input.setText(String.valueOf(draft.defaultSnoozeMinutes));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
            .setTitle("Fixed snooze minutes")
            .setMessage("Enter the fixed snooze duration for this task.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use fixed snooze", (d, w) -> {
                try {
                    draft.snoozeMode = ReminderTask.SNOOZE_FIXED;
                    draft.defaultSnoozeMinutes = Math.max(1, Integer.parseInt(input.getText().toString().trim()));
                    feedback = "Snooze selected: fixed " + draft.defaultSnoozeMinutes + " minutes";
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
        if (ReminderTask.REPEAT_CUSTOM_INTERVAL.equals(draft.repeatMode) && draft.intervalDays == 0 && draft.intervalHours == 0 && draft.intervalMinutes == 0) {
            feedback = "Cannot save yet: custom repeat interval needs at least one day, hour, or minute.";
            render();
            return;
        }
        if (draft.defaultSnoozeMinutes < 1) {
            feedback = "Cannot save yet: snooze must be at least one minute.";
            render();
            return;
        }
        draft.enabled = enabled.isChecked();
        draft.daily = ReminderTask.REPEAT_DAILY.equals(draft.repeatMode);
        draft.openOccurrenceDueAt = 0;
        draft.openSnoozeCount = 0;
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
        t.completedCount += 1;
        store.appendHistory(t.id, "completed_manual", "Completed from app · occurrence due " + new Date(t.openOccurrenceDueAt) + " · snoozes " + t.openSnoozeCount);
        t.openOccurrenceDueAt = 0;
        t.openSnoozeCount = 0;
        if (ReminderTask.REPEAT_ONCE.equals(t.repeatMode)) t.enabled = false;
        store.upsert(t);
        if (!ReminderTask.REPEAT_ONCE.equals(t.repeatMode) && t.enabled) ReminderScheduler.scheduleTask(this, t, true);
        feedback = "Completed and logged: " + t.title;
        render();
    }


    private String snoozeActionTitle(ReminderTask t) {
        return t.chooseSnoozeEachTime() ? "Choose snooze" : "Snooze " + t.defaultSnoozeMinutes + " min";
    }

    private void handleSnooze(ReminderTask t) {
        if (t.chooseSnoozeEachTime()) showSnoozeNowDialog(t);
        else snoozeTask(t, t.defaultSnoozeMinutes);
    }

    private void snoozeTask(ReminderTask t, int minutes) {
        ReminderScheduler.scheduleSnooze(this, t, minutes);
        feedback = "Snoozed " + t.title + " for " + Math.max(1, minutes) + " minutes.";
        render();
    }

    private void showSnoozeNowDialog(ReminderTask t) {
        String[] choices = new String[]{"5 minutes", "10 minutes", "15 minutes", "30 minutes", "60 minutes", "Custom"};
        int[] vals = new int[]{5,10,15,30,60};
        new AlertDialog.Builder(this)
            .setTitle("Snooze this occurrence")
            .setItems(choices, (dialog, which) -> {
                if (which < vals.length) snoozeTask(t, vals[which]);
                else showCustomSnoozeNow(t);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showCustomSnoozeNow(ReminderTask t) {
        final EditText input = new EditText(this);
        input.setText(String.valueOf(t.defaultSnoozeMinutes));
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
            .setTitle("Snooze minutes")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Snooze", (d, w) -> {
                try { snoozeTask(t, Math.max(1, Integer.parseInt(input.getText().toString().trim()))); }
                catch (Exception e) { feedback = "Cannot snooze: enter whole minutes."; render(); }
            })
            .show();
    }

    private void snoozeTask(ReminderTask t) {
        snoozeTask(t, t.defaultSnoozeMinutes);
    }

    private void dismissTask(ReminderTask t) {
        ReminderScheduler.cancelTask(this, t.id);
        t.dismissedCount += 1;
        store.appendHistory(t.id, "dismissed_manual", "Dismissed from app · occurrence due " + new Date(t.openOccurrenceDueAt) + " · snoozes " + t.openSnoozeCount);
        t.openOccurrenceDueAt = 0;
        t.openSnoozeCount = 0;
        if (ReminderTask.REPEAT_ONCE.equals(t.repeatMode)) t.enabled = false;
        store.upsert(t);
        if (!ReminderTask.REPEAT_ONCE.equals(t.repeatMode) && t.enabled) ReminderScheduler.scheduleTask(this, t, true);
        feedback = "Dismissed without completion: " + t.title;
        render();
    }



    private void carryTask(ReminderTask t) {
        ReminderScheduler.cancelTask(this, t.id);
        t.pendingStackCount += 1;
        t.dismissedCount += 1;
        store.appendHistory(t.id, "carried_forward_manual", "Carried this occurrence to the next event · due " + new Date(t.openOccurrenceDueAt) + " · pending stack " + t.pendingStackCount);
        t.openOccurrenceDueAt = 0;
        t.openSnoozeCount = 0;
        store.upsert(t);
        if (!ReminderTask.REPEAT_ONCE.equals(t.repeatMode) && t.enabled) ReminderScheduler.scheduleTask(this, t, true);
        feedback = "Carried to next occurrence. Pending stack: " + t.pendingStackCount;
        render();
    }

    private void skipTask(ReminderTask t) {
        ReminderScheduler.cancelTask(this, t.id);
        t.dismissedCount += 1;
        store.appendHistory(t.id, "skipped_manual", "Skipped this occurrence from app · due " + new Date(t.openOccurrenceDueAt) + " · snoozes " + t.openSnoozeCount + ". This was intentional, not completed.");
        t.openOccurrenceDueAt = 0;
        t.openSnoozeCount = 0;
        if (ReminderTask.REPEAT_ONCE.equals(t.repeatMode)) t.enabled = false;
        store.upsert(t);
        if (!ReminderTask.REPEAT_ONCE.equals(t.repeatMode) && t.enabled) ReminderScheduler.scheduleTask(this, t, true);
        feedback = "Skipped this occurrence and logged it separately from dismiss: " + t.title;
        render();
    }

    private void notDoneTask(ReminderTask t) {
        ReminderScheduler.cancelTask(this, t.id);
        t.missedCount += 1;
        if (t.stackMissedOccurrences) t.pendingStackCount += 1;
        store.appendHistory(t.id, "not_done_manual", "Marked not done from app · due " + new Date(t.openOccurrenceDueAt) + " · snoozes " + t.openSnoozeCount + (t.stackMissedOccurrences ? " · carried to stack " + t.pendingStackCount : ""));
        t.openOccurrenceDueAt = 0;
        t.openSnoozeCount = 0;
        if (ReminderTask.REPEAT_ONCE.equals(t.repeatMode)) t.enabled = false;
        store.upsert(t);
        if (!ReminderTask.REPEAT_ONCE.equals(t.repeatMode) && t.enabled) ReminderScheduler.scheduleTask(this, t, true);
        feedback = "Marked not done and logged as missed: " + t.title;
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
        c.id = t.id; c.title = t.title; c.notes = t.notes; c.hour = t.hour; c.minute = t.minute; c.daily = t.daily; c.enabled = t.enabled; c.defaultSnoozeMinutes = t.defaultSnoozeMinutes; c.createdAt = t.createdAt; c.lastScheduledAt = t.lastScheduledAt; c.repeatMode = t.repeatMode; c.weekdaysMask = t.weekdaysMask; c.dayOfMonth = t.dayOfMonth; c.intervalDays = t.intervalDays; c.intervalHours = t.intervalHours; c.intervalMinutes = t.intervalMinutes; c.lastDueAt = t.lastDueAt; c.openOccurrenceDueAt = t.openOccurrenceDueAt; c.openSnoozeCount = t.openSnoozeCount; c.completedCount = t.completedCount; c.dismissedCount = t.dismissedCount; c.missedCount = t.missedCount; c.stackMissedOccurrences = t.stackMissedOccurrences; c.pendingStackCount = t.pendingStackCount; c.showCarryOverDismissAction = t.showCarryOverDismissAction; c.snoozeMode = t.snoozeMode; c.notificationActionProfile = t.notificationActionProfile;
        return c;
    }

    private ArrayList<ReminderTask> sortedTasks(boolean enabledOnly) {
        ArrayList<ReminderTask> out = new ArrayList<>();
        for (ReminderTask t : store.loadTasks()) if (!enabledOnly || t.enabled) out.add(t);
        Collections.sort(out, (a, b) -> Long.compare(RepeatCalculator.nextDue(a, System.currentTimeMillis()).getTimeInMillis(), RepeatCalculator.nextDue(b, System.currentTimeMillis()).getTimeInMillis()));
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
            if (line.contains("\t" + today + "\t") && (line.contains("\tmissed") || line.contains("\tauto_not_completed"))) s.missedToday++;
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
