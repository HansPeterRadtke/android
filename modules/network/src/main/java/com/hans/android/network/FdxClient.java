package com.hans.android.network;

import com.hans.android.audio.SpoolChunk;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLException;

public final class FdxClient {
    public static final class UploadResult {
        private final boolean accepted;
        private final int serverQueueDepth;

        UploadResult(boolean accepted, int serverQueueDepth) {
            this.accepted = accepted;
            this.serverQueueDepth = serverQueueDepth;
        }

        public boolean isAccepted() { return accepted; }
        public int getServerQueueDepth() { return serverQueueDepth; }
    }

    private final String baseUrl;

    public FdxClient(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Server URL is empty");
        this.baseUrl = normalized;
    }

    public UploadResult upload(SpoolChunk chunk) throws Exception {
        String urlText = baseUrl + "/chat/upload?sid=" + encode(chunk.getSessionId())
                + "&conversation=" + encode(chunk.getConversationId())
                + "&seq=" + chunk.getSequence()
                + "&final=" + (chunk.isFinalChunk() ? "1" : "0");
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "audio/wav");
        connection.setFixedLengthStreamingMode(chunk.getFile().length());
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(chunk.getFile()));
             BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        int code = connection.getResponseCode();
        byte[] body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (code < 200 || code >= 300) throw new ServerRejectedException(code);
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        boolean accepted = json.optBoolean("accepted", json.optBoolean("ok", false));
        if (!accepted) throw new ServerRejectedException(code);
        return new UploadResult(true, json.optInt("queue_depth", -1));
    }

    public static String humanError(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current instanceof UnknownHostException) return "Server name cannot be resolved; audio remains on this phone";
        if (current instanceof SocketTimeoutException) return "Server response timed out; audio remains queued for retry";
        if (current instanceof ConnectException) return "Server is unreachable; audio remains queued for retry";
        if (current instanceof SSLException) return "Secure server connection failed; audio remains queued for retry";
        if (current instanceof ServerRejectedException) return "Server rejected the upload; audio remains queued";
        return "Upload failed; audio remains queued for retry";
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
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

    private static final class ServerRejectedException extends Exception {
        ServerRejectedException(int code) { super("HTTP " + code); }
    }
}
