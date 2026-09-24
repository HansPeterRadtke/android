package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PausedServiceRestartTest {
    private Context context;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.stopService(new Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @After public void cleanup() throws Exception {
        context.stopService(new Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @Test public void freshServiceRestoresDurablePausedSessionBeforeReady() throws Exception {
        ReliableSessionStore seedStore = new ReliableSessionStore(context);
        ReliableSessionManifest created = seedStore.createSession("Test microphone", -1);
        File sessionDir = new File(new File(new File(new File(seedStore.getRoot(), "folders"),
                created.folderId), "sessions"), created.sessionId);
        File pcm = new File(sessionDir, "segment_000000_48000.pcm");
        byte[] oneSecond = new byte[48000 * 2];
        try (FileOutputStream output = new FileOutputStream(pcm)) {
            output.write(oneSecond);
            output.flush();
            output.getFD().sync();
        }
        seedStore.markPaused(created.sessionId);
        ReliableSessionManifest seeded = seedStore.load(created.sessionId);
        assertTrue(seeded.paused);
        assertFalse(seeded.recordingFinished);

        Intent serviceIntent = new Intent(context, RecordingService.class);
        CountDownLatch connected = new CountDownLatch(1);
        final RecordingService[] holder = new RecordingService[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = ((RecordingService.LocalBinder) binder).getService();
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE));
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            RecordingService.Snapshot snapshot = null;
            for (int i = 0; i < 150; i++) {
                snapshot = holder[0].getSnapshot();
                if (snapshot != null && "PAUSED".equals(snapshot.state)
                        && snapshot.openSession != null) break;
                Thread.sleep(100L);
            }
            assertNotNull(snapshot);
            assertEquals("PAUSED", snapshot.state);
            assertTrue(snapshot.paused);
            assertFalse(snapshot.recording);
            assertNotNull(snapshot.openSession);
            assertEquals(created.sessionId, snapshot.currentSessionId);
            assertEquals(created.sessionId, snapshot.openSession.sessionId);
            assertTrue(snapshot.openSession.paused);
        } finally {
            context.unbindService(connection);
        }
    }
}
