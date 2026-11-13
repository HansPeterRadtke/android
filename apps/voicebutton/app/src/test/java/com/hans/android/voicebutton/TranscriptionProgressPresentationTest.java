package com.hans.android.voicebutton;

import static org.junit.Assert.*;
import com.hans.android.network.reliable.ReliableUploadClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import android.app.Application;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class TranscriptionProgressPresentationTest {
    private static ReliableUploadClient.TranscriptionStatus parse(JSONObject response)
            throws Exception {
        Method method = ReliableUploadClient.class.getDeclaredMethod(
                "parseTranscriptionStatus", JSONObject.class);
        method.setAccessible(true);
        return (ReliableUploadClient.TranscriptionStatus) method.invoke(null, response);
    }

    private static JSONObject current(String phase, String state, long done, long total)
            throws Exception {
        return new JSONObject().put("session_id", "new").put("display_name", "new.mp3")
                .put("state", state).put("phase", phase)
                .put("frames_done", done).put("frames_total", total).put("percent", 98);
    }

    @Test public void separatePerRecordingAndArchiveBarsHaveSeparateDenominators()
            throws Exception {
        JSONObject active = current("transcribing", "RUNNING", 420, 1000);
        JSONObject response = new JSONObject().put("current", active)
                .put("pending", new JSONArray().put(active))
                .put("complete_count", 99).put("not_transcribed_count", 1)
                .put("total_committed_count", 100).put("overall_percent", 99);
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(response), "", false);
        assertEquals(420, ui.currentPermille);
        assertEquals(990, ui.overallPermille);
        assertTrue(ui.currentLabel.contains("new.mp3 · 42%"));
        assertTrue(ui.overallLabel.contains("99 of 100"));
        assertFalse(ui.currentLabel.contains("99%"));
    }

    @Test public void noFrameDenominatorMeansIndeterminateEvenWithPercentField()
            throws Exception {
        JSONObject active = current("loading", "RUNNING", 0, 0);
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(new JSONObject()
                        .put("current", active).put("pending", new JSONArray().put(active))
                        .put("total_committed_count", 1)
                        .put("not_transcribed_count", 1)), "", false);
        assertEquals(-1, ui.currentPermille);
        assertTrue(ui.currentLabel.contains("Loading"));
    }

    @Test public void staleMeasuredProgressPreservesLastKnownValueAndAge()
            throws Exception {
        JSONObject active = current("transcribing", "RUNNING", 7, 10);
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(new JSONObject()
                        .put("current", active).put("pending", new JSONArray().put(active))
                        .put("total_committed_count", 2).put("complete_count", 1)
                        .put("not_transcribed_count", 1).put("overall_percent", 85)),
                        " · last update 12s ago", true);
        assertEquals(700, ui.currentPermille);
        assertEquals(850, ui.overallPermille);
        assertTrue(ui.currentLabel.contains("12s ago"));
        assertTrue(ui.overallLabel.contains("12s ago"));
        assertTrue(ui.stale);
    }

    @Test public void idleCompletedOverallBarIsOneHundredPercent()
            throws Exception {
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(new JSONObject()
                        .put("total_committed_count", 5).put("complete_count", 5)
                        .put("not_transcribed_count", 0).put("overall_percent", 100)),
                        "", false);
        assertEquals("", ui.currentLabel);
        assertEquals(1000, ui.overallPermille);
        assertTrue(ui.overallLabel.contains("5 of 5"));
    }

    @Test public void failureKeepsRealPartialProgressWithoutRunningIndicator()
            throws Exception {
        JSONObject failed = current("failed", "FAILED", 7, 10);
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(new JSONObject()
                    .put("current", failed).put("pending", new JSONArray().put(failed))
                    .put("total_committed_count", 1)
                    .put("not_transcribed_count", 1)), "", false);
        assertEquals(700, ui.currentPermille);
        assertTrue(ui.currentLabel.contains("70% processed"));
        assertTrue(ui.currentError);
        assertFalse(ui.currentProcessing);
    }

    @Test public void manualTranscriptDoesNotAnimateAsRunning()
            throws Exception {
        JSONObject manual = current("manual", "MANUAL", 0, 0);
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(new JSONObject()
                    .put("current", manual).put("pending", new JSONArray().put(manual))
                    .put("total_committed_count", 1)
                    .put("not_transcribed_count", 1)), "", false);
        assertEquals(-1, ui.currentPermille);
        assertFalse(ui.currentProcessing);
    }

    @Test public void disconnectedStatusNeverInventsPercentage() {
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(null, "", true);
        assertEquals(-1, ui.currentPermille);
        assertEquals(-1, ui.overallPermille);
        assertTrue(ui.currentError);
    }

    @Test public void missingServerTotalNeverManufacturesOverallBar()
            throws Exception {
        TranscriptionProgressPresentation.State ui =
                TranscriptionProgressPresentation.from(parse(new JSONObject()
                        .put("not_transcribed_count", 3)
                        .put("overall_percent", 67)
                        .put("total_committed_count", 0)), "", false);
        assertEquals(-1, ui.overallPermille);
        assertTrue(ui.currentLabel.contains("3 recordings"));
    }
}
