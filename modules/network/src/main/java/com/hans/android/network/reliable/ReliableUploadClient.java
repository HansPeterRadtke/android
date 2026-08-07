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
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ReliableUploadClient {
    public static final int MIN_UPLOAD_PART_BYTES = AdaptiveUploadPolicy.MIN_PART_BYTES;
    public static final int INITIAL_UPLOAD_PART_BYTES = AdaptiveUploadPolicy.INITIAL_PART_BYTES;
    public static final int MAX_UPLOAD_PART_BYTES = AdaptiveUploadPolicy.MAX_PART_BYTES;
    public static final int CONNECT_TIMEOUT_MS = 15_000;
    public static final int READ_TIMEOUT_MS = 45_000;

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
        public final int provisionalTranscriptComplete;
        public final int provisionalTranscriptTotal;
        public final String finalTranscriptState;

        Status(boolean committed, String serverId, long manifestRevision,
               Map<Integer, RemoteSegment> received,
               Map<Integer, Transcript> transcripts,
               int provisionalTranscriptComplete,
               int provisionalTranscriptTotal,
               String finalTranscriptState) {
            this.committed = committed; this.serverId = serverId;
            this.manifestRevision = manifestRevision;
            this.received = received; this.transcripts = transcripts;
            this.provisionalTranscriptComplete = provisionalTranscriptComplete;
            this.provisionalTranscriptTotal = provisionalTranscriptTotal;
            this.finalTranscriptState = finalTranscriptState == null
                    ? "NONE" : finalTranscriptState;
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
        public final long retryAfterMs;
        public ProtocolException(int httpCode, String message) {
            this(httpCode, message, 0L);
        }
        public ProtocolException(int httpCode, String message, long retryAfterMs) {
            super(message);
            this.httpCode = httpCode;
            this.retryAfterMs = Math.max(0L, retryAfterMs);
        }
    }

    private final String baseUrl;
    private final List<String> baseUrls;
    private final String userAgent;
    private final AdaptiveUploadPolicy uploadPolicy = new AdaptiveUploadPolicy();
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    public ReliableUploadClient(String baseUrl) {
        this(baseUrl, "VoiceButton/0.35 Android");
    }

    public ReliableUploadClient(String baseUrl, String userAgent) {
        ArrayList<String> urls = new ArrayList<>();
        String raw = baseUrl == null ? "" : baseUrl.trim();
        for (String part : raw.split("[,\n]")) {
            String value = part == null ? "" : part.trim();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            if (!value.isEmpty() && !urls.contains(value)) urls.add(value);
        }
        if (urls.isEmpty()) throw new IllegalArgumentException("Server URL is empty");
        this.baseUrls = Collections.unmodifiableList(urls);
        this.baseUrl = urls.get(0);
        this.userAgent = userAgent;
    }

    public void createFolder(String folderId, String name) throws Exception {
        createFolder(folderId, name, "");
    }

    public void createFolder(String folderId, String name,
                             String parentFolderId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("folder_id", folderId);
        payload.put("name", name);
        payload.put("parent_folder_id",
                parentFolderId == null ? "" : parentFolderId);
        postJson("/audio/v2/folders", payload);
    }

    public void moveSession(String sourceFolderId, String destinationFolderId,
                            String sessionId) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("source_folder_id", sourceFolderId);
        payload.put("destination_folder_id", destinationFolderId);
        payload.put("session_id", sessionId);
        JSONObject response = postJson("/audio/v2/move", payload);
        if (!response.optBoolean("ok", false)
                || !destinationFolderId.equals(response.optString("folder_id", ""))) {
            throw new ProtocolException(409, "Server did not confirm the recording move");
        }
    }

    public void updateMetadata(ReliableSessionManifest manifest) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("display_name", manifest.displayName == null
                || manifest.displayName.trim().isEmpty()
                ? manifest.sessionId : manifest.displayName.trim());
        payload.put("final_mp3_name", manifest.finalMp3Name == null
                || manifest.finalMp3Name.trim().isEmpty()
                ? com.hans.android.audio.reliable.RecordingFileNames.mp3Name(
                        manifest.createdAt, manifest.sessionId,
                        manifest.displayName)
                : manifest.finalMp3Name.trim());
        JSONObject response = postJson("/audio/v2/metadata?folder=" + encode(manifest.folderId)
                + "&sid=" + encode(manifest.sessionId), payload);
        if (!response.optBoolean("ok", false)) {
            throw new ProtocolException(409, "Server did not confirm recording metadata");
        }
    }

    public Status status(ReliableSessionManifest manifest) throws Exception {
        JSONObject response = getJson("/audio/v2/sync-status?folder="
                + encode(manifest.folderId) + "&sid=" + encode(manifest.sessionId));
        return parseStatus(response);
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
                int length = uploadPolicy.nextPartLength(offset, segment.mp3Bytes);
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
                long requestStarted = System.nanoTime();
                JSONObject response;
                try {
                    response = postBytes(path, part, "application/octet-stream");
                } catch (Exception failure) {
                    uploadPolicy.onPartFailure();
                    throw failure;
                }
                long next = validateProgress(response, segment.mp3Bytes);
                if (next < offset + length && !response.optBoolean("complete", false)) {
                    throw new ProtocolException(409,
                            "Server did not durably advance the chunk offset");
                }
                uploadPolicy.onPartSuccess(length, Math.max(0L,
                        (System.nanoTime() - requestStarted) / 1_000_000L));
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

    static int nextPartLength(long offset, long totalBytes, int partBytes) {
        if (offset < 0L || totalBytes < 0L || offset >= totalBytes) return 0;
        int normalized = Math.max(MIN_UPLOAD_PART_BYTES,
                Math.min(MAX_UPLOAD_PART_BYTES, partBytes));
        return (int)Math.min((long)normalized, totalBytes - offset);
    }

    public int currentPartBytes() { return uploadPolicy.currentPartBytes(); }

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
                + "&sid=" + encode(manifest.sessionId) + "&compact=1", payload);
        if (!response.optBoolean("committed", false)) {
            throw new ProtocolException(409, "Server did not commit the complete recording");
        }
        return parseStatus(response);
    }

    static Status parseStatus(JSONObject response) throws Exception {
        LinkedHashMap<Integer, RemoteSegment> received = new LinkedHashMap<>();
        JSONArray compact = response.optJSONArray("received_compact");
        if (compact != null) for (int i = 0; i < compact.length(); i++) {
            JSONArray item = compact.getJSONArray(i);
            int seq = item.getInt(0);
            received.put(seq, new RemoteSegment(seq,
                    item.optString(2, ""), item.optLong(1, 0L),
                    item.optLong(3, 0L), item.optLong(4, 0L)));
        }
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
        JSONObject finalTranscript = response.optJSONObject("final_transcript");
        String finalState = finalTranscript == null ? "NONE"
                : finalTranscript.optString("state", "NONE");
        boolean committed = response.optBoolean("committed", false);
        if (committed && "COMPLETE".equals(finalState) && !received.isEmpty()) {
            String finalText = finalTranscript.optString("text", "");
            String engine = finalTranscript.optString("engine", "");
            long createdAt = finalTranscript.optLong("created_at_ms", 0L);
            boolean first = true;
            for (Integer seq : received.keySet()) {
                transcripts.put(seq, new Transcript(seq, "COMPLETE",
                        first ? finalText : "", engine, createdAt, ""));
                first = false;
            }
        }
        return new Status(committed, response.optString("server_id", ""),
                response.optLong("manifest_revision", 0L), received, transcripts,
                response.optInt("provisional_transcript_complete", transcripts.size()),
                response.optInt("provisional_transcript_total", transcripts.size()),
                finalState);
    }

    public void cancelActiveRequest() {
        HttpURLConnection connection = activeConnection.getAndSet(null);
        if (connection != null) connection.disconnect();
    }

    private JSONObject getJson(String path) throws Exception {
        Exception lastFailure = null;
        for (String endpoint : baseUrls) {
            HttpURLConnection connection = null;
            try {
                connection = open(endpoint, path, "GET");
                return readJsonResponse(connection);
            } catch (Exception failure) {
                lastFailure = failure;
                if (!shouldTryNextEndpoint(failure)) throw failure;
            } finally {
                if (connection != null) {
                    activeConnection.compareAndSet(connection, null);
                    connection.disconnect();
                }
            }
        }
        throw lastFailure == null ? new UnknownHostException(baseUrl) : lastFailure;
    }

    private JSONObject postBytes(String path, byte[] bytes,
                                 String contentType) throws Exception {
        Exception lastFailure = null;
        for (String endpoint : baseUrls) {
            HttpURLConnection connection = null;
            try {
                connection = open(endpoint, path, "POST");
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
            } catch (Exception failure) {
                lastFailure = failure;
                if (!shouldTryNextEndpoint(failure)) throw failure;
            } finally {
                if (connection != null) {
                    activeConnection.compareAndSet(connection, null);
                    connection.disconnect();
                }
            }
        }
        throw lastFailure == null ? new UnknownHostException(baseUrl) : lastFailure;
    }

    private JSONObject postJson(String path, JSONObject payload) throws Exception {
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        Exception lastFailure = null;
        for (String endpoint : baseUrls) {
            HttpURLConnection connection = null;
            try {
                connection = open(endpoint, path, "POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
                    checkInterrupted(); out.write(bytes);
                }
                return readJsonResponse(connection);
            } catch (Exception failure) {
                lastFailure = failure;
                if (!shouldTryNextEndpoint(failure)) throw failure;
            } finally {
                if (connection != null) {
                    activeConnection.compareAndSet(connection, null);
                    connection.disconnect();
                }
            }
        }
        throw lastFailure == null ? new UnknownHostException(baseUrl) : lastFailure;
    }

    private HttpURLConnection open(String endpoint, String path, String method) throws Exception {
        checkInterrupted();
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + path).openConnection();
        // Bound every request. Uploads are resumable, so timing out and retrying
        // is safer than allowing one half-open connection to block the entire queue.
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", "application/json, audio/mpeg");
        activeConnection.set(connection);
        return connection;
    }

    private static boolean shouldTryNextEndpoint(Exception failure) {
        if (failure instanceof UnknownHostException) return true;
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.US);
        return message.contains("unable to resolve host")
                || message.contains("no address associated")
                || message.contains("failed to connect")
                || message.contains("connection refused")
                || message.contains("timed out");
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
                throw new ProtocolException(code, message,
                        parseRetryAfterMs(connection.getHeaderField("Retry-After")));
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


    static long parseRetryAfterMs(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        String normalized = value.trim();
        try {
            return Math.max(0L, Long.parseLong(normalized)) * 1000L;
        } catch (NumberFormatException ignored) {}
        try {
            SimpleDateFormat format = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date parsed = format.parse(normalized);
            return parsed == null ? 0L : Math.max(0L,
                    parsed.getTime() - System.currentTimeMillis());
        } catch (Exception ignored) {
            return 0L;
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
