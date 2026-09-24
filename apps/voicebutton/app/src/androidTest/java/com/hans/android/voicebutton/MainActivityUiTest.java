package com.hans.android.voicebutton;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.google.android.material.button.MaterialButton;
import com.hans.android.audio.reliable.ReliableSessionStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MainActivityUiTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS);

    private Context context;

    @Before public void cleanStore() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.stopService(new android.content.Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @After public void cleanup() throws Exception {
        context.stopService(new android.content.Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @Test public void idleOverviewMatchesRecordingContract() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitText(R.id.voicebutton_status_title, "Ready to record");
            onView(withId(R.id.voicebutton_backup_text)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_upload_current)).check(doesNotExist());
            onView(withId(R.id.voicebutton_transcription_summary)).check(doesNotExist());
            onView(withId(R.id.voicebutton_transcription_progress)).check(doesNotExist());
            onView(withId(R.id.voicebutton_transcription_current)).check(doesNotExist());
            scenario.onActivity(activity -> {
                View backupBar = activity.findViewById(R.id.voicebutton_backup_progress);
                boolean compact = activity.getResources().getConfiguration().screenHeightDp < 520;
                if (compact) {
                    assertTrue(backupBar.getVisibility() == View.GONE);
                } else {
                    assertTrue(backupBar.getVisibility() == View.VISIBLE);
                }
            });            onView(withId(R.id.voicebutton_primary)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_primary)).check(matches(withText("Start recording")));
            onView(withId(R.id.voicebutton_more)).check(matches(isDisplayed()));
            onView(withId(R.id.voicebutton_finish)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_duration)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_local_protection)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_routed_microphone)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_mic_level_text)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_mic_level_bar)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.voicebutton_setup)).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                View primary = activity.findViewById(R.id.voicebutton_primary);
                assertTrue(primary instanceof MaterialButton);
                assertTrue(primary.getHeight() >= dp(activity, 48));
                ViewParent parent = primary.getParent();
                while (parent instanceof View) {
                    assertFalse(parent instanceof ScrollView);
                    parent = parent.getParent();
                }
            });
        }
    }

    private static void awaitText(int viewId, String expected) {
        AssertionError last = null;
        for (int i = 0; i < 100; i++) {
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
        throw last == null ? new AssertionError("Timed out waiting for text") : last;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
