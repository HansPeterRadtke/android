package com.hans.android.audio.reliable;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.os.Process;
import android.os.SystemClock;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.AudioRouteController;
import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class JournaledMp3Recorder {
    public interface Listener {
        void onStarted(String routedDevice);
        void onRecorderEvent(String event, int seq, long bytes, long durationMs, String detail);
        void onAudioLevel(float rmsDbfs, float peakDbfs, long capturedSamples);
        void onSegmentCommitted(int seq, File mp3, long durationMs);
        void onStopped(String sessionId);
        void onFailure(String stage, String exceptionClass, String message);
    }

    private static final int OUTPUT_SAMPLE_RATE = ReliableSessionManifest.OUTPUT_SAMPLE_RATE;
    private static final int PREFERRED_INPUT_SAMPLE_RATE = 48000;
    private static final int SECONDARY_INPUT_SAMPLE_RATE = 44100;
    private static final int SPEECH_INPUT_SAMPLE_RATE = 32000;
    private static final int NARROW_INPUT_SAMPLE_RATE = 16000;
    private static final int BLUETOOTH_FALLBACK_INPUT_SAMPLE_RATE = 8000;
    private static final int BLOCK_MS = 50;
    private static final int CHUNK_MS = 2000;
    private static final int PCM_SYNC_MS = 100;
    private static final int QUEUE_SECONDS = 120;
    private static final long NO_DATA_GRACE_MS = 3000L;

    private static final PcmBlock END = new PcmBlock(new short[0], 0, 0L, true);

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile AudioRecord activeRecord;
    private volatile Thread captureThread;
    private volatile Thread writerThread;
    private volatile ArrayBlockingQueue<PcmBlock> queue;

    public synchronized boolean start(Context context, AudioInputOption input,
                                      ReliableSessionStore store, String sessionId,
                                      Listener listener) {
        if (!recording.compareAndSet(false, true)) return false;
        failure.set(null);
        int blocks = Math.max(40, QUEUE_SECONDS * 1000 / BLOCK_MS);
        queue = new ArrayBlockingQueue<>(blocks);
        captureThread = new Thread(() -> capture(context.getApplicationContext(), input,
                store, sessionId, listener), "reliable-audio-capture");
        captureThread.start();
        return true;
    }

    public boolean isRecording() { return recording.get(); }

    public void stop() {
        recording.set(false);
        AudioRecord value = activeRecord;
        if (value != null) try { value.stop(); } catch (Exception ignored) {}
        Thread valueThread = captureThread;
        if (valueThread != null) valueThread.interrupt();
    }

    public boolean awaitStopped(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        if (!joinUntil(captureThread, deadline)) return false;
        return joinUntil(writerThread, deadline);
    }

    private static boolean joinUntil(Thread thread, long deadline) {
        if (thread == null || thread == Thread.currentThread()) return true;
        long remaining = Math.max(0L, deadline - SystemClock.elapsedRealtime());
        try {
            thread.join(remaining);
            return !thread.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static int[] candidateInputSampleRates(AudioInputOption input) {
        if (input != null && input.isBluetooth()) {
            if (input.getDeviceType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return new int[] {NARROW_INPUT_SAMPLE_RATE,
                        BLUETOOTH_FALLBACK_INPUT_SAMPLE_RATE};
            }
            return new int[] {PREFERRED_INPUT_SAMPLE_RATE,
                    SPEECH_INPUT_SAMPLE_RATE, NARROW_INPUT_SAMPLE_RATE};
        }
        return new int[] {PREFERRED_INPUT_SAMPLE_RATE,
                SECONDARY_INPUT_SAMPLE_RATE, SPEECH_INPUT_SAMPLE_RATE,
                NARROW_INPUT_SAMPLE_RATE};
    }

    private void capture(Context context, AudioInputOption input,
                         ReliableSessionStore store, String sessionId,
                         Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRouteController route = new AudioRouteController(context);
        AudioRecord recorder = null;
        AutomaticGainControl automaticGainControl = null;
        String stage = "capture_start";
        long blockIndex = 0L;
        try {
            listener.onRecorderEvent("capture.pipeline_start", -1, 0L, 0L,
                    "block_ms=" + BLOCK_MS + ", chunk_ms=" + CHUNK_MS
                            + ", queue_seconds=" + QUEUE_SECONDS
                            + ", pcm_sync_ms=" + PCM_SYNC_MS);
            stage = "prepare_audio_route";
            AudioDeviceInfo requested = route.prepare(context, input);
            listener.onRecorderEvent("capture.communication_route", -1, 0L, 0L,
                    route.getPreparationDetail());
            stage = "construct_audio_record";
            RecordSetup setup = createAudioRecord(input, listener);
            recorder = setup.recorder;
            activeRecord = recorder;
            stage = "enable_automatic_gain_control";
            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(recorder.getAudioSessionId());
                    if (automaticGainControl != null) {
                        automaticGainControl.setEnabled(true);
                        listener.onRecorderEvent("capture.automatic_gain_control", -1, 0L, 0L,
                                "available=true, enabled=" + automaticGainControl.getEnabled());
                    }
                } catch (RuntimeException gainFailure) {
                    listener.onRecorderEvent("capture.automatic_gain_control", -1, 0L, 0L,
                            "available=true, enabled=false, error=" + gainFailure.getClass().getSimpleName()
                                    + ": " + gainFailure.getMessage());
                }
            } else {
                listener.onRecorderEvent("capture.automatic_gain_control", -1, 0L, 0L,
                        "available=false");
            }
            stage = "apply_preferred_microphone";
            boolean builtIn = input != null
                    && input.getCategory() == AudioInputOption.Category.BUILT_IN;
            if (!builtIn && !route.applyPreferredDevice(recorder, requested, input)) {
                throw new IllegalStateException("Android rejected the selected external microphone input");
            }
            stage = "start_audio_record";
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Android did not enter the recording state");
            }
            stage = "wait_for_routed_microphone";
            AudioDeviceInfo routed = route.waitForRoutedInput(recorder, requested, input);
            listener.onRecorderEvent("capture.routed_device", -1, 0L, 0L,
                    "routed_device_id=" + (routed == null ? -1 : routed.getId())
                            + ", routed_device_type=" + (routed == null ? -1 : routed.getType())
                            + ", input_sample_rate=" + setup.sampleRate
                            + ", routed_description=" + AudioInputCatalog.describe(routed));

            writerThread = new Thread(() -> writePipeline(store, sessionId,
                    setup.sampleRate, listener), "reliable-audio-durable-writer");
            writerThread.start();
            listener.onStarted(AudioInputCatalog.describe(routed));

            int samplesPerBlock = Math.max(1, setup.sampleRate * BLOCK_MS / 1000);
            short[] readBuffer = new short[samplesPerBlock];
            long noDataStarted = 0L;
            while (recording.get() && failure.get() == null) {
                stage = "read_microphone_samples";
                int read = recorder.read(readBuffer, 0, readBuffer.length,
                        AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    noDataStarted = 0L;
                    short[] copy = Arrays.copyOf(readBuffer, read);
                    PcmBlock block = new PcmBlock(copy, read, blockIndex++, false);
                    stage = "enqueue_pcm_block";
                    enqueueCapturedBlock(block);
                } else if (!recording.get()) {
                    break;
                } else if (read == 0) {
                    long now = SystemClock.elapsedRealtime();
                    if (noDataStarted == 0L) noDataStarted = now;
                    if (now - noDataStarted < NO_DATA_GRACE_MS) {
                        SystemClock.sleep(20L);
                        continue;
                    }
                    throw new IllegalStateException("Android returned zero microphone samples for three seconds after routing completed");
                } else {
                    throw new IllegalStateException("Android AudioRecord.read failed with code " + read);
                }
            }
        } catch (Throwable problem) {
            boolean expectedStop = !recording.get()
                    && (problem instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()
                    || String.valueOf(problem.getMessage()).contains("cancelled because Voice Button was closed"));
            if (!expectedStop) {
                failure.compareAndSet(null, problem);
                recording.set(false);
                listener.onRecorderEvent("capture.exception", -1, 0L, 0L,
                        "stage=" + stage + ", exception=" + problem.getClass().getName()
                                + ", message=" + String.valueOf(problem.getMessage()));
            }
        } finally {
            activeRecord = null;
            if (automaticGainControl != null) {
                try { automaticGainControl.release(); } catch (RuntimeException ignored) {}
            }
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
                } catch (Exception ignored) {}
                try { recorder.release(); } catch (Exception ignored) {}
            }
            route.release();
            offerEnd();
            joinUntil(writerThread, SystemClock.elapsedRealtime() + 45000L);
            recording.set(false);
            Throwable problem = failure.get();
            if (problem != null) {
                listener.onFailure(stage, problem.getClass().getName(),
                        problem.getMessage() == null ? "no exception message" : problem.getMessage());
            }
            listener.onRecorderEvent("capture.pipeline_stop", -1, 0L, 0L,
                    "blocks_captured=" + blockIndex
                            + ", failure=" + (problem == null ? "none" : problem.getClass().getSimpleName()));
            listener.onStopped(sessionId);
        }
    }

    private void enqueueCapturedBlock(PcmBlock block) throws Exception {
        boolean interruptedDuringStop = false;
        while (true) {
            Throwable writerFailure = failure.get();
            if (writerFailure != null) throw new IllegalStateException(
                    "The durable writer failed before accepting a captured PCM block", writerFailure);
            Thread writer = writerThread;
            if (writer != null && !writer.isAlive()) {
                throw new IllegalStateException("The durable writer stopped before accepting a captured PCM block");
            }
            try {
                if (queue.offer(block, 500L, TimeUnit.MILLISECONDS)) {
                    if (interruptedDuringStop) Thread.currentThread().interrupt();
                    return;
                }
            } catch (InterruptedException interrupted) {
                if (recording.get()) throw interrupted;
                interruptedDuringStop = true;
                Thread.interrupted();
                continue;
            }
            if (recording.get()) {
                throw new IllegalStateException("The durable writer queue remained full for half a second; two minutes of bounded backlog protection were exhausted");
            }
            // Pause, Finish, or Close may interrupt this wait. The PCM block was already
            // returned by AudioRecord, so it must be enqueued before the end marker.
        }
    }

    private void offerEnd() {
        ArrayBlockingQueue<PcmBlock> value = queue;
        if (value == null) return;
        while (!value.offer(END)) {
            if (writerThread == null || !writerThread.isAlive()) return;
            try { Thread.sleep(10L); }
            catch (InterruptedException ignored) { Thread.interrupted(); }
        }
    }

    private void writePipeline(ReliableSessionStore store, String sessionId,
                               int inputSampleRate, Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        ChunkWriter chunk = null;
        int seq = 0;
        long sessionOutputSample = 0L;
        String stage = "writer_start";
        try {
            ReliableSessionManifest manifest = store.load(sessionId);
            seq = manifest.nextSeq;
            sessionOutputSample = manifest.totalOutputSamples;
            long targetInputSamples = inputSampleRate * CHUNK_MS / 1000L;
            PcmBlock pending = null;
            int pendingOffset = 0;
            while (true) {
                PcmBlock block;
                if (pending != null) block = pending;
                else block = queue.take();
                if (block.end) break;
                if (chunk == null) {
                    stage = "open_durable_chunk";
                    chunk = new ChunkWriter(store, sessionId, seq, inputSampleRate,
                            sessionOutputSample, listener);
                }
                int remaining = block.count - pendingOffset;
                int capacity = (int)Math.min(Integer.MAX_VALUE,
                        targetInputSamples - chunk.inputSamples);
                int consume = Math.min(remaining, capacity);
                stage = "write_pcm_and_mp3";
                chunk.write(block.samples, pendingOffset, consume);
                pendingOffset += consume;
                if (chunk.inputSamples >= targetInputSamples) {
                    stage = "commit_durable_chunk";
                    ChunkResult result = chunk.closeAndCommit();
                    listener.onSegmentCommitted(seq, result.mp3, result.durationMs);
                    sessionOutputSample = result.endSample;
                    seq++;
                    chunk = null;
                }
                if (pendingOffset < block.count) pending = block;
                else { pending = null; pendingOffset = 0; }
            }
            if (chunk != null && chunk.inputSamples > 0L) {
                stage = "commit_final_durable_chunk";
                ChunkResult result = chunk.closeAndCommit();
                listener.onSegmentCommitted(seq, result.mp3, result.durationMs);
                chunk = null;
            } else if (chunk != null) {
                chunk.abort();
                chunk = null;
            }
        } catch (Throwable problem) {
            if (chunk != null) try { chunk.preserveForRecovery(); } catch (Exception ignored) {}
            failure.compareAndSet(null, problem);
            recording.set(false);
            listener.onRecorderEvent("writer.exception", seq, 0L, 0L,
                    "stage=" + stage + ", exception=" + problem.getClass().getName()
                            + ", message=" + String.valueOf(problem.getMessage()));
            AudioRecord value = activeRecord;
            if (value != null) try { value.stop(); } catch (Exception ignored) {}
        }
    }

    private static RecordSetup createAudioRecord(AudioInputOption input, Listener listener) {
        int source = input != null && input.isBluetooth()
                ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.MIC;
        StringBuilder failures = new StringBuilder();
        for (int sampleRate : candidateInputSampleRates(input)) {
            int minimum = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                appendFailure(failures, sampleRate + "Hz minBuffer=" + minimum);
                continue;
            }
            int allocated = Math.max(minimum * 2, sampleRate * 2 * 4);
            AudioRecord value = null;
            try {
                value = new AudioRecord(source, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, allocated);
                if (value.getState() == AudioRecord.STATE_INITIALIZED) {
                    listener.onRecorderEvent("capture.audio_record_created", -1,
                            allocated, 0L, "input_sample_rate=" + sampleRate
                                    + ", allocated_buffer_bytes=" + allocated
                                    + ", source=" + (source == MediaRecorder.AudioSource.MIC
                                    ? "MIC" : "VOICE_COMMUNICATION"));
                    return new RecordSetup(value, sampleRate);
                }
                appendFailure(failures, sampleRate + "Hz state=" + value.getState());
            } catch (RuntimeException failure) {
                appendFailure(failures, sampleRate + "Hz "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            if (value != null) try { value.release(); } catch (Exception ignored) {}
        }
        throw new IllegalStateException("Android could not initialize the selected microphone: " + failures);
    }

    private static void appendFailure(StringBuilder failures, String value) {
        if (failures.length() > 0) failures.append("; ");
        failures.append(value);
    }

    private static final class PcmBlock {
        final short[] samples;
        final int count;
        final long index;
        final boolean end;
        PcmBlock(short[] samples, int count, long index, boolean end) {
            this.samples = samples; this.count = count; this.index = index; this.end = end;
        }
    }

    private static final class RecordSetup {
        final AudioRecord recorder;
        final int sampleRate;
        RecordSetup(AudioRecord recorder, int sampleRate) {
            this.recorder = recorder; this.sampleRate = sampleRate;
        }
    }

    private static final class ChunkResult {
        final File mp3;
        final long durationMs;
        final long endSample;
        ChunkResult(File mp3, long durationMs, long endSample) {
            this.mp3 = mp3; this.durationMs = durationMs; this.endSample = endSample;
        }
    }

    private static final class ChunkWriter {
        private final ReliableSessionStore store;
        private final String sessionId;
        private final int seq;
        private final int inputSampleRate;
        private final long startOutputSample;
        private final Listener listener;
        private final long createdAtMs;
        private final File pcmOpen;
        private final File mp3Open;
        private final FileOutputStream pcmOut;
        private final FileOutputStream mp3Out;
        private final AndroidLame lame;
        private final byte[] mp3Buffer = new byte[16384];
        private long inputSamples;
        private long samplesSinceSync;
        private double levelSquareSum;
        private int levelPeak;
        private long levelSampleCount;
        private float encodingGain = 1.0f;
        private boolean closed;

        ChunkWriter(ReliableSessionStore store, String sessionId, int seq,
                    int inputSampleRate, long startOutputSample,
                    Listener listener) throws Exception {
            this.store = store; this.sessionId = sessionId; this.seq = seq;
            this.inputSampleRate = inputSampleRate;
            this.startOutputSample = startOutputSample;
            this.listener = listener;
            createdAtMs = System.currentTimeMillis();
            store.ensureWritable(2L * 1024L * 1024L);
            File directory = store.sessionDirectory(sessionId);
            pcmOpen = new File(directory, String.format(Locale.US,
                    "segment_%06d_%05d.open.pcm", seq, inputSampleRate));
            mp3Open = new File(directory, String.format(Locale.US,
                    "segment_%06d.open.mp3", seq));
            pcmOut = new FileOutputStream(pcmOpen, false);
            mp3Out = new FileOutputStream(mp3Open, false);
            lame = new LameBuilder().setInSampleRate(inputSampleRate)
                    .setOutSampleRate(OUTPUT_SAMPLE_RATE).setOutChannels(1)
                    .setOutBitrate(Mp3Converter.BITRATE_KBPS).setQuality(2).build();
            listener.onRecorderEvent("writer.chunk_open", seq, 0L, 0L,
                    "input_sample_rate=" + inputSampleRate
                            + ", output_sample_rate=" + OUTPUT_SAMPLE_RATE
                            + ", bitrate_kbps=" + Mp3Converter.BITRATE_KBPS
                            + ", adaptive_gain_max_db=12"
                            + ", start_sample=" + startOutputSample);
        }

        void write(short[] samples, int offset, int count) throws Exception {
            byte[] pcmBytes = new byte[count * 2];
            short[] encode = new short[count];
            int blockPeak = 0;
            for (int i = 0; i < count; i++) {
                short value = samples[offset + i];
                int absolute = value == Short.MIN_VALUE ? 32768 : Math.abs((int)value);
                if (absolute > blockPeak) blockPeak = absolute;
                if (absolute > levelPeak) levelPeak = absolute;
                levelSquareSum += (double)value * (double)value;
                levelSampleCount++;
            }
            float blockPeakRatio = blockPeak / 32768.0f;
            float targetGain = blockPeakRatio <= 0.0001f
                    ? 4.0f : Math.max(1.0f, Math.min(4.0f, 0.80f / blockPeakRatio));
            if (targetGain < encodingGain) encodingGain = targetGain;
            else encodingGain += (targetGain - encodingGain) * 0.10f;
            for (int i = 0; i < count; i++) {
                short value = samples[offset + i];
                pcmBytes[i * 2] = (byte)(value & 0xff);
                pcmBytes[i * 2 + 1] = (byte)((value >>> 8) & 0xff);
                int amplified = Math.round(value * encodingGain);
                if (amplified > Short.MAX_VALUE) amplified = Short.MAX_VALUE;
                else if (amplified < Short.MIN_VALUE) amplified = Short.MIN_VALUE;
                encode[i] = (short)amplified;
            }
            pcmOut.write(pcmBytes);
            int encoded = lame.encode(encode, encode, count, mp3Buffer);
            if (encoded < 0) throw new IOException("LAME encoding failed: " + encoded);
            if (encoded > 0) mp3Out.write(mp3Buffer, 0, encoded);
            inputSamples += count;
            samplesSinceSync += count;
            long levelTarget = Math.max(1L, inputSampleRate / 10L);
            if (levelSampleCount >= levelTarget) {
                double rms = Math.sqrt(levelSquareSum / Math.max(1L, levelSampleCount)) / 32768.0;
                double peak = levelPeak / 32768.0;
                float rmsDbfs = (float)(20.0 * Math.log10(Math.max(1.0e-7, rms)));
                float peakDbfs = (float)(20.0 * Math.log10(Math.max(1.0e-7, peak)));
                listener.onAudioLevel(rmsDbfs, peakDbfs, inputSamples);
                levelSquareSum = 0.0;
                levelPeak = 0;
                levelSampleCount = 0L;
            }
            long syncTarget = Math.max(1L, inputSampleRate * PCM_SYNC_MS / 1000L);
            if (samplesSinceSync >= syncTarget) sync();
        }

        private void sync() throws Exception {
            long started = SystemClock.elapsedRealtime();
            pcmOut.flush(); pcmOut.getFD().sync();
            mp3Out.flush(); mp3Out.getFD().sync();
            samplesSinceSync = 0L;
            listener.onRecorderEvent("writer.chunk_sync", seq,
                    pcmOpen.length() + mp3Open.length(),
                    inputSamples * 1000L / inputSampleRate,
                    "sync_duration_ms=" + Math.max(0L,
                            SystemClock.elapsedRealtime() - started));
        }

        ChunkResult closeAndCommit() throws Exception {
            if (closed) throw new IOException("Chunk already closed");
            int flushed = lame.flush(mp3Buffer);
            if (flushed < 0) throw new IOException("LAME flush failed: " + flushed);
            if (flushed > 0) mp3Out.write(mp3Buffer, 0, flushed);
            sync();
            pcmOut.close(); mp3Out.close(); lame.close(); closed = true;
            File mp3 = new File(mp3Open.getParentFile(),
                    String.format(Locale.US, "segment_%06d.mp3", seq));
            if (mp3.exists() && !mp3.delete()) throw new IOException("Could not replace MP3 chunk");
            if (!mp3Open.renameTo(mp3)) throw new IOException("Could not publish MP3 chunk");
            store.fsyncSessionDirectory(sessionId);
            Mp3Frames.Stats stats = Mp3Frames.normalizeInPlace(mp3);
            store.fsyncSessionDirectory(sessionId);
            long logicalOutputSamples = inputSamples * OUTPUT_SAMPLE_RATE / inputSampleRate;
            long endOutputSample = startOutputSample + logicalOutputSamples;
            long durationMs = logicalOutputSamples * 1000L / OUTPUT_SAMPLE_RATE;
            long closedAtMs = System.currentTimeMillis();
            store.commitMp3Segment(sessionId, seq, mp3, durationMs,
                    startOutputSample, endOutputSample, OUTPUT_SAMPLE_RATE,
                    createdAtMs, closedAtMs, closedAtMs);
            if (!pcmOpen.delete()) {
                listener.onRecorderEvent("writer.pcm_cleanup_deferred", seq,
                        pcmOpen.length(), durationMs, pcmOpen.getName());
            }
            listener.onRecorderEvent("writer.chunk_committed", seq,
                    mp3.length(), durationMs,
                    "start_sample=" + startOutputSample + ", end_sample=" + endOutputSample
                            + ", frames=" + stats.frames
                            + ", final_encoding_gain=" + encodingGain
                            + ", sha256=" + ReliableSessionStore.sha256File(mp3));
            return new ChunkResult(mp3, durationMs, endOutputSample);
        }

        void preserveForRecovery() throws Exception {
            if (closed) return;
            try { sync(); } catch (Exception ignored) {}
            try { pcmOut.close(); } catch (Exception ignored) {}
            try { mp3Out.close(); } catch (Exception ignored) {}
            try { lame.close(); } catch (Exception ignored) {}
            closed = true;
        }

        void abort() throws Exception {
            if (!closed) {
                try { pcmOut.close(); } catch (Exception ignored) {}
                try { mp3Out.close(); } catch (Exception ignored) {}
                try { lame.close(); } catch (Exception ignored) {}
                closed = true;
            }
            pcmOpen.delete(); mp3Open.delete();
        }
    }
}
