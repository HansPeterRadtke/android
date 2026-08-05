package com.hans.android.network.reliable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.hans.android.audio.reliable.ReliableSessionManifest;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public class ReliableUploadResumeIntegrationTest {
    @Test public void lostAcknowledgementResumesAtDurableServerOffset()
            throws Exception {
        byte[] expected = new byte[96 * 1024];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte)(i * 31);
        String wholeHash = ReliableSessionManifest.sha256(expected);
        try (FakeDurableServer server = new FakeDurableServer(
                expected.length, wholeHash)) {
            File file = Files.createTempFile(
                    "voicebutton-resume", ".mp3").toFile();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(expected);
            }
            ReliableSessionManifest manifest = new ReliableSessionManifest();
            manifest.sessionId = "resume-session";
            manifest.conversationId = "resume-conversation";
            manifest.folderId = "default";
            manifest.folderName = "Default";
            ReliableSessionManifest.Segment segment =
                    new ReliableSessionManifest.Segment();
            segment.seq = 0;
            segment.mp3Name = file.getName();
            segment.mp3Bytes = expected.length;
            segment.sha256 = wholeHash;
            ReliableUploadClient client = new ReliableUploadClient(
                    "http://127.0.0.1:" + server.port(), "VoiceButton-test");
            List<Long> durableProgress = new ArrayList<>();
            try {
                try {
                    client.uploadSegment(manifest, segment, file,
                            (bytes, total, serverId, revision) ->
                                    durableProgress.add(bytes));
                    fail("The lost acknowledgement must make the first attempt uncertain");
                } catch (Exception expectedFailure) {
                    assertTrue(expectedFailure.getMessage() == null
                            || !expectedFailure.getMessage().isEmpty());
                }
                ReliableUploadClient.Ack ack = client.uploadSegment(
                        manifest, segment, file,
                        (bytes, total, serverId, revision) ->
                                durableProgress.add(bytes));
                assertEquals("test-jetson", ack.serverId);
                assertEquals(2, server.postCount());
                assertEquals(Integer.valueOf(0), server.requestedOffsets().get(0));
                assertEquals(Integer.valueOf(64 * 1024),
                        server.requestedOffsets().get(1));
                assertArrayEquals(expected, server.durableBytes());
                assertTrue(durableProgress.contains(64L * 1024L));
                assertEquals(Long.valueOf(expected.length),
                        durableProgress.get(durableProgress.size() - 1));
                server.rethrowFailure();
            } finally {
                client.cancelActiveRequest();
                file.delete();
            }
        }
    }

    private static final class FakeDurableServer implements AutoCloseable {
        private final int expectedBytes;
        private final String wholeHash;
        private final ServerSocket server;
        private final Thread thread;
        private final ByteArrayOutputStream durable = new ByteArrayOutputStream();
        private final AtomicInteger posts = new AtomicInteger();
        private final List<Integer> offsets = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean closed;

        FakeDurableServer(int expectedBytes, String wholeHash) throws Exception {
            this.expectedBytes = expectedBytes;
            this.wholeHash = wholeHash;
            server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
            thread = new Thread(this::run, "voicebutton-test-http-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return server.getLocalPort(); }
        int postCount() { return posts.get(); }
        synchronized List<Integer> requestedOffsets() {
            return new ArrayList<>(offsets);
        }
        synchronized byte[] durableBytes() { return durable.toByteArray(); }

        void rethrowFailure() {
            Throwable value = failure.get();
            if (value != null) throw new AssertionError(value);
        }

        private void run() {
            while (!closed) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(30_000);
                    handle(socket);
                } catch (Throwable value) {
                    if (!closed) failure.compareAndSet(null, value);
                }
            }
        }

        private void handle(Socket socket) throws Exception {
            BufferedInputStream input = new BufferedInputStream(
                    socket.getInputStream());
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] request = requestLine.split(" ", 3);
            if (request.length < 2) throw new IllegalStateException(
                    "Invalid HTTP request line: " + requestLine);
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int split = line.indexOf(':');
                if (split > 0) headers.put(
                        line.substring(0, split).trim().toLowerCase(Locale.US),
                        line.substring(split + 1).trim());
            }
            int contentLength = Integer.parseInt(
                    headers.getOrDefault("content-length", "0"));
            byte[] body = readExact(input, contentLength);
            String target = request[1];
            if (target.startsWith("/audio/v2/chunk-progress?")) {
                synchronized (this) {
                    sendJson(socket.getOutputStream(), progress(durable.size(),
                            expectedBytes, wholeHash,
                            durable.size() == expectedBytes));
                }
                return;
            }
            if (!target.startsWith("/audio/v2/chunk-part?")) {
                sendError(socket.getOutputStream(), 404, "not_found");
                return;
            }
            Map<String, String> query = query(target);
            int offset = Integer.parseInt(query.get("offset"));
            int partBytes = Integer.parseInt(query.get("part_bytes"));
            synchronized (this) {
                offsets.add(offset);
                if (offset != durable.size()) throw new AssertionError(
                        "Expected offset " + durable.size() + " but received " + offset);
                if (partBytes != body.length) throw new AssertionError(
                        "Part length metadata did not match body");
                if (!ReliableSessionManifest.sha256(body).equals(
                        query.get("part_sha256"))) {
                    throw new AssertionError("Part digest did not match body");
                }
                durable.write(body);
                if (posts.incrementAndGet() == 1) {
                    // Bytes are durable, but the JSON acknowledgement disappears.
                    sendEmptySuccess(socket.getOutputStream());
                    return;
                }
                sendJson(socket.getOutputStream(), progress(durable.size(),
                        expectedBytes, wholeHash,
                        durable.size() == expectedBytes));
            }
        }

        @Override public void close() throws Exception {
            closed = true;
            server.close();
            thread.join(5000L);
            rethrowFailure();
        }
    }

    private static JSONObject progress(int offset, int total, String hash,
                                       boolean complete) throws Exception {
        JSONObject value = new JSONObject();
        value.put("durable", true);
        value.put("durable_offset", offset);
        value.put("chunk_bytes", total);
        value.put("complete", complete);
        value.put("server_id", "test-jetson");
        value.put("manifest_revision", 7L);
        if (complete) {
            value.put("sha256", hash);
            value.put("bytes", total);
            value.put("server_received_at_ms", 100L);
            value.put("server_durable_at_ms", 101L);
        }
        return value;
    }

    private static Map<String, String> query(String target) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        int marker = target.indexOf('?');
        String raw = marker < 0 ? "" : target.substring(marker + 1);
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split);
            String value = split < 0 ? "" : pair.substring(split + 1);
            result.put(URLDecoder.decode(key, "UTF-8"),
                    URLDecoder.decode(value, "UTF-8"));
        }
        return result;
    }

    private static String readLine(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        int value;
        while ((value = input.read()) != -1) {
            if (previous == '\r' && value == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1),
                        StandardCharsets.ISO_8859_1);
            }
            out.write(value);
            previous = value;
        }
        return out.size() == 0 ? null
                : new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readExact(InputStream input, int length) throws Exception {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(body, offset, length - offset);
            if (read < 0) throw new IllegalStateException("Unexpected request-body end");
            offset += read;
        }
        return body;
    }

    private static void sendJson(OutputStream raw, JSONObject value)
            throws Exception {
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8);
        send(raw, 200, "OK", "application/json; charset=utf-8", body);
    }

    private static void sendError(OutputStream raw, int code, String message)
            throws Exception {
        byte[] body = new JSONObject().put("error", message).toString()
                .getBytes(StandardCharsets.UTF_8);
        send(raw, code, "Error", "application/json; charset=utf-8", body);
    }

    private static void sendEmptySuccess(OutputStream raw) throws Exception {
        send(raw, 200, "OK", "application/json; charset=utf-8", new byte[0]);
    }

    private static void send(OutputStream raw, int code, String status,
                             String contentType, byte[] body) throws Exception {
        BufferedOutputStream out = new BufferedOutputStream(raw);
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
