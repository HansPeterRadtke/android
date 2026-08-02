package com.hans.android.network.reliable;

import com.hans.android.audio.reliable.ReliableSessionManifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ReliableUploadClient {
    public static final int UPLOAD_PART_BYTES = 4096;

    public interface ProgressListener {
        void onProgress(long durableBytes, long totalBytes,
                        String serverId, long manifestRevision) throws Exception;
    }

    public static final class RemoteSegment {
        public final int seq;
        public final String sha256;
        public final long bytes;
        public final long receivedAtMs;
        public final long durableAtMs;

        RemoteSegment(int seq, String sha256, long bytes,
                      long receivedAtMs, long durableAtMs) {
            this.seq = seq; this.sha256 = sha256; this.bytes = bytes;
            this.receivedAtMs = receivedAtMs; this.durableAtMs = durableAtMs;
        }
    }

    public static final class Transcript {
        public final int seq;
        public final String state;
        public final String text;
        public final String engine;
        public final long createdAtMs;
        public final String error;

        Transcript(int seq, String state, String text, String engine,
                   long createdAtMs, String error) {
            this.seq = seq; this.state = state; this.text = text;
            this.engine = engine; this.createdAtMs = createdAtMs; this.error = error;
        }
    }

    public static final class Status {
        public final boolean committed;
        public final String serverId;
        public final long manifestRevision;
        public final Map<Integer, RemoteSegment> received;
        public final Map<Integer, Transcript> transcripts;

        Status(boolean committed, String serverId, long manifestRevision,
               Map<Integer, RemoteSegment> received,
               Map<Integer, Transcript> transcripts) {
            this.committed = committed; this.serverId = serverId;
            this.manifestRevision = manifestRevision;
            this.received = received; this.transcripts = transcripts;
        }
    }

    public static final class Ack {
        public final String serverId;
        public final long manifestRevision;
        public final long receivedAtMs;
        public final long durableAtMs;

        Ack(String serverId, long manifestRevision,
            long receivedAtMs, long durableAtMs) {
            this.serverId = serverId; this.manifestRevision = manifestRevision;
            this.receivedAtMs = receivedAtMs; this.durableAtMs = durableAtMs;
        }
    }

    public static final class ProtocolException extends Exception {
        public final int httpCode;
        public ProtocolException(int httpCode, String message) {
            super(message); this.httpCode = httpCode;
        }
    }

    private final String baseUrl;
    private final String userAgent;
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    public ReliableUploadClient(String baseUrl) {
        this(baseUrl, "VoiceButton/0.15 Android");
    }

    public ReliableUploadClient(String baseUrl, String userAgent) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("Server URL is empty");
        this.baseUrl = value;
        this.userAgent = userAgent;
    }

    public void createFolder(String folderId, String name) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("folder_id", folderId);
        payload.put("name", name);
        postJson("/audio/v2/folders", payload);
    }

    public Status status(ReliableSessionManifest manifest) throws Exception {
        JSONObject response = getJson("/audio/v2/status?folder=" + encode(manifest.folderId)
                + "&sid=" + encode(manifest.sessionId));
        LinkedHashMap<Integer, RemoteSegment> received = new LinkedHashMap<>();
        JSONArray array = response.optJSONArray("received");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            int seq = item.getInt("seq");
            received.put(seq, new RemoteSegment(seq,
                    item.optString("sha256", ""), item.optLong("bytes", 0L),
                    item.optLong("server_received_at_ms", 0L),
                    item.optLong("server_durable_at_ms", 0L)));
        }
        LinkedHashMap<Integer, Transcript> transcripts = new LinkedHashMap<>();
        JSONArray text = response.optJSONArray("transcripts");
        if (text != null) for (int i = 0; i < text.length(); i++) {
            JSONObject item = text.getJSONObject(i);
            int seq = item.getInt("seq");
            transcripts.put(seq, new Transcript(seq,
                    item.optString("state", "PENDING"),
                    item.optString("text", ""), item.optString("engine", ""),
                    item.optLong("created_at_ms", 0L), item.optString("error", "")));
        }
        return new Status(response.optBoolean("committed", false),
                response.optString("server_id", ""),
                response.optLong("manifest_revision", 0L), received, transcripts);
    }

    public Ack uploadSegment(ReliableSessionManifest manifest,
                             ReliableSessionManifest.Segment segment,
                             File mp3File,
                             ProgressListener progressListener) throws Exception {
        if (!mp3File.isFile() || mp3File.length() != segment.mp3Bytes) {
            throw new IllegalStateException("Local MP3 chunk changed before upload");
        }
        String metadata = "folder=" + encode(manifest.folderId)
                + "&sid=" + encode(manifest.sessionId)
                + "&conversation=" + encode(manifest.conversationId)
                + "&seq=" + segment.seq
                + "&sha256=" + encode(segment.sha256)
                + "&bytes=" + segment.mp3Bytes;
        JSONObject progress = getJson("/audio/v2/chunk-progress?" + metadata);
        long offset = validateProgress(progress, segment.mp3Bytes);
        notifyProgress(progressListener, offset, segment.mp3Bytes, progress);
        if (progress.optBoolean("complete", false)) {
            return ackFromCompleted(progress, segment);
        }

        try (RandomAccessFile input = new RandomAccessFile(mp3File, "r")) {
            while (offset < segment.mp3Bytes) {
                checkInterrupted();
                int length = nextPartLength(offset, segment.mp3Bytes);
                byte[] part = new byte[length];
                input.seek(offset);
                input.readFully(part);
                String partHash = ReliableSessionManifest.sha256(part);
                String path = "/audio/v2/chunk-part?" + metadata
                        + "&offset=" + offset
                        + "&part_sha256=" + encode(partHash)
                        + "&part_bytes=" + length
                        + "&duration_ms=" + segment.durationMs
                        + "&start_sample=" + segment.startSample
                        + "&end_sample=" + segment.endSample
                        + "&sample_rate=" + segment.sampleRate
                        + "&created_at_ms=" + segment.createdAtMs
                        + "&closed_at_ms=" + segment.closedAtMs
                        + "&durable_at_ms=" + segment.localDurableAtMs;
                JSONObject response = postBytes(path, part,
                        "application/octet-stream");
                long next = validateProgress(response, segment.mp3Bytes);
                if (next < offset + length && !response.optBoolean("complete", false)) {
                    throw new ProtocolException(409,
                            "Server did not durably advance the chunk offset");
                }
                offset = next;
                notifyProgress(progressListener, offset, segment.mp3Bytes, response);
                if (response.optBoolean("complete", false)) {
                    return ackFromCompleted(response, segment);
                }
            }
        }
        JSONObject completed = getJson("/audio/v2/chunk-progress?" + metadata);
        notifyProgress(progressListener, validateProgress(completed, segment.mp3Bytes),
                segment.mp3Bytes, completed);
        return ackFromCompleted(completed, segment);
    }

    static int nextPartLength(long offset, long totalBytes) {
        if (offset < 0L || totalBytes < 0L || offset >= totalBytes) return 0;
        return (int)Math.min((long)UPLOAD_PART_BYTES, totalBytes - offset);
    }

    private static long validateProgress(JSONObject response, long totalBytes)
            throws ProtocolException {
        if (!response.optBoolean("durable", false)) {
            throw new ProtocolException(409,
                    "Server did not confirm durable part storage");
        }
        long offset = response.optLong("durable_offset", -1L);
        long serverTotal = response.optLong("chunk_bytes", totalBytes);
        if (offset < 0L || offset > totalBytes || serverTotal != totalBytes) {
            throw new ProtocolException(409,
                    "Server returned an invalid durable byte offset");
        }
        return offset;
    }

    private static void notifyProgress(ProgressListener listener,
                                       long durableBytes, long totalBytes,
                                       JSONObject response) throws Exception {
        if (listener == null) return;
        listener.onProgress(durableBytes, totalBytes,
                response.optString("server_id", ""),
                response.optLong("manifest_revision", 0L));
    }

    private static Ack ackFromCompleted(JSONObject response,
                                        ReliableSessionManifest.Segment segment)
            throws ProtocolException {
        if (!response.optBoolean("complete", false)
                || !response.optBoolean("durable", false)
                || !segment.sha256.equals(response.optString("sha256", ""))
                || segment.mp3Bytes != response.optLong("bytes", -1L)
                || segment.mp3Bytes != response.optLong("durable_offset", -1L)) {
            throw new ProtocolException(409,
                    "Server completed-chunk acknowledgement did not match local bytes");
        }
        return new Ack(response.optString("server_id", ""),
                response.optLong("manifest_revision", 0L),
                response.optLong("server_received_at_ms", 0L),
                response.optLong("server_durable_at_ms", 0L));
    }

    public Status commit(ReliableSessionManifest manifest) throws Exception {
        JSONObject payload = new JSONObject(manifest.canonicalCommitJson());
        payload.put("manifest_sha256", manifest.commitSha256());
        JSONObject response = postJson("/audio/v2/commit?folder=" + encode(manifest.folderId)
                + "&sid=" + encode(manifest.sessionId), payload);
        if (!response.optBoolean("committed", false)) {
            throw new ProtocolException(409, "Server did not commit the complete recording");
        }
        return parseStatus(response);
    }

    private Status parseStatus(JSONObject response) throws Exception {
        LinkedHashMap<Integer, RemoteSegment> received = new LinkedHashMap<>();
        JSONArray array = response.optJSONArray("received");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i); int seq = item.getInt("seq");
            received.put(seq, new RemoteSegment(seq, item.optString("sha256", ""),
                    item.optLong("bytes", 0L), item.optLong("server_received_at_ms", 0L),
                    item.optLong("server_durable_at_ms", 0L)));
        }
        LinkedHashMap<Integer, Transcript> transcripts = new LinkedHashMap<>();
        JSONArray text = response.optJSONArray("transcripts");
        if (text != null) for (int i = 0; i < text.length(); i++) {
            JSONObject item = text.getJSONObject(i); int seq = item.getInt("seq");
            transcripts.put(seq, new Transcript(seq, item.optString("state", "PENDING"),
                    item.optString("text", ""), item.optString("engine", ""),
                    item.optLong("created_at_ms", 0L), item.optString("error", "")));
        }
        return new Status(response.optBoolean("committed", false),
                response.optString("server_id", ""),
                response.optLong("manifest_revision", 0L), received, transcripts);
    }

    public void cancelActiveRequest() {
        HttpURLConnection connection = activeConnection.getAndSet(null);
        if (connection != null) connection.disconnect();
    }

    private JSONObject getJson(String path) throws Exception {
        return readJsonResponse(open(path, "GET"));
    }

    private JSONObject postBytes(String path, byte[] bytes,
                                 String contentType) throws Exception {
        HttpURLConnection connection = open(path, "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (BufferedOutputStream out = new BufferedOutputStream(
                    connection.getOutputStream())) {
                checkInterrupted();
                out.write(bytes);
                out.flush();
            }
            return readJsonResponse(connection);
        } finally {
            activeConnection.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    private JSONObject postJson(String path, JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(path, "POST");
        try {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                checkInterrupted(); out.write(bytes);
            }
            return readJsonResponse(connection);
        } finally {
            activeConnection.compareAndSet(connection, null); connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        checkInterrupted();
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(6000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json, audio/mpeg");
        activeConnection.set(connection);
        return connection;
    }

    private JSONObject readJsonResponse(HttpURLConnection connection) throws Exception {
        try {
            checkInterrupted(); int code = connection.getResponseCode();
            byte[] body = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            checkInterrupted(); String text = new String(body, StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                String message = text;
                try { message = new JSONObject(text).optString("error", text); }
                catch (Exception ignored) {}
                throw new ProtocolException(code, message);
            }
            return new JSONObject(text);
        } finally {
            activeConnection.compareAndSet(connection, null); connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) {
                checkInterrupted(); out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Audio synchronization cancelled because Voice Button was closed");
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value,
                StandardCharsets.UTF_8.name());
    }
}
