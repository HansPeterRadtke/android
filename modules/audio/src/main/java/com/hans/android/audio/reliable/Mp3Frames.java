package com.hans.android.audio.reliable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public final class Mp3Frames {
    // Least common multiple of the nine MPEG Layer III sample rates:
    // 8, 11.025, 12, 16, 22.05, 24, 32, 44.1, and 48 kHz.
    // One frame contributes samplesPerFrame * (ticksPerSecond / sampleRate)
    // EXACT integer ticks, even for mixed-rate streams and VBR MP3.
    // This is a codec timebase, never a fixed assumed recording sample rate.
    private static final long MPEG_TICKS_PER_SECOND = 14_112_000L;
    private static final long MPEG_TICKS_PER_MILLISECOND =
            MPEG_TICKS_PER_SECOND / 1000L;
    private static final OutputStream DISCARD_OUTPUT = new OutputStream() {
        @Override public void write(int ignored) {}
        @Override public void write(byte[] bytes, int offset, int count) {}
    };
    public static final class Stats {
        public final long bytes;
        public final long frames;
        public final long durationMs;

        Stats(long bytes, long frames, long durationMs) {
            this.bytes = bytes;
            this.frames = frames;
            this.durationMs = durationMs;
        }
    }

    private Mp3Frames() {}

    public static Stats normalizeInPlace(File file) throws IOException {
        File temp = new File(file.getAbsolutePath() + ".normalize-" + UUID.randomUUID());
        Stats stats;
        try (FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedOutputStream out = new BufferedOutputStream(fileOut)) {
            stats = copyFrames(file, out);
            out.flush();
            fileOut.getFD().sync();
        }
        if (stats.frames <= 0 || stats.bytes <= 0) {
            temp.delete();
            throw new IOException("No complete MP3 frames were found");
        }
        publishReplacement(temp, file);
        return stats;
    }

    // rename on Android/Linux replaces the directory entry atomically. Never unlink
    // the old audio first, and retain the synced candidate if publication fails.
    static void publishReplacement(File candidate, File destination) throws IOException {
        if (!candidate.renameTo(destination)) {
            throw new IOException("Could not publish normalized audio; original and recovery copy retained");
        }
    }

    /** Examine complete MP3 frames without changing the recorded audio. */
    public static Stats inspect(File file) throws IOException {
        return copyFrames(file, DISCARD_OUTPUT);
    }

    public static Stats copyFrames(File file, OutputStream output) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            skipId3v2(in);
            long bytes = 0L;
            long frames = 0L;
            long durationTicks = 0L;
            boolean started = false;
            byte[] headerBytes = new byte[4];
            int headerCount = 0;
            while (true) {
                int next = in.read();
                if (next < 0) break;
                if (headerCount < 4) {
                    headerBytes[headerCount++] = (byte) next;
                    if (headerCount < 4) continue;
                } else {
                    headerBytes[0] = headerBytes[1];
                    headerBytes[1] = headerBytes[2];
                    headerBytes[2] = headerBytes[3];
                    headerBytes[3] = (byte) next;
                }
                Header header = parseHeader(headerBytes, 0);
                if (header == null) {
                    if (started) break;
                    continue;
                }
                byte[] frame = new byte[header.frameBytes];
                System.arraycopy(headerBytes, 0, frame, 0, 4);
                int remaining = header.frameBytes - 4;
                int offset = 4;
                while (remaining > 0) {
                    int read = in.read(frame, offset, remaining);
                    if (read < 0) return new Stats(bytes, frames, durationTicks / MPEG_TICKS_PER_MILLISECOND);
                    offset += read;
                    remaining -= read;
                }
                output.write(frame);
                bytes += header.frameBytes;
                frames++;
                long ticksPerSample = MPEG_TICKS_PER_SECOND / header.sampleRate;
                if (ticksPerSample * header.sampleRate != MPEG_TICKS_PER_SECOND) {
                    throw new IOException("Unsupported MP3 sample-rate timebase");
                }
                try {
                    durationTicks = Math.addExact(durationTicks,
                            Math.multiplyExact((long) header.samplesPerFrame,
                                    ticksPerSample));
                } catch (ArithmeticException overflow) {
                    throw new IOException("MP3 duration exceeds supported range", overflow);
                }
                started = true;
                headerCount = 0;
            }
            long durationMs = durationTicks / MPEG_TICKS_PER_MILLISECOND;
            return new Stats(bytes, frames, durationMs);
        }
    }

    private static void skipId3v2(BufferedInputStream in) throws IOException {
        in.mark(10);
        byte[] header = new byte[10];
        int count = 0;
        while (count < header.length) {
            int read = in.read(header, count, header.length - count);
            if (read < 0) break;
            count += read;
        }
        if (count < 10 || header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            in.reset();
            return;
        }
        int size = ((header[6] & 0x7f) << 21) | ((header[7] & 0x7f) << 14)
                | ((header[8] & 0x7f) << 7) | (header[9] & 0x7f);
        long remaining = (long) size + ((header[5] & 0x10) != 0 ? 10L : 0L);
        while (remaining > 0L) {
            long skipped = in.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (in.read() < 0) break;
            remaining--;
        }
    }

    private static Header parseHeader(byte[] data, int offset) {
        int value = ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
        if ((value & 0xffe00000) != 0xffe00000) return null;
        int versionBits = (value >>> 19) & 0x3;
        int layerBits = (value >>> 17) & 0x3;
        int bitrateIndex = (value >>> 12) & 0xf;
        int sampleRateIndex = (value >>> 10) & 0x3;
        int padding = (value >>> 9) & 0x1;
        if (versionBits == 1 || layerBits != 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null;

        boolean mpeg1 = versionBits == 3;
        int[] bitrates = mpeg1
                ? new int[]{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0}
                : new int[]{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0};
        int[] rates;
        if (versionBits == 3) rates = new int[]{44100,48000,32000};
        else if (versionBits == 2) rates = new int[]{22050,24000,16000};
        else rates = new int[]{11025,12000,8000};
        int bitrate = bitrates[bitrateIndex];
        int sampleRate = rates[sampleRateIndex];
        int frameBytes = ((mpeg1 ? 144000 : 72000) * bitrate / sampleRate) + padding;
        if (frameBytes < 24) return null;
        return new Header(frameBytes, mpeg1 ? 1152 : 576, sampleRate);
    }

    private static final class Header {
        final int frameBytes;
        final int samplesPerFrame;
        final int sampleRate;
        Header(int frameBytes, int samplesPerFrame, int sampleRate) {
            this.sampleRate = sampleRate;
            this.frameBytes = frameBytes;
            this.samplesPerFrame = samplesPerFrame;
        }
    }
}
