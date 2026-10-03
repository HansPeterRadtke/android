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
    @Test public void failedReplacementRetainsOriginalAndRecoveryCandidate()throws Exception{
        File dir=java.nio.file.Files.createTempDirectory("mp3-publish-").toFile(),original=new File(dir,"original.mp3"),candidate=new File(dir,"candidate.mp3");
        try{java.nio.file.Files.write(original.toPath(),"original captured audio".getBytes(java.nio.charset.StandardCharsets.UTF_8));java.nio.file.Files.write(candidate.toPath(),"synced candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File failingCandidate=new File(candidate.getPath()){@Override public boolean renameTo(File destination){return false;}};
            try{Mp3Frames.publishReplacement(failingCandidate,original);org.junit.Assert.fail("Injected replacement failure ignored");}catch(java.io.IOException expected){}
            assertEquals("original captured audio",new String(java.nio.file.Files.readAllBytes(original.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals("synced candidate",new String(java.nio.file.Files.readAllBytes(candidate.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        }finally{original.delete();candidate.delete();dir.delete();}
    }
}
