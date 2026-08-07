package com.hans.android.voicebutton;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;

final class RecordingFailureAlarm {
    private static final long REPEAT_MS = 4000L;
    private static final int TONE_DURATION_MS = 1200;

    private final Handler handler;
    private ToneGenerator tone;
    private boolean active;
    private boolean silenced;
    private String message = "";

    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            synchronized (RecordingFailureAlarm.this) {
                if (!active || silenced) return;
            }
            try {
                if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_DURATION_MS);
            } catch (RuntimeException ignored) {}
            handler.postDelayed(this, REPEAT_MS);
        }
    };

    RecordingFailureAlarm(Handler handler) {
        this.handler = handler;
    }

    synchronized void start(String detail) {
        message = detail == null ? "Recording stopped unexpectedly" : detail;
        if (!active) {
            active = true;
            silenced = false;
        }
        handler.removeCallbacks(pulse);
        if (!silenced) handler.post(pulse);
    }

    synchronized void silence() {
        silenced = true;
        handler.removeCallbacks(pulse);
        stopTone();
    }

    synchronized void resolve() {
        active = false;
        silenced = false;
        message = "";
        handler.removeCallbacks(pulse);
        stopTone();
    }

    synchronized void release() {
        resolve();
        if (tone != null) {
            try { tone.release(); } catch (RuntimeException ignored) {}
            tone = null;
        }
    }

    synchronized boolean isActive() { return active; }
    synchronized boolean isAudible() { return active && !silenced; }
    synchronized String getMessage() { return message; }

    private void stopTone() {
        if (tone != null) {
            try { tone.stopTone(); } catch (RuntimeException ignored) {}
        }
    }
}
