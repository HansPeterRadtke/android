package com.hans.android.voicebutton;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.ProgressBar;
import android.widget.TextView;

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
public class CurrentUploadUiTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS);

    private Context context;
    private boolean previousAutomaticUpload;
    private long previousManualUploadBeforeMs;

    @Before public void setupWithoutServerUpload() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = AppSettings.preferences(context);
        previousAutomaticUpload = preferences.getBoolean("automatic_upload", true);
        previousManualUploadBeforeMs =
                preferences.getLong("manual_upload_before_ms", 0L);
        // An emulator-only synthetic recording must NEVER be sent to Jetson.
        assertTrue(preferences.edit().putBoolean("automatic_upload", false)
                .putLong("manual_upload_before_ms", 0L).commit());
        context.stopService(new Intent(context, RecordingService.class));
        ReliableSessionStore store = new ReliableSessionStore(context);
        store.deleteAll();
        seedFinishedRecording(store, "historical_audio.mp3", 900);
        Thread.sleep(25L);
        seedFinishedRecording(store, "new_recording.mp3", 400);
    }

    private static void seedFinishedRecording(ReliableSessionStore store,
                                              String name, long acknowledgedBytes)
            throws Exception {
        ReliableSessionManifest session =
                store.createSession("Synthetic emulator test", -1);
        File audio = new File(store.sessionDirectory(session.sessionId), name);
        try (FileOutputStream output = new FileOutputStream(audio)) {
            output.write(new byte[1000]);
            output.flush();
            output.getFD().sync();
        }
        store.commitMp3Segment(session.sessionId, 0, audio, 1000L);
        store.markRecordingFinished(session.sessionId, "synthetic-test");
        store.markConversionFinished(session.sessionId, audio);
        store.markRemotePartProgress(session.sessionId, 0, acknowledgedBytes,
                "test-not-on-real-jetson", 1L);
    }

    @After public void cleanup() throws Exception {
        if (context == null) return;
        context.stopService(new Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
        AppSettings.preferences(context).edit()
                .putBoolean("automatic_upload", previousAutomaticUpload)
                .putLong("manual_upload_before_ms", previousManualUploadBeforeMs)
                .commit();
    }

    @Test public void overviewShowsNewRecordingPercentageAndOtherCountOnly() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            AssertionError last = null;
            for (int attempt = 0; attempt < 120; attempt++) {
                try {
                    onView(withId(R.id.voicebutton_active_upload))
                            .check(matches(withText(containsString("new_recording.mp3"))));
                    last = null;
                    break;
                } catch (AssertionError failure) {
                    last = failure;
                    try { Thread.sleep(100L); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw failure;
                    }
                }
            }
            if (last != null) throw last;
            onView(withId(R.id.voicebutton_active_upload)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_upload_queue))
                    .check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_transcription_queue))
                    .check(matches(withEffectiveVisibility(GONE)));
            scenario.onActivity(activity -> {
                ProgressBar progress =
                        activity.findViewById(R.id.voicebutton_active_upload_bar);
                assertTrue(progress.isShown());
                assertEquals("Progress must be current file only", 400, progress.getProgress());
                assertTrue(((TextView) ((android.view.ViewGroup)
                        activity.findViewById(R.id.voicebutton_active_upload).getParent())
                        .getChildAt(1)).getText().toString()
                        .contains("1 more recording waiting"));
                assertTrue("Automatic upload still disabled for this test",
                        !AppSettings.uploadPermitted(activity));
            });
        }
    }
}
