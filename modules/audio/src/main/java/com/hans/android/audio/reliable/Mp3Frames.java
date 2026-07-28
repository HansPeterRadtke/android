package com.hans.android.audio.reliable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
        byte[] data = readAll(file);
        int position = skipId3v2(data);
        long bytes = 0L;
        long frames = 0L;
        long samples = 0L;
        boolean started = false;
        while (position + 4 <= data.length) {
            Header header = parseHeader(data, position);
            if (header == null) {
                if (started) break;
                position++;
                continue;
            }
            if (position + header.frameBytes > data.length) break;
            output.write(data, position, header.frameBytes);
            bytes += header.frameBytes;
            frames++;
            samples += header.samplesPerFrame;
            position += header.frameBytes;
            started = true;
        }
        long durationMs = samples <= 0L ? 0L : samples * 1000L / 16000L;
        return new Stats(bytes, frames, durationMs);
    }

    private static byte[] readAll(File file) throws IOException {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream((int)Math.min(Integer.MAX_VALUE, Math.max(8192L, file.length())))) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    private static int skipId3v2(byte[] data) {
        if (data.length < 10 || data[0] != 'I' || data[1] != 'D' || data[2] != '3') return 0;
        int size = ((data[6] & 0x7f) << 21) | ((data[7] & 0x7f) << 14)
                | ((data[8] & 0x7f) << 7) | (data[9] & 0x7f);
        int footer = (data[5] & 0x10) != 0 ? 10 : 0;
        return Math.min(data.length, 10 + size + footer);
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
