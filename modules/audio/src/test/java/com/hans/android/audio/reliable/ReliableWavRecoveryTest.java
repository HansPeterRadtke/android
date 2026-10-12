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

    @Test public void validHighRateStereoWaveIsNeverRelabeledAs16kHz()
            throws Exception {
        File file = File.createTempFile("voicebutton-high-rate-", ".wav");
        long frames = 44100L * 2L;
        try (java.io.RandomAccessFile out =
                     new java.io.RandomAccessFile(file, "rw")) {
            long pcmBytes = frames * 2L * 2L;
            ReliableSessionStore.writeWavHeader(out, pcmBytes, 44100, 2, 16);
            out.setLength(44L + pcmBytes);
        }
        try {
            byte[] originalHeader = new byte[44];
            try (FileInputStream in = new FileInputStream(file)) {
                assertEquals(44, in.read(originalHeader));
            }
            ReliableSessionStore.repairWav(file);
            byte[] after = new byte[44];
            try (FileInputStream in = new FileInputStream(file)) {
                assertEquals(44, in.read(after));
            }
            org.junit.Assert.assertArrayEquals(originalHeader, after);
            WavDurations.Info info = WavDurations.read(file);
            assertEquals(44100, info.sampleRateHz);
            assertEquals(2, info.channels);
            assertEquals(16, info.bitsPerSample);
            assertEquals(2000L, info.durationMs);
        } finally {
            file.delete();
        }
    }

    @Test public void corruptedLegacyWaveCanBeRepairedWithKnownWriterRate()
            throws Exception {
        File file = File.createTempFile("legacy-wav-", ".wav");
        try {
            try (java.io.RandomAccessFile out =
                         new java.io.RandomAccessFile(file, "rw")) {
                out.setLength(44L + 48000L);
            }
            ReliableSessionStore.repairWav(file, 24000);
            WavDurations.Info info = WavDurations.read(file);
            assertEquals(24000, info.sampleRateHz);
            assertEquals(1000L, info.durationMs);
        } finally {
            file.delete();
        }
    }

    @Test public void recoversUnsyncedTailUsingOriginalStereoSamplingRate()
            throws Exception {
        File file = File.createTempFile("wave-unsynced-", ".wav");
        int bytesPerFrame = 4; // Two channels of signed 16-bit PCM.
        try (java.io.RandomAccessFile out =
                     new java.io.RandomAccessFile(file, "rw")) {
            ReliableSessionStore.writeWavHeader(out,44100L*bytesPerFrame,
                    44100,2,16);
            out.setLength(44L+44100L*bytesPerFrame*2L);
        }
        try {
            ReliableSessionStore.repairWav(file);
            WavDurations.Info restored = WavDurations.read(file);
            assertEquals(44100,restored.sampleRateHz);
            assertEquals(2,restored.channels);
            assertEquals(352800L,restored.dataBytes);
            assertEquals(2000L,restored.durationMs);
        } finally {
            file.delete();
        }
    }

    private static int le32(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
