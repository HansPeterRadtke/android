package com.hans.android.voicebutton;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import com.hans.android.network.reliable.MobileAudioCredential;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=Application.class)
public class LivePcmReplicatorTest {
    @Test public void fsyncedPcmIsMirroredOffTheRecorderThread() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        String token="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        MobileAudioCredential.save(app,token);
        app.getSharedPreferences("voicebutton_behavior",Context.MODE_PRIVATE).edit()
                .putBoolean("automatic_upload",true)
                .putBoolean("background_paused_by_exit",false).commit();
        byte[] data=new byte[4096];
        for(int i=0;i<data.length;i++)data[i]=(byte)(i*19);
        File file=File.createTempFile("live-pcm-audit", ".pcm");
        try(FileOutputStream stream=new FileOutputStream(file)) {
            stream.write(data);stream.getFD().sync();
        }
        AtomicReference<Throwable> failure=new AtomicReference<>();
        try(ServerSocket fake=new ServerSocket(0,3,InetAddress.getLoopbackAddress())) {
            fake.setSoTimeout(10000);
            Thread receiver=new Thread(()->{
                try {
                    for(int i=0;i<2;i++)try(Socket socket=fake.accept()){
                        socket.setSoTimeout(6000);
                        BufferedInputStream in=new BufferedInputStream(socket.getInputStream());
                        String request=readLine(in);int length=0;
                        String header;boolean credential=false;
                        while((header=readLine(in))!=null&&!header.isEmpty()) {
                            if(header.toLowerCase().startsWith("x-voicebutton-token:"))
                                credential=header.substring(header.indexOf(':')+1).trim().equals(token);
                            if(header.toLowerCase().startsWith("content-length:"))
                                length=Integer.parseInt(header.substring(header.indexOf(':')+1).trim());
                        }
                        assertTrue(credential);
                        assertTrue(request.startsWith(i==0
                                ? "GET /audio/v2/live-pcm-status?"
                                : "POST /audio/v2/live-pcm-part?"));
                        if(i==1)assertArrayEquals(data,in.readNBytes(length));
                        byte[] response=(i==0
                                ? "{\"durable_offset\":0}"
                                : "{\"ok\":true,\"durable\":true,\"server_id\":\"jetson\",\"durable_offset\":4096}")
                                .getBytes(StandardCharsets.UTF_8);
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                +"Content-Length: "+response.length
                                +"\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(response);out.flush();
                    }
                }catch(Throwable error){failure.set(error);}
            },"fake-durable-live-pcm-server");
            receiver.setDaemon(true);receiver.start();
            LivePcmReplicator replicator=new LivePcmReplicator(app,
                    "http://127.0.0.1:"+fake.getLocalPort());
            try {
                replicator.begin("audit-session","default","audit-conversation");
                long start=System.nanoTime();
                replicator.onDurablePrefix("audit-session",0,file,48000,4096L);
                assertTrue("writer notification blocked on network",
                        (System.nanoTime()-start)<100_000_000L);
                long deadline=System.nanoTime()+8_000_000_000L;
                while(replicator.progress().acknowledgedBytes<4096L
                        && System.nanoTime()<deadline)Thread.sleep(30L);
                assertEquals(4096L,replicator.progress().acknowledgedBytes);
                assertEquals(1000,replicator.progress().permille());
                receiver.join(8000L);
                assertFalse(receiver.isAlive());
                if(failure.get()!=null)throw new AssertionError(failure.get());
            }finally {
                replicator.stop();
                assertTrue(replicator.awaitStopped(5000L));
            }
        }finally {
            file.delete();
            MobileAudioCredential.save(app,"");
        }
    }

    private static String readLine(BufferedInputStream in)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=in.read())>=0&&b!='\n')if(b!='\r')bytes.write(b);
        return b<0&&bytes.size()==0?null:bytes.toString("US-ASCII");
    }
}
