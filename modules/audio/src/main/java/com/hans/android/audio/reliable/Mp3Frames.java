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
        if (file.exists() && !file.delete()) {
            temp.delete();
            throw new IOException("Could not replace MP3 segment");
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("Could not publish normalized MP3 segment");
        }
        return stats;
    }

    public static Stats copyFrames(File file, OutputStream output) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), 64 * 1024)) {
            skipId3v2(in);
            long bytes = 0L;
            long frames = 0L;
            long samples = 0L;
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
                    if (read < 0) return new Stats(bytes, frames, samples <= 0L ? 0L : samples * 1000L / 16000L);
                    offset += read;
                    remaining -= read;
                }
                output.write(frame);
                bytes += header.frameBytes;
                frames++;
                samples += header.samplesPerFrame;
                started = true;
                headerCount = 0;
            }
            long durationMs = samples <= 0L ? 0L : samples * 1000L / 16000L;
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
        return new Header(frameBytes, mpeg1 ? 1152 : 576);
    }

    private static final class Header {
        final int frameBytes;
        final int samplesPerFrame;
        Header(int frameBytes, int samplesPerFrame) {
            this.frameBytes = frameBytes;
            this.samplesPerFrame = samplesPerFrame;
        }
    }
}
