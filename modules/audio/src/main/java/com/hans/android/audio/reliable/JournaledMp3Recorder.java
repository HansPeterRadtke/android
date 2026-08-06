package com.hans.android.audio.reliable;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.os.SystemClock;

import com.hans.android.audio.AudioInputCatalog;
import com.hans.android.audio.AudioInputOption;
import com.hans.android.audio.AudioRouteController;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loss-averse microphone capture.
 *
 * The AudioRecord thread writes raw PCM directly to short append-only journals.
 * Rotation makes closed chunks available for background MP3 encoding and upload
 * while the microphone keeps running. The capture thread itself never waits for
 * MP3 encoding, diagnostics transmission, or network upload. Journals are
 * synchronized at a bounded interval and are discoverable from their filenames
 * after process death.
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
    private static final int BLOCK_MS = 50;
    private static final int AUDIO_RECORD_BUFFER_MS = 200;
    private static final int PCM_SYNC_MS = 1_000;
    private static final int LIVE_JOURNAL_SEGMENT_MS = 2_000;
    private static final long NO_DATA_GRACE_MS = 3_000L;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private volatile AudioRecord activeRecord;
    private volatile Thread captureThread;

    public synchronized boolean start(Context context, AudioInputOption input,
                                      ReliableSessionStore store,
                                      String sessionId, int enhancementLevel,
                                      Listener listener) {
        if (!recording.compareAndSet(false, true)) return false;
        int safeEnhancementLevel = SpeechEnhancer.clampLevel(enhancementLevel);
        captureThread = new Thread(() -> capture(
                context.getApplicationContext(), input, store, sessionId,
                safeEnhancementLevel, listener), "reliable-audio-direct-journal");
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
        Thread valueThread = captureThread;
        if (valueThread != null) valueThread.interrupt();
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
                return new int[] {NARROW_INPUT_SAMPLE_RATE};
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
        return true;
    }

    private void capture(Context context, AudioInputOption input,
                         ReliableSessionStore store, String sessionId,
                         int enhancementLevel, Listener listener) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRouteController route = new AudioRouteController(context);
        AudioRecord recorder = null;
        AutomaticGainControl automaticGainControl = null;
        NoiseSuppressor noiseSuppressor = null;
        SpeechEnhancer enhancer = new SpeechEnhancer(enhancementLevel);
        DurablePcmJournal journal = null;
        int seq = -1;
        int inputSampleRate = 0;
        long capturedSamples = 0L;
        long segmentSamples = 0L;
        long samplesSinceSync = 0L;
        long syncCount = 0L;
        long syncTotalMs = 0L;
        long syncMaxMs = 0L;
        long createdAtMs = 0L;
        String stage = "capture_start";
        boolean failureReported = false;
        try {
            listener.onRecorderEvent("capture.pipeline_start", -1, 0L, 0L,
                    "direct_pcm_journal=true, queue=false, live_mp3=true"
                            + ", enhancement=" + SpeechEnhancer.name(enhancementLevel)
                            + ", software_gain=true"
                            + ", software_noise_gate=true"
                            + ", software_high_pass=true"
                            + ", block_ms=" + BLOCK_MS
                            + ", pcm_sync_ms=" + PCM_SYNC_MS
                            + ", live_journal_segment_ms="
                            + LIVE_JOURNAL_SEGMENT_MS
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

            stage = "enable_speech_capture_processing";
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
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(
                            recorder.getAudioSessionId());
                    if (noiseSuppressor != null) {
                        noiseSuppressor.setEnabled(true);
                        listener.onRecorderEvent(
                                "capture.noise_suppressor", -1,
                                0L, 0L, "available=true, enabled="
                                        + noiseSuppressor.getEnabled());
                    }
                } catch (RuntimeException noiseFailure) {
                    listener.onRecorderEvent(
                            "capture.noise_suppressor", -1,
                            0L, 0L, "available=true, enabled=false, error="
                                    + noiseFailure.getClass().getSimpleName()
                                    + ": " + noiseFailure.getMessage());
                }
            } else {
                listener.onRecorderEvent("capture.noise_suppressor",
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
            long syncTarget = Math.max(1L,
                    inputSampleRate * PCM_SYNC_MS / 1000L);
            long liveSegmentTarget = Math.max(1L,
                    inputSampleRate * LIVE_JOURNAL_SEGMENT_MS / 1000L);

            while (recording.get()) {
                stage = "read_microphone_samples";
                int read = recorder.read(readBuffer, 0,
                        readBuffer.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    noDataStarted = 0L;
                    enhancer.process(readBuffer, read);
                    for (int i = 0; i < read; i++) {
                        short value = readBuffer[i];
                        int absolute = value == Short.MIN_VALUE
                                ? 32768 : Math.abs((int)value);
                        if (absolute > levelPeak) levelPeak = absolute;
                        levelSquareSum += (double)value * (double)value;
                    }
                    stage = "append_enhanced_pcm_journal";
                    journal.append(readBuffer, read);
                    capturedSamples += read;
                    segmentSamples += read;
                    samplesSinceSync += read;
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

                    if (samplesSinceSync >= syncTarget) {
                        stage = "sync_direct_pcm_journal";
                        long syncMs = journal.sync();
                        syncCount++;
                        syncTotalMs += syncMs;
                        syncMaxMs = Math.max(syncMaxMs, syncMs);
                        samplesSinceSync = 0L;
                        if (syncMs >= 1000L) {
                            listener.onRecorderEvent(
                                    "capture.slow_pcm_sync", seq,
                                    segmentSamples * 2L,
                                    segmentSamples * 1000L
                                            / Math.max(1, inputSampleRate),
                                    "sync_duration_ms=" + syncMs);
                        }
                    }
                    if (segmentSamples >= liveSegmentTarget) {
                        stage = "rotate_live_pcm_journal";
                        File closedFile = journal.publish();
                        journal = null;
                        store.fsyncSessionDirectory(sessionId);
                        long durationMs = inputSampleRate <= 0 ? 0L
                                : segmentSamples * 1000L / inputSampleRate;
                        listener.onJournalCommitted(sessionId, seq,
                                closedFile, inputSampleRate,
                                segmentSamples * 2L, durationMs);
                        listener.onRecorderEvent(
                                "capture.pcm_journal_rotated", seq,
                                segmentSamples * 2L, durationMs,
                                "created_at_ms=" + createdAtMs
                                        + ", sync_count=" + syncCount
                                        + ", sync_total_ms=" + syncTotalMs
                                        + ", sync_max_ms=" + syncMaxMs
                                        + ", file="
                                        + closedFile.getName());
                        seq++;
                        journal = new DurablePcmJournal(directory, seq,
                                inputSampleRate);
                        createdAtMs = System.currentTimeMillis();
                        segmentSamples = 0L;
                        samplesSinceSync = 0L;
                        syncCount = 0L;
                        syncTotalMs = 0L;
                        syncMaxMs = 0L;
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
                // Alarm/recovery is notified immediately, before final sync.
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
            if (noiseSuppressor != null) {
                try { noiseSuppressor.release(); }
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

            if (journal != null) {
                try {
                    stage = "close_direct_pcm_journal";
                    if (segmentSamples > 0L) {
                        File closedFile = journal.publish();
                        journal = null;
                        store.fsyncSessionDirectory(sessionId);
                        long durationMs = inputSampleRate <= 0 ? 0L
                                : segmentSamples * 1000L
                                / inputSampleRate;
                        listener.onJournalCommitted(sessionId, seq,
                                closedFile, inputSampleRate,
                                segmentSamples * 2L, durationMs);
                        listener.onRecorderEvent(
                                "capture.pcm_journal_closed", seq,
                                segmentSamples * 2L, durationMs,
                                "created_at_ms=" + createdAtMs
                                        + ", sync_count=" + syncCount
                                        + ", sync_total_ms=" + syncTotalMs
                                        + ", sync_max_ms=" + syncMaxMs
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
                    "direct_pcm_journal=true, live_mp3=true, enhancement="
                            + SpeechEnhancer.name(enhancementLevel)
                            + ", failure=" + failureReported);
            listener.onStopped(sessionId);
        }
    }

    private static RecordSetup createAudioRecord(AudioInputOption input,
                                                  Listener listener) {
        int source = MediaRecorder.AudioSource.VOICE_RECOGNITION;
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
                                    + "VOICE_RECOGNITION");
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

    public static String enhancementName(int level) { return SpeechEnhancer.name(level); }

    private static final class SpeechEnhancer {
        private static final double[] TARGET_RMS = {0.0, 0.13, 0.18, 0.24};
        private static final double[] MAX_GAIN = {1.0, 3.0, 6.0, 10.0};
        private static final double[] NOISE_ATTENUATION = {1.0, 0.70, 0.45, 0.25};
        private static final double SPEECH_RMS_FLOOR = 0.004;
        private static final double SPEECH_PEAK_FLOOR = 0.018;
        private static final double MIN_GAIN = 0.65;
        private final int level;
        private double highPassPreviousInput;
        private double highPassPreviousOutput;
        private double gain = 1.0;

        SpeechEnhancer(int level) { this.level = clampLevel(level); }

        static int clampLevel(int level) {
            if (level < 0) return 0;
            if (level > 3) return 3;
            return level;
        }

        static String name(int level) {
            switch (clampLevel(level)) {
                case 0: return "off";
                case 1: return "natural";
                case 3: return "maximum";
                default: return "strong";
            }
        }

        void process(short[] samples, int count) {
            if (samples == null || count <= 0 || level == 0) return;
            double squareSum = 0.0;
            int peak = 0;
            for (int i = 0; i < count; i++) {
                int value = samples[i];
                int absolute = value == Short.MIN_VALUE
                        ? 32768 : Math.abs(value);
                if (absolute > peak) peak = absolute;
                double normalized = value / 32768.0;
                squareSum += normalized * normalized;
            }
            double rms = Math.sqrt(squareSum / Math.max(1, count));
            double peakRatio = peak / 32768.0;
            boolean speechLikely = rms >= SPEECH_RMS_FLOOR
                    || peakRatio >= SPEECH_PEAK_FLOOR;
            double target = speechLikely
                    ? TARGET_RMS[level] / Math.max(SPEECH_RMS_FLOOR, rms)
                    : 1.0;
            target = Math.max(MIN_GAIN, Math.min(MAX_GAIN[level], target));
            double smoothing = target < gain ? 0.45 : 0.14;
            gain += (target - gain) * smoothing;
            double noiseAttenuation = speechLikely ? 1.0 : NOISE_ATTENUATION[level];
            for (int i = 0; i < count; i++) {
                double input = samples[i] / 32768.0;
                double filtered = input - highPassPreviousInput
                        + 0.995 * highPassPreviousOutput;
                highPassPreviousInput = input;
                highPassPreviousOutput = filtered;
                double value = filtered * gain * noiseAttenuation;
                value = softLimit(value);
                int pcm = (int)Math.round(value * 32767.0);
                if (pcm > Short.MAX_VALUE) pcm = Short.MAX_VALUE;
                else if (pcm < Short.MIN_VALUE) pcm = Short.MIN_VALUE;
                samples[i] = (short)pcm;
            }
        }

        private static double softLimit(double value) {
            double limited = Math.tanh(value * 1.15) / Math.tanh(1.15);
            if (limited > 0.98) return 0.98;
            if (limited < -0.98) return -0.98;
            return limited;
        }
    }

    private static final class RecordSetup {
        final AudioRecord recorder;
        final int sampleRate;

        RecordSetup(AudioRecord recorder, int sampleRate) {
            this.recorder = recorder;
            this.sampleRate = sampleRate;
        }
    }
}
