package com.hans.android.voicebutton;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileOutputStream;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.hans.android.network.reliable.RecordingUploadPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainActivityExitTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS);

    private Context context;

    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(RecordingUploadPolicy.resumeForUserAction(context));
        assertFalse(RecordingUploadPolicy.pausedByExit(context));
    }

    @After public void cleanup() {
        context.stopService(new Intent(context, RecordingService.class));
        RecordingUploadPolicy.resumeForUserAction(context);
    }

    @Test public void visibleExitClosesUiAndPersistentlyStopsBackgroundTransfers() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.voicebutton_exit)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_exit)).perform(click());
            assertTrue("Exit must persist its intent before the Activity closes",
                    RecordingUploadPolicy.pausedByExit(context));
            assertFalse("Exit must disable all automatic and manual upload loops",
                    RecordingUploadPolicy.uploadPermitted(context));
            waitForServiceShutdown();
        }
    }

    @Test public void backFromIdleAlsoClosesInsteadOfKeepingRecorderRunning() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(MainActivity::onBackPressed);
            assertTrue(RecordingUploadPolicy.pausedByExit(context));
            assertFalse(RecordingUploadPolicy.uploadPermitted(context));
            waitForServiceShutdown();
        }
    }

    @Test public void exitFromPausedRecordingRetainsDurableSession()
            throws Exception {
        ReliableSessionStore store = new ReliableSessionStore(context);
        ReliableSessionManifest initial = store.createSession("Exit test microphone", -1);
        File directory = new File(new File(new File(new File(
                store.getRoot(), "folders"), initial.folderId),
                "sessions"), initial.sessionId);
        File pcm = new File(directory, "segment_000000_48000.pcm");
        try (FileOutputStream output = new FileOutputStream(pcm)) {
            output.write(new byte[48000 * 2]);
            output.getFD().sync();
        }
        store.markPaused(initial.sessionId);
        assertTrue(store.load(initial.sessionId).paused);
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    onView(withId(R.id.voicebutton_primary)).check(
                            matches(withText("Resume recording")));
                    break;
                } catch (AssertionError notReady) {
                    if (attempt == 59) throw notReady;
                    android.os.SystemClock.sleep(200);
                }
            }
            onView(withId(R.id.voicebutton_exit)).perform(click());
            onView(withText("Pause and exit")).perform(click());
            assertTrue(RecordingUploadPolicy.pausedByExit(context));
            waitForServiceShutdown();
        }
        ReliableSessionManifest preserved = store.load(initial.sessionId);
        assertTrue("A paused recording must survive explicit exit", preserved.paused);
        assertFalse(preserved.recordingFinished);
    }

    private void waitForServiceShutdown() {
        ActivityManager manager = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        long deadline = android.os.SystemClock.elapsedRealtime() + 15_000L;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            boolean running = false;
            for (ActivityManager.RunningServiceInfo info :
                    manager.getRunningServices(100)) {
                if (RecordingService.class.getName().equals(
                        info.service.getClassName())) {
                    running = true;
                    break;
                }
            }
            if (!running) return;
            android.os.SystemClock.sleep(200L);
        }
        org.junit.Assert.fail("Recorder foreground service survived explicit Exit");
    }
}
