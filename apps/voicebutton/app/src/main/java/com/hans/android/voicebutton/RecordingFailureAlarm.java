package com.hans.android.voicebutton;

import android.os.Handler;

final class RecordingFailureAlarm {
    private final Handler handler;
    private boolean active;
    private String message = "";

    RecordingFailureAlarm(Handler handler) {
        this.handler = handler;
    }

    synchronized void start(String detail) {
        message = detail == null ? "Recording stopped unexpectedly" : detail;
        active = true;
    }

    synchronized void silence() {
        active = false;
        message = "";
    }

    synchronized void resolve() {
        active = false;
        message = "";
    }

    synchronized void release() {
        resolve();
    }

    synchronized boolean isActive() { return active; }
    synchronized boolean isAudible() { return false; }
    synchronized String getMessage() { return message; }
}
