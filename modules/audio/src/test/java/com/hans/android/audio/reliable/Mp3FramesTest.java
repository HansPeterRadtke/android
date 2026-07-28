package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import org.junit.Test;

public class Mp3FramesTest {
    @Test public void stripsLeadingGarbageAndCopiesCompleteFrames() throws Exception {
        File file = File.createTempFile("voicebutton-mp3-", ".mp3");
        try {
            byte[] frame = new byte[144];
            frame[0] = (byte)0xff;
            frame[1] = (byte)0xf3;
            frame[2] = 0x48;
            frame[3] = (byte)0xc4;
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(new byte[]{'I','D','3',4,0,0,0,0,0,0});
                out.write(frame);
                out.write(frame);
                out.write(frame, 0, 20);
            }
            ByteArrayOutputStream normalized = new ByteArrayOutputStream();
            Mp3Frames.Stats stats = Mp3Frames.copyFrames(file, normalized);
            assertEquals(2, stats.frames);
            assertEquals(288, stats.bytes);
            assertEquals(72, stats.durationMs);
            assertEquals(288, normalized.size());
            assertTrue((normalized.toByteArray()[0] & 0xff) == 0xff);
        } finally {
            file.delete();
        }
    }
}
