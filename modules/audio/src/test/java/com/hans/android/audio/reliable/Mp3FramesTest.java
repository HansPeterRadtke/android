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
    @Test
    public void copyFramesDoesNotReadWholeLargeFileIntoHeap() throws Exception {
        File file = File.createTempFile("mp3frames-large-", ".mp3");
        file.deleteOnExit();
        try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(file, "rw")) {
            out.write(new byte[]{(byte) 0xff, (byte) 0xf3, (byte) 0x88, (byte) 0x00});
            out.setLength(180L * 1024L * 1024L);
        }
        java.io.ByteArrayOutputStream copied = new java.io.ByteArrayOutputStream();
        Mp3Frames.Stats stats = Mp3Frames.copyFrames(file, copied);
        org.junit.Assert.assertTrue(stats.frames >= 1L);
        org.junit.Assert.assertTrue(copied.size() < 4096);
    }

    @Test public void durationUsesActual48kHzAndMixedSampleRates()throws Exception{
        File file=File.createTempFile("mp3-duration-",".mp3");try{
            byte[] high=new byte[384];high[0]=(byte)0xff;high[1]=(byte)0xfb;high[2]=(byte)0x94;
            byte[] low=new byte[144];low[0]=(byte)0xff;low[1]=(byte)0xf3;low[2]=(byte)0x48;
            try(FileOutputStream out=new FileOutputStream(file)){for(int i=0;i<10;i++)out.write(high);}
            assertEquals(240,Mp3Frames.copyFrames(file,new ByteArrayOutputStream()).durationMs);
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write(low);out.write(high,0,17);}
            Mp3Frames.Stats stats=Mp3Frames.copyFrames(file,new ByteArrayOutputStream());assertEquals(11,stats.frames);assertEquals(276,stats.durationMs);
        }finally{file.delete();}
    }
    @Test public void allNineMpegRatesDeriveExactDurationsFromFrameHeaders()
            throws Exception {
        int[][] choices = {
                {3,0,44100,1152},{3,1,48000,1152},{3,2,32000,1152},
                {2,0,22050,576},{2,1,24000,576},{2,2,16000,576},
                {0,0,11025,576},{0,1,12000,576},{0,2,8000,576}};
        for (int[] selected : choices) {
            File input = File.createTempFile("mpeg-rate-", ".mp3");
            try {
                byte[] frame = syntheticMpegFrame(selected[0], selected[1], 9, 0);
                try (FileOutputStream output = new FileOutputStream(input)) {
                    for (int i = 0; i < 100; i++) output.write(frame);
                }
                Mp3Frames.Stats inspected = Mp3Frames.inspect(input);
                long expectedMs = 100L * selected[3] * 1000L / selected[2];
                assertEquals("rate " + selected[2], expectedMs, inspected.durationMs);
                assertEquals(100L, inspected.frames);
                assertEquals(frame.length * 100L, inspected.bytes);
            } finally {
                input.delete();
            }
        }
    }

    @Test public void mixedRatesPaddingAndBitrateChangeBytesButNotTimeUnits()
            throws Exception {
        File input = File.createTempFile("mpeg-variable-", ".mp3");
        try {
            byte[] f44100 = syntheticMpegFrame(3,0,9,0);
            byte[] f48000 = syntheticMpegFrame(3,1,10,1);
            byte[] f24000 = syntheticMpegFrame(2,1,9,0);
            byte[] f8000 = syntheticMpegFrame(0,2,9,1);
            try (FileOutputStream output = new FileOutputStream(input)) {
                output.write(f44100);
                output.write(f48000);
                output.write(f24000);
                output.write(f8000);
                output.write(f44100,0,9); // truncated tail never counts
            }
            byte[] before = java.nio.file.Files.readAllBytes(input.toPath());
            Mp3Frames.Stats scanned = Mp3Frames.inspect(input);
            assertEquals(4, scanned.frames);
            long expectedMs = (long)Math.floor(
                    (1152.0/44100 + 1152.0/48000
                            + 576.0/24000 + 576.0/8000)*1000);
            assertEquals(expectedMs, scanned.durationMs);
            assertEquals(f44100.length + f48000.length
                    + f24000.length + f8000.length, scanned.bytes);
            assertTrue(java.util.Arrays.equals(before,
                    java.nio.file.Files.readAllBytes(input.toPath())));
        } finally {
            input.delete();
        }
    }

    private static byte[] syntheticMpegFrame(int versionBits,
                                              int sampleRateIndex,
                                              int bitrateIndex,
                                              int padding) {
        int[] rates = versionBits == 3 ? new int[]{44100,48000,32000}
                : versionBits == 2 ? new int[]{22050,24000,16000}
                : new int[]{11025,12000,8000};
        int[] ratesKbps = versionBits == 3
                ? new int[]{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0}
                : new int[]{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0};
        int length = ((versionBits == 3 ? 144000 : 72000)
                * ratesKbps[bitrateIndex] / rates[sampleRateIndex]) + padding;
        byte[] frame = new byte[length];
        int header = 0xffe00000 | (versionBits << 19)
                | (1 << 17) | (1 << 16)
                | (bitrateIndex << 12) | (sampleRateIndex << 10)
                | (padding << 9) | (3 << 6);
        frame[0] = (byte)(header >>> 24);
        frame[1] = (byte)(header >>> 16);
        frame[2] = (byte)(header >>> 8);
        frame[3] = (byte)header;
        return frame;
    }

    @Test public void failedReplacementRetainsOriginalAndRecoveryCandidate()throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("mp3-publish-").toFile(),original=new File(dir,"original.mp3"),candidate=new File(dir,"candidate.mp3");
        try{java.nio.file.Files.write(original.toPath(),"original captured audio".getBytes(java.nio.charset.StandardCharsets.UTF_8));java.nio.file.Files.write(candidate.toPath(),"synced candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File failingCandidate=new File(candidate.getPath()){@Override public boolean renameTo(File destination){return false;}};
            try{Mp3Frames.publishReplacement(failingCandidate,original);org.junit.Assert.fail("Injected replacement failure ignored");}catch(java.io.IOException expected){}
            assertEquals("original captured audio",new String(java.nio.file.Files.readAllBytes(original.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals("synced candidate",new String(java.nio.file.Files.readAllBytes(candidate.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        }finally{original.delete();candidate.delete();dir.delete();}
    }
}
