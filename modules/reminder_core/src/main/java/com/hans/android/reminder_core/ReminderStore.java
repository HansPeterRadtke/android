package com.hans.android.reminder_core;

import android.content.Context;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class ReminderStore {
    private final Context context;
    private final File tasksFile;
    private final File historyFile;

    public ReminderStore(Context context) {
        this.context = context.getApplicationContext();
        this.tasksFile = new File(this.context.getFilesDir(), "tasks.json");
        this.historyFile = new File(this.context.getFilesDir(), "task_history.log");
    }

    public synchronized ArrayList<ReminderTask> loadTasks() {
        ArrayList<ReminderTask> out = new ArrayList<>();
        try {
            if (!tasksFile.exists()) return out;
            String s = readFile(tasksFile);
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) out.add(ReminderTask.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            appendHistory(0, "store_error", "Failed to load tasks: " + e);
        }
        return out;
    }

    public synchronized void saveTasks(List<ReminderTask> tasks) {
        try {
            JSONArray arr = new JSONArray();
            for (ReminderTask t : tasks) arr.put(t.toJson());
            writeFile(tasksFile, arr.toString(2));
        } catch (Exception e) {
            appendHistory(0, "store_error", "Failed to save tasks: " + e);
        }
    }

    public synchronized ReminderTask findTask(long id) {
        for (ReminderTask t : loadTasks()) if (t.id == id) return t;
        return null;
    }

    public synchronized void upsert(ReminderTask task) {
        ArrayList<ReminderTask> tasks = loadTasks();
        boolean found = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id == task.id) {
                tasks.set(i, task);
                found = true;
                break;
            }
        }
        if (!found) tasks.add(task);
        saveTasks(tasks);
    }

    public synchronized void delete(long id) {
        ArrayList<ReminderTask> tasks = loadTasks();
        ArrayList<ReminderTask> next = new ArrayList<>();
        for (ReminderTask t : tasks) if (t.id != id) next.add(t);
        saveTasks(next);
        appendHistory(id, "deleted", "Task deleted");
    }

    public synchronized void appendHistory(long taskId, String action, String detail) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String line = ts + "\t" + day + "\t" + taskId + "\t" + action + "\t" + detail.replace('\n', ' ') + "\n";
            FileOutputStream out = new FileOutputStream(historyFile, true);
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {}
    }

    public synchronized String readHistory() {
        try {
            if (!historyFile.exists()) return "No history yet.";
            return readFile(historyFile);
        } catch (Exception e) {
            return "Failed to read history: " + e;
        }
    }

    public File historyFile() { return historyFile; }

    private static String readFile(File f) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bout.write(buf, 0, n);
        in.close();
        return bout.toString("UTF-8");
    }

    private static void writeFile(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.close();
    }
}
