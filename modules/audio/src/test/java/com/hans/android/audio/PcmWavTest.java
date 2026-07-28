package com.hans.android.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class PcmWavTest {
    @Test public void wrapsPcmWithExpectedHeader() {
        byte[] pcm = new byte[] {1, 2, 3, 4};
        byte[] wav = PcmWav.wrapPcm16Mono(pcm, pcm.length, 16000);
        assertEquals(48, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(16000, le32(wav, 24));
        assertEquals(32000, le32(wav, 28));
        assertEquals(4, le32(wav, 40));
        assertArrayEquals(pcm, new byte[] {wav[44], wav[45], wav[46], wav[47]});
    }

    private static int le32(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
