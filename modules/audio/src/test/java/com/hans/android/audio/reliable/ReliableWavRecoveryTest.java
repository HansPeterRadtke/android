package com.hans.android.audio.reliable;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public class ReliableWavRecoveryTest {
    @Test public void repairsHeaderFromDurablePcmLength() throws Exception {
        File file = File.createTempFile("voicebutton-open-", ".wav");
        try {
            byte[] badHeader = new byte[44];
            byte[] pcm = new byte[32000];
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(badHeader);
                out.write(pcm);
                out.getFD().sync();
            }
            ReliableSessionStore.repairWav(file);
            byte[] header = new byte[44];
            try (FileInputStream in = new FileInputStream(file)) {
                assertEquals(44, in.read(header));
            }
            assertEquals("RIFF", new String(header, 0, 4, "US-ASCII"));
            assertEquals("WAVE", new String(header, 8, 4, "US-ASCII"));
            assertEquals(32000, le32(header, 40));
            assertEquals(32036, le32(header, 4));
        } finally {
            file.delete();
        }
    }

    private static int le32(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
