package com.hans.android.network.reliable;

import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class RecordingAuthHeaderTest {
    @Test public void configuredCredentialIsSentOnRealHttpRequests() throws Exception {
        AtomicReference<String> seenToken = new AtomicReference<>();
        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket(
                0, 5, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(10000);
            Thread worker = new Thread(() -> {
                try {
                    for (int index = 0; index < 2; index++) {
                        try (Socket socket = listener.accept()) {
                            socket.setSoTimeout(5000);
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream(),
                                            StandardCharsets.US_ASCII));
                            String request = reader.readLine();
                            if (request == null || !request.startsWith("GET /audio/v2/transcription-status")) {
                                throw new AssertionError("Unexpected HTTP request " + request);
                            }
                            String line;
                            String credential = null;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase(Locale.US)
                                        .startsWith("x-voicebutton-token:")) {
                                    credential = line.substring(line.indexOf(':') + 1).trim();
                                }
                            }
                            seenToken.set(credential);
                            String json = "{\"ok\":true,\"total_committed_count\":1,"
                                    + "\"complete_count\":0,\"not_transcribed_count\":1,"
                                    + "\"overall_percent\":0}";
                            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
                            OutputStream output = socket.getOutputStream();
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                    + "Content-Length: " + payload.length
                                    + "\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            output.write(payload);
                            output.flush();
                        }
                    }
                } catch (Throwable failure) {
                    backgroundFailure.set(failure);
                }
            }, "voicebutton-auth-http-test");
            worker.setDaemon(true);
            worker.start();
            ReliableUploadClient client = new ReliableUploadClient(
                    "http://127.0.0.1:" + listener.getLocalPort(), "VoiceButton-test");
            assertEquals(1, client.transcriptionStatus().notTranscribedCount);
            assertNull(seenToken.get());
            String token = "SAMPLE_ACCESS_1234567890_ABCDEFGHIJKLMNOPQRSTUVWXY";
            assertEquals(token, MobileAudioCredential.normalize(token));
            client.setRecordingServerToken(token);
            assertEquals(1, client.transcriptionStatus().notTranscribedCount);
            assertEquals(token, seenToken.get());
            worker.join(10000);
            assertFalse("HTTP request worker still running", worker.isAlive());
            if (backgroundFailure.get() != null) throw new AssertionError(backgroundFailure.get());
        }
    }
}
