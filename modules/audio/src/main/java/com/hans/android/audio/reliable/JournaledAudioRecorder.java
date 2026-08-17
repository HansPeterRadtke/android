package com.hans.android.audio.reliable;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.AudioRouteController;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JournaledAudioRecorder {
    public interface Listener {
        void onStarted(String routedDevice);
        void onSegmentCommitted(int seq, File wav, long pcmBytes);
        void onStopped(String sessionId);
        void onFailure(String message);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int READ_BYTES = 3200;
    private static final long SEGMENT_BYTES = SAMPLE_RATE * 2L * 5L;
    private static final long SYNC_BYTES = SAMPLE_RATE * 2L / 2L;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile AudioRecord activeRecord;
    private Thread thread;

    public synchronized boolean start(Context context, AudioInputOption input,
                                      ReliableSessionStore store, String sessionId,
                                      Listener listener) {
        if (!recording.compareAndSet(false, true)) return false;
        thread = new Thread(() -> capture(context.getApplicationContext(), input, store, sessionId, listener),
                "reliable-audio-capture");
        thread.start();
        return true;
    }

    public boolean isRecording() { return recording.get(); }

    public void stop() {
        recording.set(false);
        AudioRecord value = activeRecord;
        if (value != null) try { value.stop(); } catch (Exception ignored) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void capture(Context context, AudioInputOption input, ReliableSessionStore store,
                         String sessionId, Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRouteController route = new AudioRouteController(context);
        AudioRecord recorder = null;
        RandomAccessFile journal = null;
        File openFile = null;
        int seq = 0;
        long pcmBytes = 0L;
        long bytesSinceSync = 0L;
        try {
            ReliableSessionManifest manifest = store.load(sessionId);
            seq = manifest.nextSeq;
            AudioDeviceInfo requested = route.prepare(context, input);
            if (input != null && !input.isSystemDefault() && requested == null) {
                throw new IllegalStateException("The selected microphone is no longer connected");
            }
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Android rejected the recording format");
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, READ_BYTES * 4));
            activeRecord = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Android could not initialize the microphone");
            if (!route.applyPreferredDevice(recorder, requested, input)) throw new IllegalStateException("Android refused the selected microphone route");
            recorder.startRecording();
            AudioDeviceInfo routed = route.waitForRoutedInput(recorder, requested, input);
            listener.onStarted(AudioInputCatalog.describe(routed));
            byte[] buffer = new byte[READ_BYTES];
            while (recording.get()) {
                if (journal == null) {
                    store.ensureWritable(SEGMENT_BYTES + 1024L);
                    openFile = new File(store.sessionDirectory(sessionId), String.format(Locale.US, "segment_%06d.open.wav", seq));
                    journal = new RandomAccessFile(openFile, "rw");
                    journal.setLength(0L); ReliableSessionStore.writeWavHeader(journal, 0L); journal.seek(44L);
                    pcmBytes = 0L; bytesSinceSync = 0L;
                }
                int read = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    int even = read & ~1;
                    journal.write(buffer, 0, even); pcmBytes += even; bytesSinceSync += even;
                    if (bytesSinceSync >= SYNC_BYTES) {
                        ReliableSessionStore.writeWavHeader(journal, pcmBytes); journal.seek(44L + pcmBytes); journal.getFD().sync(); bytesSinceSync = 0L;
                    }
                    if (pcmBytes >= SEGMENT_BYTES) {
                        File closed = closeSegment(journal, openFile, seq, pcmBytes);
                        journal = null; openFile = null;
                        store.commitWavSegment(sessionId, seq, closed, pcmBytes);
                        listener.onSegmentCommitted(seq, closed, pcmBytes);
                        seq++;
                    }
                } else if (!recording.get()) break;
                else throw new IllegalStateException("Android stopped delivering microphone data");
            }
            if (journal != null && pcmBytes > 0L) {
                File closed = closeSegment(journal, openFile, seq, pcmBytes); journal = null; openFile = null;
                store.commitWavSegment(sessionId, seq, closed, pcmBytes);
                listener.onSegmentCommitted(seq, closed, pcmBytes);
            } else if (journal != null) {
                journal.close(); journal = null; if (openFile != null) openFile.delete();
            }
        } catch (Exception failure) {
            try {
                if (journal != null && pcmBytes > 0L) {
                    File closed = closeSegment(journal, openFile, seq, pcmBytes); journal = null;
                    store.commitWavSegment(sessionId, seq, closed, pcmBytes);
                    listener.onSegmentCommitted(seq, closed, pcmBytes);
                }
            } catch (Exception ignored) {}
            listener.onFailure(failure.getMessage() == null ? "Recording failed" : failure.getMessage());
        } finally {
            recording.set(false); activeRecord = null;
            try { if (journal != null) journal.close(); } catch (Exception ignored) {}
            if (recorder != null) {
                try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
            }
            route.release(); listener.onStopped(sessionId);
        }
    }

    private static File closeSegment(RandomAccessFile journal, File openFile, int seq, long pcmBytes) throws Exception {
        ReliableSessionStore.writeWavHeader(journal, pcmBytes); journal.getFD().sync(); journal.close();
        File closed = new File(openFile.getParentFile(), String.format(Locale.US, "segment_%06d.wav", seq));
        if (closed.exists() && !closed.delete()) throw new IllegalStateException("Could not replace WAV segment");
        if (!openFile.renameTo(closed)) throw new IllegalStateException("Could not publish WAV segment");
        return closed;
    }
}
