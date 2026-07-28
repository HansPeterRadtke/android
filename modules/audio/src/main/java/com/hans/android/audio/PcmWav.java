package com.hans.android.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PcmWav {
    private PcmWav() {}

    public static byte[] wrapPcm16Mono(byte[] pcm, int length, int sampleRate) {
        if (pcm == null) throw new IllegalArgumentException("pcm is null");
        if (length < 0 || length > pcm.length) throw new IllegalArgumentException("invalid PCM length");
        if (sampleRate <= 0) throw new IllegalArgumentException("invalid sample rate");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(44 + length);
            writeAscii(out, "RIFF");
            writeLe32(out, 36 + length);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLe32(out, 16);
            writeLe16(out, 1);
            writeLe16(out, 1);
            writeLe32(out, sampleRate);
            writeLe32(out, sampleRate * 2);
            writeLe16(out, 2);
            writeLe16(out, 16);
            writeAscii(out, "data");
            writeLe32(out, length);
            out.write(pcm, 0, length);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }
}
