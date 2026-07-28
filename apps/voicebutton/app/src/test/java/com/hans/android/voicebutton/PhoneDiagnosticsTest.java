package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PhoneDiagnosticsTest {
    @Test public void exactFailureReportsDeepestCauseAndOperation() {
        Throwable failure = new IllegalStateException("outer",
                new java.io.IOException("microphone device disappeared"));
        assertEquals("Starting AudioRecord failed: IOException: microphone device disappeared",
                PhoneDiagnostics.exactFailure("Starting AudioRecord", failure));
    }

    @Test public void exactFailureNeverReturnsVagueBlankText() {
        Throwable failure = new IllegalArgumentException();
        assertEquals("Operation failed: IllegalArgumentException: no exception message",
                PhoneDiagnostics.exactFailure(null, failure));
    }
}
