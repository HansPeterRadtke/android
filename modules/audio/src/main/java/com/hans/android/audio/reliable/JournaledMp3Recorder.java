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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loss-averse microphone capture.
 *
 * The urgent AudioRecord thread only reads microphone samples, computes levels,
 * and copies them into RAM. Disk append, fsync, publish, MP3 encoding,
 * diagnostics transmission, and network upload are handled by other threads.
 */
public final class JournaledMp3Recorder {
    public interface Listener {
        void onStarted(String routedDevice);
        void onRecorderEvent(String event, int seq, long bytes,
                             long durationMs, String detail);
        void onAudioLevel(float rmsDbfs, float peakDbfs, long capturedSamples);
        void onJournalCommitted(String sessionId, int seq,
                                File pcmJournal, int inputSampleRate,
                                long pcmBytes, long durationMs);
        void onStopped(String sessionId);
        void onFailure(String stage, String exceptionClass, String message);
    }

    private static final int PREFERRED_INPUT_SAMPLE_RATE = 48000;
    private static final int SECONDARY_INPUT_SAMPLE_RATE = 44100;
    private static final int SPEECH_INPUT_SAMPLE_RATE = 32000;
    private static final int NARROW_INPUT_SAMPLE_RATE = 16000;
    private static final int BLUETOOTH_FALLBACK_INPUT_SAMPLE_RATE = 8000;
    private static final int BLOCK_MS = 50;
    private static final int AUDIO_RECORD_BUFFER_MS = 30_000;
    private static final int PCM_SYNC_MS = 1_000;
    private static final int WRITER_QUEUE_WARN_BLOCKS = 200;
    private static final long WRITER_JOIN_TIMEOUT_MS = 60_000L;
    private static final long NO_DATA_GRACE_MS = 3_000L;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile AudioRecord activeRecord;
    private volatile Thread captureThread;

    public synchronized boolean start(Context context, AudioInputOption input,
                                      ReliableSessionStore store,
                                      String sessionId, Listener listener) {
        if (!recording.compareAndSet(false, true)) return false;
        captureThread = new Thread(() -> capture(
                context.getApplicationContext(), input, store, sessionId,
                listener), "reliable-audio-capture");
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
        return true;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public void stop() {
        recording.set(false);
        AudioRecord value = activeRecord;
        if (value != null) {
            try { value.stop(); }
            catch (Exception ignored) {}
        }
    }

    public boolean awaitStopped(long timeoutMs) {
        Thread value = captureThread;
        if (value == null || value == Thread.currentThread()) return true;
        try {
            value.join(Math.max(0L, timeoutMs));
            return !value.isAlive();
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

    static int captureBufferBytes(int sampleRate, int minimumBytes) {
        long target = Math.max(1L, sampleRate) * 2L
                * AUDIO_RECORD_BUFFER_MS / 1000L;
        return (int)Math.min(Integer.MAX_VALUE,
                Math.max(Math.max(1, minimumBytes) * 2L, target));
    }

    static int syncIntervalMs() {
        return PCM_SYNC_MS;
    }

    static boolean encodesWhileCapturing() {
        return false;
    }

    static boolean captureThreadWritesDisk() {
        return false;
    }

    private void capture(Context context, AudioInputOption input,
                         ReliableSessionStore store, String sessionId,
                         Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRouteController route = new AudioRouteController(context);
        AudioRecord recorder = null;
        AutomaticGainControl automaticGainControl = null;
        DurablePcmJournal journal = null;
        PcmJournalWriter writer = null;
        PcmJournalWriter.Stats writerStats = PcmJournalWriter.Stats.empty();
        int seq = -1;
        int inputSampleRate = 0;
        long capturedSamples = 0L;
        long createdAtMs = 0L;
        String stage = "capture_start";
        boolean failureReported = false;
        try {
            listener.onRecorderEvent("capture.pipeline_start", -1, 0L, 0L,
                    "direct_pcm_journal=true, queue=true, live_mp3=false"
                            + ", block_ms=" + BLOCK_MS
                            + ", pcm_sync_ms=" + PCM_SYNC_MS
                            + ", audio_record_buffer_ms="
                            + AUDIO_RECORD_BUFFER_MS);
            stage = "prepare_audio_route";
            AudioDeviceInfo requested = route.prepare(context, input);
            listener.onRecorderEvent("capture.communication_route", -1,
                    0L, 0L, route.getPreparationDetail());

            stage = "construct_audio_record";
            RecordSetup setup = createAudioRecord(input, listener);
            recorder = setup.recorder;
            inputSampleRate = setup.sampleRate;
            activeRecord = recorder;

            stage = "enable_automatic_gain_control";
            if (AutomaticGainControl.isAvailable()) {
                try {
                    automaticGainControl = AutomaticGainControl.create(
                            recorder.getAudioSessionId());
                    if (automaticGainControl != null) {
                        automaticGainControl.setEnabled(true);
                        listener.onRecorderEvent(
                                "capture.automatic_gain_control", -1,
                                0L, 0L, "available=true, enabled="
                                        + automaticGainControl.getEnabled());
                    }
                } catch (RuntimeException gainFailure) {
                    listener.onRecorderEvent(
                            "capture.automatic_gain_control", -1,
                            0L, 0L, "available=true, enabled=false, error="
                                    + gainFailure.getClass().getSimpleName()
                                    + ": " + gainFailure.getMessage());
                }
            } else {
                listener.onRecorderEvent("capture.automatic_gain_control",
                        -1, 0L, 0L, "available=false");
            }

            stage = "apply_preferred_microphone";
            boolean builtIn = input != null
                    && input.getCategory()
                    == AudioInputOption.Category.BUILT_IN;
            if (!builtIn && !route.applyPreferredDevice(
                    recorder, requested, input)) {
                throw new IllegalStateException(
                        "Android rejected the selected external microphone input");
            }

            stage = "open_direct_pcm_journal";
            store.ensureWritable(8L * 1024L * 1024L);
            seq = store.nextAvailableSegmentSeq(sessionId);
            File directory = store.sessionDirectory(sessionId);
            journal = new DurablePcmJournal(directory, seq,
                    inputSampleRate);
            createdAtMs = System.currentTimeMillis();
            writer = new PcmJournalWriter(journal, seq, inputSampleRate,
                    listener);
            writer.start();
            listener.onRecorderEvent("capture.writer_thread_started", seq,
                    0L, 0L, "queue_unbounded=true, warn_blocks="
                            + WRITER_QUEUE_WARN_BLOCKS
                            + ", thread=reliable-audio-pcm-writer");

            stage = "start_audio_record";
            recorder.startRecording();
            if (recorder.getRecordingState()
                    != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException(
                        "Android did not enter the recording state");
            }
            stage = "wait_for_routed_microphone";
            AudioDeviceInfo routed = route.waitForRoutedInput(
                    recorder, requested, input);
            listener.onRecorderEvent("capture.routed_device", seq, 0L,
                    0L, "routed_device_id="
                            + (routed == null ? -1 : routed.getId())
                            + ", routed_device_type="
                            + (routed == null ? -1 : routed.getType())
                            + ", input_sample_rate=" + inputSampleRate
                            + ", routed_description="
                            + AudioInputCatalog.describe(routed));
            listener.onStarted(AudioInputCatalog.describe(routed));

            int samplesPerBlock = Math.max(1,
                    inputSampleRate * BLOCK_MS / 1000);
            short[] readBuffer = new short[samplesPerBlock];
            long noDataStarted = 0L;
            double levelSquareSum = 0.0;
            int levelPeak = 0;
            long levelSampleCount = 0L;
            long levelTarget = Math.max(1L,
                    inputSampleRate / 10L);

            while (recording.get()) {
                stage = "read_microphone_samples";
                int read = recorder.read(readBuffer, 0,
                        readBuffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    noDataStarted = 0L;
                    for (int i = 0; i < read; i++) {
                        short value = readBuffer[i];
                        int absolute = value == Short.MIN_VALUE
                                ? 32768 : Math.abs((int)value);
                        if (absolute > levelPeak) levelPeak = absolute;
                        levelSquareSum += (double)value * (double)value;
                    }
                    stage = "enqueue_pcm_samples";
                    writer.enqueue(readBuffer, read, capturedSamples);
                    writer.throwIfFailed();
                    capturedSamples += read;
                    levelSampleCount += read;

                    if (levelSampleCount >= levelTarget) {
                        double rms = Math.sqrt(levelSquareSum
                                / Math.max(1L, levelSampleCount)) / 32768.0;
                        double peak = levelPeak / 32768.0;
                        listener.onAudioLevel(
                                (float)(20.0 * Math.log10(
                                        Math.max(1.0e-7, rms))),
                                (float)(20.0 * Math.log10(
                                        Math.max(1.0e-7, peak))),
                                capturedSamples);
                        levelSquareSum = 0.0;
                        levelPeak = 0;
                        levelSampleCount = 0L;
                    }
                } else if (!recording.get()) {
                    break;
                } else if (read == 0) {
                    long now = SystemClock.elapsedRealtime();
                    if (noDataStarted == 0L) noDataStarted = now;
                    if (now - noDataStarted < NO_DATA_GRACE_MS) {
                        SystemClock.sleep(20L);
                        continue;
                    }
                    throw new IllegalStateException(
                            "Android returned zero microphone samples for three seconds after routing completed");
                } else {
                    throw new IllegalStateException(
                            "Android AudioRecord.read failed with code " + read);
                }
            }
        } catch (Throwable problem) {
            boolean expectedStop = !recording.get()
                    && (problem instanceof InterruptedException
                    || Thread.currentThread().isInterrupted()
                    || String.valueOf(problem.getMessage()).contains(
                    "cancelled because Voice Button was closed"));
            if (!expectedStop) {
                recording.set(false);
                failureReported = true;
                listener.onRecorderEvent("capture.exception", seq,
                        capturedSamples * 2L,
                        inputSampleRate <= 0 ? 0L
                                : capturedSamples * 1000L
                                / inputSampleRate,
                        "stage=" + stage + ", exception="
                                + problem.getClass().getName()
                                + ", message="
                                + String.valueOf(problem.getMessage()));
                listener.onFailure(stage, problem.getClass().getName(),
                        problem.getMessage() == null
                                ? "no exception message"
                                : problem.getMessage());
            }
        } finally {
            activeRecord = null;
            if (automaticGainControl != null) {
                try { automaticGainControl.release(); }
                catch (RuntimeException ignored) {}
            }
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState()
                            == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (Exception ignored) {}
                try { recorder.release(); }
                catch (Exception ignored) {}
            }
            route.release();

            if (writer != null) {
                try {
                    stage = "drain_pcm_writer";
                    writerStats = writer.finishAndAwait(WRITER_JOIN_TIMEOUT_MS);
                } catch (Throwable writerFailure) {
                    if (!failureReported) {
                        failureReported = true;
                        listener.onFailure(stage,
                                writerFailure.getClass().getName(),
                                writerFailure.getMessage() == null
                                        ? "no exception message"
                                        : writerFailure.getMessage());
                    }
                }
            }

            if (journal != null) {
                try {
                    stage = "close_direct_pcm_journal";
                    if (capturedSamples > 0L) {
                        File closedFile = journal.publish();
                        journal = null;
                        store.fsyncSessionDirectory(sessionId);
                        long durationMs = inputSampleRate <= 0 ? 0L
                                : capturedSamples * 1000L
                                / inputSampleRate;
                        listener.onJournalCommitted(sessionId, seq,
                                closedFile, inputSampleRate,
                                capturedSamples * 2L, durationMs);
                        listener.onRecorderEvent(
                                "capture.pcm_journal_closed", seq,
                                capturedSamples * 2L, durationMs,
                                "created_at_ms=" + createdAtMs
                                        + ", sync_count="
                                        + writerStats.syncCount
                                        + ", sync_total_ms="
                                        + writerStats.syncTotalMs
                                        + ", sync_max_ms="
                                        + writerStats.syncMaxMs
                                        + ", max_queue_depth="
                                        + writerStats.maxQueueDepth
                                        + ", file="
                                        + closedFile.getName());
                    } else {
                        journal.discard();
                        journal = null;
                    }
                } catch (Throwable closeFailure) {
                    if (journal != null) {
                        try { journal.closePreservingOpenJournal(); }
                        catch (Exception ignored) {}
                    }
                    if (!failureReported) {
                        failureReported = true;
                        listener.onFailure(stage,
                                closeFailure.getClass().getName(),
                                closeFailure.getMessage() == null
                                        ? "no exception message"
                                        : closeFailure.getMessage());
                    }
                }
            }
            recording.set(false);
            listener.onRecorderEvent("capture.pipeline_stop", seq,
                    capturedSamples * 2L,
                    inputSampleRate <= 0 ? 0L
                            : capturedSamples * 1000L / inputSampleRate,
                    "direct_pcm_journal=true, queue=true, failure="
                            + failureReported
                            + ", writer_samples="
                            + writerStats.samplesWritten
                            + ", max_queue_depth="
                            + writerStats.maxQueueDepth);
            listener.onStopped(sessionId);
        }
    }

    private static RecordSetup createAudioRecord(AudioInputOption input,
                                                  Listener listener) {
        int source = input != null && input.isBluetooth()
                ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                : MediaRecorder.AudioSource.MIC;
        StringBuilder failures = new StringBuilder();
        for (int sampleRate : candidateInputSampleRates(input)) {
            int minimum = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                appendFailure(failures,
                        sampleRate + "Hz minBuffer=" + minimum);
                continue;
            }
            int allocated = captureBufferBytes(sampleRate, minimum);
            AudioRecord value = null;
            try {
                value = new AudioRecord(source, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, allocated);
                if (value.getState()
                        == AudioRecord.STATE_INITIALIZED) {
                    listener.onRecorderEvent(
                            "capture.audio_record_created", -1,
                            allocated, 0L, "input_sample_rate="
                                    + sampleRate
                                    + ", allocated_buffer_bytes="
                                    + allocated
                                    + ", allocated_buffer_ms="
                                    + AUDIO_RECORD_BUFFER_MS
                                    + ", source="
                                    + (source
                                    == MediaRecorder.AudioSource.MIC
                                    ? "MIC" : "VOICE_COMMUNICATION"));
                    return new RecordSetup(value, sampleRate);
                }
                appendFailure(failures, sampleRate
                        + "Hz state=" + value.getState());
            } catch (RuntimeException failure) {
                appendFailure(failures, sampleRate + "Hz "
                        + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage());
            }
            if (value != null) {
                try { value.release(); }
                catch (Exception ignored) {}
            }
        }
        throw new IllegalStateException(
                "Android could not initialize the selected microphone: "
                        + failures);
    }

    private static void appendFailure(StringBuilder failures,
                                      String value) {
        if (failures.length() > 0) failures.append("; ");
        failures.append(value);
    }

    private static final class RecordSetup {
        final AudioRecord recorder;
        final int sampleRate;

        RecordSetup(AudioRecord recorder, int sampleRate) {
            this.recorder = recorder;
            this.sampleRate = sampleRate;
        }
    }

    private static final class PcmBlock {
        static final PcmBlock END = new PcmBlock(new short[0], 0);
        final short[] samples;
        final int count;

        private PcmBlock(short[] samples, int count) {
            this.samples = samples;
            this.count = count;
        }

        static PcmBlock copyOf(short[] source, int count) throws IOException {
            if (source == null || count < 0 || count > source.length) {
                throw new IOException("Invalid PCM queue block");
            }
            return new PcmBlock(Arrays.copyOf(source, count), count);
        }
    }

    private static final class PcmJournalWriter {
        private final LinkedBlockingQueue<PcmBlock> queue =
                new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure =
                new AtomicReference<>();
        private final DurablePcmJournal journal;
        private final int sequence;
        private final int sampleRate;
        private final Listener listener;
        private final long syncTargetSamples;
        private final Thread thread;
        private volatile long samplesWritten;
        private volatile long samplesSinceSync;
        private volatile long syncCount;
        private volatile long syncTotalMs;
        private volatile long syncMaxMs;
        private volatile int maxQueueDepth;

        PcmJournalWriter(DurablePcmJournal journal, int sequence,
                         int sampleRate, Listener listener) {
            this.journal = journal;
            this.sequence = sequence;
            this.sampleRate = sampleRate;
            this.listener = listener;
            this.syncTargetSamples = Math.max(1L,
                    sampleRate * PCM_SYNC_MS / 1000L);
            this.thread = new Thread(this::run, "reliable-audio-pcm-writer");
        }

        void start() {
            thread.start();
        }

        void enqueue(short[] samples, int count, long capturedBefore)
                throws IOException {
            throwIfFailed();
            PcmBlock block = PcmBlock.copyOf(samples, count);
            queue.offer(block);
            int depth = queue.size();
            if (depth > maxQueueDepth) maxQueueDepth = depth;
            if (depth >= WRITER_QUEUE_WARN_BLOCKS
                    && depth % WRITER_QUEUE_WARN_BLOCKS == 0) {
                listener.onRecorderEvent("capture.writer_queue_pressure",
                        sequence, capturedBefore * 2L,
                        sampleRate <= 0 ? 0L
                                : capturedBefore * 1000L / sampleRate,
                        "queue_depth_blocks=" + depth
                                + ", queue_depth_ms="
                                + (depth * BLOCK_MS));
            }
        }

        Stats finishAndAwait(long timeoutMs) throws IOException {
            queue.offer(PcmBlock.END);
            long deadline = SystemClock.elapsedRealtime()
                    + Math.max(0L, timeoutMs);
            boolean interrupted = false;
            while (thread.isAlive()) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) break;
                try {
                    thread.join(Math.min(remaining, 250L));
                } catch (InterruptedException interruption) {
                    interrupted = true;
                    Thread.interrupted();
                }
            }
            if (thread.isAlive()) {
                thread.interrupt();
                failure.compareAndSet(null, new IOException(
                        "PCM writer did not drain within " + timeoutMs
                                + " ms"));
            }
            if (interrupted) Thread.currentThread().interrupt();
            throwIfFailed();
            return new Stats(samplesWritten, syncCount, syncTotalMs,
                    syncMaxMs, maxQueueDepth);
        }

        void throwIfFailed() throws IOException {
            Throwable value = failure.get();
            if (value == null) return;
            IOException wrapped = new IOException("PCM writer failed: "
                    + value.getClass().getSimpleName() + ": "
                    + value.getMessage());
            wrapped.initCause(value);
            throw wrapped;
        }

        private void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            try {
                while (true) {
                    PcmBlock block = queue.take();
                    if (block == PcmBlock.END) break;
                    journal.append(block.samples, block.count);
                    samplesWritten += block.count;
                    samplesSinceSync += block.count;
                    if (samplesSinceSync >= syncTargetSamples) {
                        long syncMs = journal.sync();
                        syncCount++;
                        syncTotalMs += syncMs;
                        syncMaxMs = Math.max(syncMaxMs, syncMs);
                        samplesSinceSync = 0L;
                        if (syncMs >= 1000L) {
                            listener.onRecorderEvent(
                                    "capture.slow_pcm_sync", sequence,
                                    samplesWritten * 2L,
                                    sampleRate <= 0 ? 0L
                                            : samplesWritten * 1000L
                                            / sampleRate,
                                    "sync_duration_ms=" + syncMs
                                            + ", queue_depth_blocks="
                                            + queue.size());
                        }
                    }
                }
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        }

        static final class Stats {
            final long samplesWritten;
            final long syncCount;
            final long syncTotalMs;
            final long syncMaxMs;
            final int maxQueueDepth;

            Stats(long samplesWritten, long syncCount, long syncTotalMs,
                  long syncMaxMs, int maxQueueDepth) {
                this.samplesWritten = samplesWritten;
                this.syncCount = syncCount;
                this.syncTotalMs = syncTotalMs;
                this.syncMaxMs = syncMaxMs;
                this.maxQueueDepth = maxQueueDepth;
            }

            static Stats empty() { return new Stats(0L, 0L, 0L, 0L, 0); }
        }
    }
}
