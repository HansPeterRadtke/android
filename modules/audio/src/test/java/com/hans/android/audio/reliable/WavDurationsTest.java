package com.hans.android.audio.reliable;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.junit.Test;

public class WavDurationsTest {
    @Test public void computesDurationsForSampleRateChannelWidthAndChunkPlacement()
            throws Exception {
        for (int rate : new int[]{8000, 16000, 22050, 44100, 48000, 96000}) {
            for (int channels : new int[]{1,2}) {
                for (int bits : new int[]{8,16,24,32}) {
                    int bytesPerFrame = channels * (bits / 8);
                    long frames = rate * 3L + rate / 2L;
                    long bytes = frames * bytesPerFrame;
                    File file = File.createTempFile("wave-duration-", ".wav");
                    try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
                        out.writeBytes("RIFF");
                        le32(out, 36 + bytes + 10);
                        out.writeBytes("WAVE");
                        out.writeBytes("LIST");le32(out,2);out.writeShort(0);
                        out.writeBytes("fmt ");le32(out,16);
                        le16(out,1);le16(out,channels);le32(out,rate);
                        le32(out,rate * bytesPerFrame);le16(out,bytesPerFrame);
                        le16(out,bits);
                        out.writeBytes("data");le32(out,bytes);
                        out.setLength(out.getFilePointer() + bytes);
                    }
                    WavDurations.Info result = WavDurations.read(file);
                    assertEquals(3500, result.durationMs);
                    assertEquals(rate, result.sampleRateHz);
                    assertEquals(channels, result.channels);
                    assertEquals(bits, result.bitsPerSample);
                    assertEquals(bytes, result.dataBytes);
                    assertTrue(result.dataOffsetBytes > 44);
                    file.delete();
                }
            }
        }
    }

    @Test public void cannotComputeWhenRateOrFrameWidthMissing() throws Exception {
        for (int rate : new int[]{0,-1,384001}) {
            try {
                WavDurations.pcmDurationMs(44100,rate,2);
                fail("Accepted impossible sample rate");
            } catch (IOException expected) {}
        }
        try {
            WavDurations.pcmDurationMs(3,44100,2);
            fail("Accepted partial frame");
        } catch (IOException expected) {}
    }

    @Test public void handlesLongDurationsWithoutMultiplicationOverflow() throws Exception {
        assertEquals(3600000000L,
                WavDurations.pcmDurationMs(2L*48000L*3600000L,48000,2));
    }

    private static void le16(RandomAccessFile out, long x) throws IOException {
        out.write((int)x);out.write((int)(x>>>8));
    }
    private static void le32(RandomAccessFile out, long x) throws IOException {
        le16(out,x);le16(out,x>>>16);
    }
}
