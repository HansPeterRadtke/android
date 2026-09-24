package com.hans.android.voicebutton;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public class PausedUiRestoreTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS);

    private Context context;

    @Before public void seedPausedSession() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.stopService(new android.content.Intent(context, RecordingService.class));
        ReliableSessionStore store = new ReliableSessionStore(context);
        store.deleteAll();
        ReliableSessionManifest created = store.createSession("Test microphone", -1);
        File sessionDir = new File(new File(new File(new File(store.getRoot(), "folders"),
                created.folderId), "sessions"), created.sessionId);
        File pcm = new File(sessionDir, "segment_000000_48000.pcm");
        try (FileOutputStream output = new FileOutputStream(pcm)) {
            output.write(new byte[48000 * 2]);
            output.flush();
            output.getFD().sync();
        }
        store.markPaused(created.sessionId);
        assertTrue(store.load(created.sessionId).paused);
    }

    @After public void cleanup() throws Exception {
        context.stopService(new android.content.Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @Test public void freshActivityShowsPausedResumeContract() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitText(R.id.voicebutton_primary, "Resume recording");
            onView(withId(R.id.voicebutton_finish)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_primary)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_setup)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_local_protection)).check(matches(isDisplayed()));
        }
    }

    private static void awaitText(int viewId, String expected) {
        AssertionError last = null;
        for (int i = 0; i < 120; i++) {
            try {
                onView(withId(viewId)).check(matches(withText(expected)));
                return;
            } catch (AssertionError failure) {
                last = failure;
                try { Thread.sleep(100L); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw last == null ? new AssertionError("Timed out waiting for paused UI") : last;
    }
}
