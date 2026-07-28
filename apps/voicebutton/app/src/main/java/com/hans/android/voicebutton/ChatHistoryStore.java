package com.hans.android.voicebutton;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatHistoryStore {
    public static final class Item {
        public final String sessionId;
        public final String selectedInput;
        public final long createdAt;
        public final String status;
        public final String userText;
        public final boolean userTextFinal;
        public final String assistantText;
        public final boolean assistantTextFinal;
        public final String userAudioPath;
        public final String assistantAudioPath;
        public final String assistantAudioId;
        public final String error;

        Item(Mutable value) {
            sessionId = value.sessionId;
            selectedInput = value.selectedInput;
            createdAt = value.createdAt;
            status = value.status;
            userText = value.userText;
            userTextFinal = value.userTextFinal;
            assistantText = value.assistantText;
            assistantTextFinal = value.assistantTextFinal;
            userAudioPath = value.userAudioPath;
            assistantAudioPath = value.assistantAudioPath;
            assistantAudioId = value.assistantAudioId;
            error = value.error;
        }

        public boolean isComplete() {
            return "complete".equals(status) && assistantTextFinal
                    && assistantAudioPath != null && !assistantAudioPath.isEmpty();
        }
    }

    private static final class Mutable {
        String sessionId;
        String selectedInput;
        long createdAt;
        String status = "recording";
        String userText = "Listening…";
        boolean userTextFinal;
        String assistantText = "";
        boolean assistantTextFinal;
        String userAudioPath = "";
        String assistantAudioPath = "";
        String assistantAudioId = "";
        String error = "";

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("session_id", sessionId);
            json.put("selected_input", selectedInput);
            json.put("created_at", createdAt);
            json.put("status", status);
            json.put("user_text", userText);
            json.put("user_text_final", userTextFinal);
            json.put("assistant_text", assistantText);
            json.put("assistant_text_final", assistantTextFinal);
            json.put("user_audio_path", userAudioPath);
            json.put("assistant_audio_path", assistantAudioPath);
            json.put("assistant_audio_id", assistantAudioId);
            json.put("error", error);
            return json;
        }

        static Mutable fromJson(JSONObject json) {
            Mutable value = new Mutable();
            value.sessionId = json.optString("session_id", "");
            value.selectedInput = json.optString("selected_input", "System default microphone");
            value.createdAt = json.optLong("created_at", System.currentTimeMillis());
            value.status = json.optString("status", "saved");
            value.userText = json.optString("user_text", "");
            value.userTextFinal = json.optBoolean("user_text_final", false);
            value.assistantText = json.optString("assistant_text", "");
            value.assistantTextFinal = json.optBoolean("assistant_text_final", false);
            value.userAudioPath = json.optString("user_audio_path", "");
            value.assistantAudioPath = json.optString("assistant_audio_path", "");
            value.assistantAudioId = json.optString("assistant_audio_id", "");
            value.error = json.optString("error", "");
            return value;
        }
    }

    private final File metadataFile;
    private final File audioRoot;
    private final LinkedHashMap<String, Mutable> items = new LinkedHashMap<>();

    public ChatHistoryStore(Context context) {
        File root = new File(context.getNoBackupFilesDir(), "voice_chat");
        if (!root.isDirectory()) root.mkdirs();
        metadataFile = new File(root, "history.json");
        audioRoot = new File(root, "audio");
        if (!audioRoot.isDirectory()) audioRoot.mkdirs();
        load();
    }

    public synchronized void create(String sessionId, String selectedInput) {
        Mutable value = new Mutable();
        value.sessionId = sessionId;
        value.selectedInput = selectedInput;
        value.createdAt = System.currentTimeMillis();
        value.status = "recording";
        items.put(sessionId, value);
        save();
    }

    public synchronized void setUserAudio(String sessionId, File file) {
        Mutable value = items.get(sessionId);
        if (value == null) return;
        value.userAudioPath = file == null ? "" : file.getAbsolutePath();
        if ("recording".equals(value.status)) value.status = "sending";
        save();
    }

    public synchronized void markSending(String sessionId) {
        Mutable value = items.get(sessionId);
        if (value == null) return;
        if ("complete".equals(value.status) || "sending".equals(value.status)) return;
        value.status = "sending";
        value.error = "";
        save();
    }

    public synchronized void markError(String sessionId, String error) {
        Mutable value = items.get(sessionId);
        if (value == null) return;
        String next = error == null ? "" : error;
        String nextStatus = "complete".equals(value.status) ? value.status : "waiting";
        if (next.equals(value.error) && nextStatus.equals(value.status)) return;
        value.error = next;
        value.status = nextStatus;
        save();
    }

    public synchronized boolean updateFromServer(String sessionId, JSONObject state) {
        Mutable value = items.get(sessionId);
        if (value == null || state == null) return false;
        String before = fingerprint(value);
        if (state.optBoolean("ok", false)) value.error = "";

        JSONObject active = state.optJSONObject("active");
        if (active != null) {
            String partial = active.optString("partial_text", "").trim();
            if (!partial.isEmpty() && !value.userTextFinal) value.userText = partial;
        }

        JSONArray messages = state.optJSONArray("messages");
        if (messages != null && messages.length() > 0) {
            JSONObject message = messages.optJSONObject(messages.length() - 1);
            if (message != null) {
                String user = message.optString("user_text", "").trim();
                if (!user.isEmpty()) value.userText = user;
                value.userTextFinal = message.optBoolean("user_text_final", value.userTextFinal);
                String assistant = message.optString("assistant_partial_text", "").trim();
                String finalAssistant = message.optString("assistant_text", "").trim();
                if (!finalAssistant.isEmpty()) assistant = finalAssistant;
                if (!assistant.isEmpty()) value.assistantText = assistant;
                value.assistantTextFinal = message.optBoolean("assistant_text_final", value.assistantTextFinal);
                value.assistantAudioId = message.optString("assistant_audio_id", value.assistantAudioId);
                value.status = message.optString("status", value.status);
                value.error = message.optString("error", value.error);
            }
        }

        boolean changed = !before.equals(fingerprint(value));
        if (changed) save();
        return changed;
    }

    public synchronized void setAssistantAudio(String sessionId, File file) {
        Mutable value = items.get(sessionId);
        if (value == null) return;
        value.assistantAudioPath = file == null ? "" : file.getAbsolutePath();
        if (value.assistantTextFinal) value.status = "complete";
        save();
    }

    public synchronized File assistantAudioTarget(String sessionId, String audioId) {
        File session = new File(audioRoot, sessionId);
        if (!session.isDirectory()) session.mkdirs();
        String safe = audioId == null ? "reply" : audioId.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(session, safe + ".wav");
    }

    public synchronized Item get(String sessionId) {
        Mutable value = items.get(sessionId);
        return value == null ? null : new Item(value);
    }

    public synchronized List<Item> snapshot() {
        List<Item> result = new ArrayList<>();
        for (Mutable value : items.values()) result.add(new Item(value));
        return result;
    }

    public synchronized List<String> incompleteSessionIds() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Mutable> entry : items.entrySet()) {
            if (!new Item(entry.getValue()).isComplete()) result.add(entry.getKey());
        }
        return result;
    }

    public synchronized boolean hasIncomplete() {
        for (Mutable value : items.values()) if (!new Item(value).isComplete()) return true;
        return false;
    }

    private void load() {
        File source = metadataFile.isFile() ? metadataFile : new File(metadataFile.getAbsolutePath() + ".bak");
        if (!source.isFile()) return;
        try {
            String text;
            try (FileInputStream in = new FileInputStream(source);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                Mutable value = Mutable.fromJson(array.getJSONObject(i));
                if (!value.sessionId.isEmpty()) items.put(value.sessionId, value);
            }
        } catch (Exception ignored) {
            items.clear();
        }
    }

    private synchronized void save() {
        try {
            JSONArray array = new JSONArray();
            for (Mutable value : items.values()) array.put(value.toJson());
            File temp = new File(metadataFile.getAbsolutePath() + ".tmp");
            File backup = new File(metadataFile.getAbsolutePath() + ".bak");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(array.toString(2).getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            if (backup.exists() && !backup.delete()) return;
            boolean hadOriginal = metadataFile.exists();
            if (hadOriginal && !metadataFile.renameTo(backup)) return;
            if (!temp.renameTo(metadataFile)) {
                if (hadOriginal) backup.renameTo(metadataFile);
                return;
            }
            if (backup.exists()) backup.delete();
        } catch (Exception ignored) {
            // Audio files remain valid even if metadata persistence temporarily fails.
        }
    }

    private static String fingerprint(Mutable value) {
        return value.status + "\n" + value.userText + "\n" + value.userTextFinal + "\n"
                + value.assistantText + "\n" + value.assistantTextFinal + "\n"
                + value.assistantAudioId + "\n" + value.error;
    }
}
