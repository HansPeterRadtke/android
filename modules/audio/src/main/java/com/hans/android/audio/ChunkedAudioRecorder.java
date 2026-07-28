package com.hans.android.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChunkedAudioRecorder {
    public interface Listener {
        void onStarted(String routedDevice);
        void onChunkPersisted(File file, int sequence, boolean finalChunk);
        void onStopped();
        void onFailure(String humanMessage);
    }

    public static final int SAMPLE_RATE = 16000;
    public static final int CHUNK_MS = 1000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CHUNK_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_MS / 1000;
    private static final int FINAL_SILENCE_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 10;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile AudioRecord activeRecord;
    private Thread thread;

    public synchronized boolean start(Context context, AudioInputOption input, SpoolStore store,
                                      String sessionId, Listener listener) {
        if (!recording.compareAndSet(false, true)) return false;
        thread = new Thread(() -> capture(context.getApplicationContext(), input, store, sessionId, listener),
                "voicebutton-capture");
        thread.start();
        return true;
    }

    public void stop() {
        recording.set(false);
        AudioRecord recorder = activeRecord;
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) {}
        }
    }

    public boolean isRecording() { return recording.get(); }

    private void capture(Context context, AudioInputOption input, SpoolStore store,
                         String sessionId, Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRouteController route = new AudioRouteController(context);
        AudioRecord recorder = null;
        int sequence = 0;
        int offset = 0;
        byte[] pcm = new byte[CHUNK_BYTES];
        try {
            AudioDeviceInfo requested = route.prepare(context, input);
            if (input != null && !input.isSystemDefault() && requested == null) {
                throw new IllegalStateException("The selected microphone is no longer connected");
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Android rejected the requested audio format");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, CHUNK_BYTES * 2));
            activeRecord = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Android could not initialize this microphone");
            }
            if (!route.applyPreferredDevice(recorder, requested, input)) {
                throw new IllegalStateException("Android refused the selected microphone route");
            }
            recorder.startRecording();
            AudioDeviceInfo routed = route.waitForRoutedInput(recorder, requested, input);
            listener.onStarted(AudioInputCatalog.describe(routed));

            while (recording.get()) {
                int read = recorder.read(pcm, offset, pcm.length - offset, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    offset += read;
                    if (offset == pcm.length) {
                        File file = store.writeChunk(sessionId, sequence, false, pcm, offset, SAMPLE_RATE);
                        listener.onChunkPersisted(file, sequence, false);
                        sequence++;
                        offset = 0;
                    }
                } else if (!recording.get()) {
                    break;
                } else {
                    throw new IllegalStateException("Android stopped delivering microphone audio");
                }
            }

            byte[] finalPcm = pcm;
            int finalLength = offset;
            if (finalLength == 0) {
                finalPcm = new byte[FINAL_SILENCE_BYTES];
                finalLength = finalPcm.length;
            }
            File finalFile = store.writeChunk(sessionId, sequence, true,
                    finalPcm, finalLength, SAMPLE_RATE);
            listener.onChunkPersisted(finalFile, sequence, true);
        } catch (SecurityException denied) {
            listener.onFailure("Microphone or Bluetooth permission is missing");
        } catch (Exception failure) {
            String message = failure.getMessage();
            listener.onFailure(message == null || message.trim().isEmpty()
                    ? "Recording stopped because Android reported an audio failure"
                    : message);
        } finally {
            recording.set(false);
            activeRecord = null;
            if (recorder != null) {
                try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
                catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
            }
            route.release();
            listener.onStopped();
        }
    }
}
