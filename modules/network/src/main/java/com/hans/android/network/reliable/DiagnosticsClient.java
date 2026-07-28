package com.hans.android.network.reliable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class DiagnosticsClient {
    public static final class Result {
        public final int acceptedCount;
        public final Set<String> acceptedEventIds;
        public final long serverReceivedWallMs;

        Result(int acceptedCount, Set<String> acceptedEventIds, long serverReceivedWallMs) {
            this.acceptedCount = acceptedCount;
            this.acceptedEventIds = acceptedEventIds;
            this.serverReceivedWallMs = serverReceivedWallMs;
        }
    }

    private final String baseUrl;
    private final String userAgent;
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    public DiagnosticsClient(String baseUrl, String userAgent) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("Server URL is empty");
        this.baseUrl = value;
        this.userAgent = userAgent == null || userAgent.trim().isEmpty()
                ? "VoiceButton Android" : userAgent.trim();
    }

    public Result send(String installationId, JSONArray events) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("installation_id", installationId);
        payload.put("events", events);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        checkInterrupted();
        HttpURLConnection connection = (HttpURLConnection) new URL(
                baseUrl + "/diagnostics/v1/events").openConnection();
        activeConnection.set(connection);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                checkInterrupted();
                out.write(bytes);
            }
            checkInterrupted();
            int code = connection.getResponseCode();
            byte[] responseBytes = readAll(code >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            String responseText = new String(responseBytes, StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                String message = responseText;
                try { message = new JSONObject(responseText).optString("error", responseText); }
                catch (Exception ignored) {}
                throw new ReliableUploadClient.ProtocolException(code,
                        "Diagnostics HTTP " + code + ": " + message);
            }
            JSONObject response = new JSONObject(responseText);
            if (!response.optBoolean("durable", false)) {
                throw new IllegalStateException("Server did not confirm durable diagnostic storage");
            }
            JSONArray accepted = response.optJSONArray("accepted_event_ids");
            Set<String> ids = new HashSet<>();
            if (accepted != null) for (int i = 0; i < accepted.length(); i++) {
                ids.add(accepted.getString(i));
            }
            return new Result(response.optInt("accepted_count", ids.size()), ids,
                    response.optLong("server_received_wall_ms", 0L));
        } finally {
            activeConnection.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    public void cancelActiveRequest() {
        HttpURLConnection connection = activeConnection.getAndSet(null);
        if (connection != null) connection.disconnect();
    }

    private static byte[] readAll(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkInterrupted();
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Diagnostic transmission cancelled because Voice Button was closed");
        }
    }

}
