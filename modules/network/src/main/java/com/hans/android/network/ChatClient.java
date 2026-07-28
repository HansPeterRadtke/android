package com.hans.android.network;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class ChatClient {
    private final String baseUrl;

    public ChatClient(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Server URL is empty");
        this.baseUrl = normalized;
    }

    public JSONObject poll(String sessionId) throws Exception {
        HttpURLConnection connection = open("/chat/poll?sid=" + encode(sessionId), "GET");
        int code = connection.getResponseCode();
        byte[] body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (code < 200 || code >= 300) throw new IllegalStateException("Chat poll HTTP " + code);
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    public File downloadAudio(String sessionId, String audioId, File target) throws Exception {
        HttpURLConnection connection = open("/chat/audio?sid=" + encode(sessionId) + "&audio=" + encode(audioId), "GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            readAll(connection.getErrorStream());
            throw new IllegalStateException("Audio download HTTP " + code);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Could not create local chat audio folder");
        }
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
             FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            fileOut.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Could not replace local reply audio");
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IllegalStateException("Could not publish local reply audio");
        }
        return target;
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod(method);
        return connection;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    private static byte[] readAll(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
