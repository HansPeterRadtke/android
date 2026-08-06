package com.hans.android.audio.reliable;

import android.system.Os;

import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.UUID;

public final class Mp3Converter {
    public static final int BITRATE_KBPS = 96;
    private static final int SAMPLES = 8192;

    public File encodeSegment(File wav, File target) throws IOException {
        return encodeWavs(java.util.Collections.singletonList(wav), target);
    }

    public File encodeWavs(List<File> wavs, File target) throws IOException {
        if (wavs == null || wavs.isEmpty()) throw new IOException("No WAV segments to convert");
        File temp = new File(target.getAbsolutePath() + ".tmp-" + UUID.randomUUID());
        AndroidLame lame = new LameBuilder()
                .setInSampleRate(16000)
                .setOutSampleRate(ReliableSessionManifest.OUTPUT_SAMPLE_RATE)
                .setOutChannels(1)
                .setOutBitrate(BITRATE_KBPS)
                .setQuality(2)
                .build();
        short[] pcm = new short[SAMPLES];
        byte[] bytes = new byte[SAMPLES * 2];
        byte[] mp3 = new byte[7200 + SAMPLES * 3];
        try (FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            for (File wav : wavs) {
                checkInterrupted();
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(wav))) {
                    skipFully(in, 44L);
                    while (true) {
                        checkInterrupted();
                        int count = readSome(in, bytes);
                        if (count <= 0) break;
                        int samples = count / 2;
                        for (int i = 0; i < samples; i++) {
                            int lo = bytes[i * 2] & 0xff;
                            int hi = bytes[i * 2 + 1];
                            pcm[i] = (short)((hi << 8) | lo);
                        }
                        int encoded = lame.encode(pcm, pcm, samples, mp3);
                        if (encoded < 0) throw new IOException("LAME segment encoding failed: " + encoded);
                        if (encoded > 0) out.write(mp3, 0, encoded);
                    }
                }
            }
            checkInterrupted();
            int flushed = lame.flush(mp3);
            if (flushed < 0) throw new IOException("LAME flush failed: " + flushed);
            if (flushed > 0) out.write(mp3, 0, flushed);
            out.flush(); fileOut.getFD().sync();
        } finally {
            lame.close();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace MP3 file");
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("Could not publish MP3 file"); }
        if (target.length() <= 0L) throw new IOException("MP3 conversion produced an empty file");
        return target;
    }

    public File encodeRawPcm(File pcmFile, int inputSampleRate, File target) throws IOException {
        if (pcmFile == null || !pcmFile.isFile() || pcmFile.length() < 2L) {
            throw new IOException("PCM recovery journal is empty");
        }
        int rate = inputSampleRate <= 0 ? 16000 : inputSampleRate;
        File temp = new File(target.getAbsolutePath() + ".tmp-" + UUID.randomUUID());
        AndroidLame lame = new LameBuilder().setInSampleRate(rate).setOutSampleRate(ReliableSessionManifest.OUTPUT_SAMPLE_RATE)
                .setOutChannels(1).setOutBitrate(BITRATE_KBPS).setQuality(2).build();
        short[] samples = new short[SAMPLES];
        byte[] bytes = new byte[SAMPLES * 2];
        byte[] encoded = new byte[7200 + SAMPLES * 3];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(pcmFile));
             FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            while (true) {
                checkInterrupted();
                int count = readSome(in, bytes);
                if (count <= 0) break;
                int sampleCount = count / 2;
                for (int i = 0; i < sampleCount; i++) {
                    int lo = bytes[i * 2] & 0xff;
                    int hi = bytes[i * 2 + 1];
                    samples[i] = (short)((hi << 8) | lo);
                }
                int size = lame.encode(samples, samples, sampleCount, encoded);
                if (size < 0) throw new IOException("LAME PCM recovery failed: " + size);
                if (size > 0) out.write(encoded, 0, size);
            }
            int flushed = lame.flush(encoded);
            if (flushed < 0) throw new IOException("LAME PCM recovery flush failed: " + flushed);
            if (flushed > 0) out.write(encoded, 0, flushed);
            out.flush(); fileOut.getFD().sync();
        } finally {
            lame.close();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace recovered MP3 chunk");
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("Could not publish recovered MP3 chunk"); }
        Mp3Frames.normalizeInPlace(target);
        return target;
    }

    public File concatenateMp3Segments(List<File> segments, File target) throws IOException {
        if (segments == null || segments.isEmpty()) throw new IOException("No MP3 segments to assemble");
        File temp = new File(target.getAbsolutePath() + ".tmp-" + UUID.randomUUID());
        long frames = 0L;
        try (FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            for (File segment : segments) {
                checkInterrupted();
                Mp3Frames.Stats stats = Mp3Frames.copyFrames(segment, out);
                if (stats.frames <= 0L) throw new IOException("A compressed segment has no complete MP3 frames");
                frames += stats.frames;
            }
            checkInterrupted();
            out.flush();
            fileOut.getFD().sync();
        }
        if (frames <= 0L) {
            temp.delete();
            throw new IOException("Final MP3 has no audio frames");
        }
        try {
            if (isAndroidRuntime()) {
                Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
            } else {
                if (target.exists() && !target.delete()) {
                    throw new IOException("Could not replace test MP3 target");
                }
                if (!temp.renameTo(target)) {
                    throw new IOException("Could not publish test MP3 target");
                }
            }
            fsyncDirectory(target.getParentFile());
        } catch (Throwable failure) {
            temp.delete();
            throw new IOException("Could not atomically publish final MP3", failure);
        }
        return target;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("MP3 operation cancelled because Voice Button was closed");
        }
    }

    private static int readSome(BufferedInputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) break;
            if (read == 0) break;
            offset += read;
        }
        return offset & ~1;
    }

    private static void skipFully(BufferedInputStream in, long bytes) throws IOException {
        long skipped = 0L;
        while (skipped < bytes) {
            long count = in.skip(bytes - skipped);
            if (count <= 0L) throw new IOException("Invalid WAV header");
            skipped += count;
        }
    }
    private static void fsyncDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) return;
        try (java.nio.channels.FileChannel channel =
                     java.nio.channels.FileChannel.open(directory.toPath(),
                             java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {}
    }

    private static boolean isAndroidRuntime() {
        String vm = System.getProperty("java.vm.name", "");
        return vm.toLowerCase(java.util.Locale.US).contains("dalvik")
                || vm.toLowerCase(java.util.Locale.US).contains("art");
    }

}
