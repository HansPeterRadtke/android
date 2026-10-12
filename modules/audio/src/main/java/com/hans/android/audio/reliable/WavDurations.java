package com.hans.android.audio.reliable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Derive PCM/WAVE duration from the actual source rate, channels, sample width
 * and data-chunk length. Never infer an arbitrary sample rate from byte count.
 */
public final class WavDurations {
    public static final class Info {
        public final int sampleRateHz;
        public final int channels;
        public final int bitsPerSample;
        public final int blockAlignBytes;
        public final long dataOffsetBytes;
        public final long dataBytes;
        public final long durationMs;

        private Info(int rate, int channels, int bits, int blockAlign,
                     long offset, long bytes) throws IOException {
            this.sampleRateHz = rate;
            this.channels = channels;
            this.bitsPerSample = bits;
            this.blockAlignBytes = blockAlign;
            this.dataOffsetBytes = offset;
            this.dataBytes = bytes;
            this.durationMs = pcmDurationMs(bytes, rate, blockAlign);
        }
    }

    private WavDurations() {}

    public static long pcmDurationMs(long dataBytes, int sampleRateHz,
                                     int bytesPerSampleFrame) throws IOException {
        if (dataBytes < 0 || sampleRateHz <= 0 || sampleRateHz > 384000
                || bytesPerSampleFrame <= 0 || bytesPerSampleFrame > 256) {
            throw new IOException("Invalid PCM duration variables");
        }
        if (dataBytes % bytesPerSampleFrame != 0) {
            throw new IOException("Incomplete PCM sample frame");
        }
        long frames = dataBytes / bytesPerSampleFrame;
        // Avoid overflowing a long when a caller supplies a large byte count.
        try {
            return Math.addExact(
                    Math.multiplyExact(frames / sampleRateHz, 1000L),
                    (frames % sampleRateHz) * 1000L / sampleRateHz);
        } catch (ArithmeticException overflow) {
            throw new IOException("PCM duration exceeds supported range", overflow);
        }
    }

    public static long convertSampleCount(long frames, int sourceRateHz,
                                          int destinationRateHz) throws IOException {
        if (frames < 0 || sourceRateHz <= 0 || destinationRateHz <= 0) {
            throw new IOException("Invalid sample count or rate");
        }
        try {
            long whole = Math.multiplyExact(frames / sourceRateHz,
                    (long) destinationRateHz);
            long part = (frames % sourceRateHz) * (long) destinationRateHz
                    / sourceRateHz;
            return Math.addExact(whole, part);
        } catch (ArithmeticException overflow) {
            throw new IOException("Sample-count conversion overflow", overflow);
        }
    }

    public static Info read(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long total = input.length();
            if (total < 44 || !"RIFF".equals(fourcc(input))) {
                throw new IOException("Missing RIFF WAV header");
            }
            readUnsigned32(input); // RIFF size: not trusted over actual file size
            if (!"WAVE".equals(fourcc(input))) {
                throw new IOException("Missing WAVE format");
            }
            int sampleRate = 0, channels = 0, bits = 0, alignment = 0;
            int byteRate = 0;
            long dataOffset = -1L, dataBytes = -1L;
            long cursor = 12L;
            int chunks = 0;
            while (cursor + 8 <= total && chunks++ < 1024) {
                input.seek(cursor);
                String label = fourcc(input);
                long chunkLength = readUnsigned32(input);
                long start = cursor + 8L;
                if (chunkLength > total - start) {
                    throw new IOException("Truncated WAV " + label + " chunk");
                }
                if ("fmt ".equals(label)) {
                    if (chunkLength < 16L) throw new IOException("Short WAV fmt chunk");
                    int format = readUnsigned16(input);
                    channels = readUnsigned16(input);
                    sampleRate = (int) readUnsigned32(input);
                    byteRate = (int) readUnsigned32(input);
                    alignment = readUnsigned16(input);
                    bits = readUnsigned16(input);
                    if (format != 1 || channels < 1 || channels > 8
                            || bits <= 0 || bits % 8 != 0 || bits > 32
                            || sampleRate <= 0 || sampleRate > 384000
                            || alignment != channels * (bits / 8)
                            || (long) sampleRate * alignment != byteRate) {
                        throw new IOException("Unsupported or inconsistent WAV PCM format");
                    }
                } else if ("data".equals(label)) {
                    dataOffset = start;
                    dataBytes = chunkLength;
                }
                long next = start + chunkLength + (chunkLength & 1L);
                if (next <= cursor) throw new IOException("Invalid WAV chunk length");
                cursor = next;
            }
            if (sampleRate <= 0 || dataOffset < 0 || dataBytes < 0) {
                throw new IOException("WAV lacks a valid fmt/data chunk");
            }
            return new Info(sampleRate, channels, bits, alignment,
                            dataOffset, dataBytes);
        }
    }

    private static String fourcc(RandomAccessFile input) throws IOException {
        byte[] id = new byte[4];
        input.readFully(id);
        return new String(id, StandardCharsets.US_ASCII);
    }

    private static int readUnsigned16(RandomAccessFile in) throws IOException {
        int lo = in.readUnsignedByte();
        int hi = in.readUnsignedByte();
        return lo | (hi << 8);
    }

    private static long readUnsigned32(RandomAccessFile in) throws IOException {
        return ((long) readUnsigned16(in))
                | (((long) readUnsigned16(in)) << 16);
    }
}
