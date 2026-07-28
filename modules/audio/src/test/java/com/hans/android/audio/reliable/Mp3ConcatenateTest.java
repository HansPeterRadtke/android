package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

import org.junit.Test;

public class Mp3ConcatenateTest {
    @Test public void assemblesOrderedFrameSegmentsWithoutReencoding() throws Exception {
        File first = File.createTempFile("voicebutton-first-", ".mp3");
        File second = File.createTempFile("voicebutton-second-", ".mp3");
        File output = File.createTempFile("voicebutton-output-", ".mp3");
        output.delete();
        try {
            writeFrames(first, 2);
            writeFrames(second, 3);
            new Mp3Converter().concatenateMp3Segments(Arrays.asList(first, second), output);
            ByteArrayOutputStream decoded = new ByteArrayOutputStream();
            Mp3Frames.Stats stats = Mp3Frames.copyFrames(output, decoded);
            assertEquals(5L, stats.frames);
            assertEquals(720L, stats.bytes);
            assertEquals(720L, output.length());
        } finally {
            first.delete();
            second.delete();
            output.delete();
        }
    }

    private static void writeFrames(File file, int count) throws Exception {
        byte[] frame = new byte[144];
        frame[0] = (byte)0xff;
        frame[1] = (byte)0xf3;
        frame[2] = 0x48;
        frame[3] = (byte)0xc4;
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < count; i++) out.write(frame);
            out.getFD().sync();
        }
    }
}
