package com.hans.android.network.reliable;

import static org.junit.Assert.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class LivePcmTransportTest {
    @Test public void privateStreamRequiresToken() throws Exception {
        ReliableUploadClient client = new ReliableUploadClient("http://127.0.0.1:1");
        try { client.livePcmStatus("default", "test", 0); fail("unauthorized status"); }
        catch (ReliableUploadClient.ProtocolException denial) {
            assertEquals(401, denial.httpCode);
        }
        try { client.uploadLivePcmPart("default", "test", "test", 0,
                48000, 0L, new byte[]{1,2}); fail("unauthorized upload"); }
        catch (ReliableUploadClient.ProtocolException denial) {
            assertEquals(401, denial.httpCode);
        }
    }

    @Test public void authenticatedTransportIncludesHashAndDurableAcknowledgements()
            throws Exception {
        String token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        byte[] source = new byte[]{1,2,3,4};
        AtomicReference<Throwable> problem = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 3,
                InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10000);
            Thread thread = new Thread(() -> {
                try {
                    for (int i=0;i<2;i++) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(6000);
                            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                            String request = readLine(input);
                            assertTrue(request.startsWith(i == 0
                                    ? "GET /audio/v2/live-pcm-status?"
                                    : "POST /audio/v2/live-pcm-part?"));
                            int contentLength=0;
                            String header;
                            boolean credential=false;
                            while ((header=readLine(input))!=null && !header.isEmpty()) {
                                if (header.toLowerCase(Locale.US).startsWith("x-voicebutton-token:"))
                                    credential=header.substring(header.indexOf(':')+1).trim().equals(token);
                                if (header.toLowerCase(Locale.US).startsWith("content-length:"))
                                    contentLength=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                            }
                            assertTrue("credential missing",credential);
                            if (i==1) {
                                assertArrayEquals(source,input.readNBytes(contentLength));
                                assertTrue(request.contains("part_sha256="
                                        +com.hans.android.audio.reliable.ReliableSessionManifest.sha256(source)));
                            }
                            byte[] response=(i==0
                                    ? "{\"durable_offset\":0}"
                                    : "{\"ok\":true,\"durable\":true,\"server_id\":\"jetson\",\"durable_offset\":4}")
                                    .getBytes(StandardCharsets.US_ASCII);
                            OutputStream output=socket.getOutputStream();
                            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                    +"Content-Length: "+response.length+"\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            output.write(response);output.flush();
                        }
                    }
                } catch (Throwable failure) { problem.set(failure); }
            }, "live-pcm-fake-server");
            thread.setDaemon(true);thread.start();
            ReliableUploadClient client = new ReliableUploadClient(
                    "http://127.0.0.1:" + server.getLocalPort());
            client.setRecordingServerToken(token);
            assertEquals(0L,client.livePcmStatus("default","test",0).getLong("durable_offset"));
            assertEquals(4L,client.uploadLivePcmPart("default","test","test",0,
                    48000,0L,source).getLong("durable_offset"));
            thread.join(10000);
            assertFalse(thread.isAlive());
            if(problem.get()!=null)throw new AssertionError(problem.get());
        }
    }

    private static String readLine(BufferedInputStream input) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();int b;
        while ((b=input.read())>=0 && b!='\n') if (b!='\r') output.write(b);
        if (b<0 && output.size()==0) return null;
        return output.toString(StandardCharsets.US_ASCII.name());
    }
}
