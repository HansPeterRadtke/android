package com.hans.android.voicebutton;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.hans.android.audio.reliable.ReliableSessionStore;
import com.hans.android.network.reliable.ReliableUploadClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

@RunWith(AndroidJUnit4.class)
public class TranscriptionProgressUiTest {
    @Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS);
    private Context context;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.stopService(new android.content.Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    @After public void cleanup() throws Exception {
        context.stopService(new android.content.Intent(context, RecordingService.class));
        new ReliableSessionStore(context).deleteAll();
    }

    private static ReliableUploadClient.TranscriptionStatus status(
            String phase, long framesDone, long framesTotal) throws Exception {
        JSONObject current = new JSONObject()
                .put("session_id", "current")
                .put("display_name", "New recording.mp3")
                .put("state", "RUNNING")
                .put("phase", phase)
                .put("frames_done", framesDone)
                .put("frames_total", framesTotal)
                .put("percent", 84);
        JSONObject reply = new JSONObject()
                .put("current", current)
                .put("pending", new JSONArray().put(current))
                .put("complete_count", 9).put("not_transcribed_count", 1)
                .put("total_committed_count", 10).put("overall_percent", 94);
        Method parse = ReliableUploadClient.class.getDeclaredMethod(
                "parseTranscriptionStatus", JSONObject.class);
        parse.setAccessible(true);
        return (ReliableUploadClient.TranscriptionStatus)parse.invoke(null, reply);
    }

    private static void render(MainActivity activity,
                               ReliableUploadClient.TranscriptionStatus value,
                               String suffix, boolean stale) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(
                "renderTranscriptionOperations",
                ReliableUploadClient.TranscriptionStatus.class, String.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(activity, value, suffix, stale);
    }

    @Test public void currentAndOverallHaveSeparateVisibleMeasuredBars() throws Exception {
        ReliableUploadClient.TranscriptionStatus reply = status("transcribing", 420L, 1000L);
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    render(activity, reply, "", false);
                    TextView current = activity.findViewById(
                            R.id.voicebutton_secondary_transcription);
                    TextView overall = activity.findViewById(
                            R.id.voicebutton_transcription_overall_label);
                    ProgressBar currentBar = activity.findViewById(
                            R.id.voicebutton_active_transcription_bar);
                    ProgressBar overallBar = activity.findViewById(
                            R.id.voicebutton_transcription_overall_bar);
                    assertTrue(current.isShown());
                    assertTrue(overall.isShown());
                    assertTrue(currentBar.isShown());
                    assertTrue(overallBar.isShown());
                    assertTrue(current.getText().toString().contains("42%"));
                    assertTrue(current.getText().toString().contains("New recording.mp3"));
                    assertTrue(overall.getText().toString().contains("All recordings"));
                    assertFalse(overall.getText().toString().equals(current.getText().toString()));
                    assertEquals(420, currentBar.getProgress());
                    assertEquals(940, overallBar.getProgress());
                    assertFalse(currentBar.isIndeterminate());
                    assertFalse(overallBar.isIndeterminate());
                    // The old archive queue and bars remain removed from the recording screen.
                    assertFalse(activity.findViewById(
                            R.id.voicebutton_transcription_queue).isShown());
                    assertFalse(activity.findViewById(
                            R.id.voicebutton_transcription_progress).isShown());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
    }

    @Test public void noFramesShowsIndeterminateAndStaleShowsLastKnownPercent()
            throws Exception {
        ReliableUploadClient.TranscriptionStatus loading = status("loading", 0L, 0L);
        ReliableUploadClient.TranscriptionStatus cached = status("transcribing", 60L, 100L);
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    render(activity, loading, "", false);
                    ProgressBar bar = activity.findViewById(
                            R.id.voicebutton_active_transcription_bar);
                    assertTrue(bar.isShown());
                    assertTrue(bar.isIndeterminate());
                    render(activity, cached, " · 12s old", true);
                    assertEquals(600, bar.getProgress());
                    assertFalse(bar.isIndeterminate());
                    assertTrue(((TextView)activity.findViewById(
                            R.id.voicebutton_secondary_transcription))
                            .getText().toString().contains("12s old"));
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            });
        }
    }
}
