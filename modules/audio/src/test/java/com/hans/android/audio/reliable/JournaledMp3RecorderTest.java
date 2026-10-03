package com.hans.android.audio.reliable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import com.hans.android.audio.AudioInputOption;

import org.junit.Test;

public class JournaledMp3RecorderTest {
    @Test public void classicBluetoothUsesTelephoneRateBeforeWidebandFallback() {
        AudioInputOption bluetooth = new AudioInputOption(1484,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                "Bluetooth headset microphone — G06-BT",
                AudioInputOption.Category.BLUETOOTH);
        assertArrayEquals(new int[] {16000, 8000},
                JournaledMp3Recorder.candidateInputSampleRates(bluetooth));
    }

    @Test public void nonBluetoothKeepsNormalSixteenKilohertzInput() {
        AudioInputOption builtIn = new AudioInputOption(16,
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                "Built-in microphone",
                AudioInputOption.Category.BUILT_IN);
        assertArrayEquals(new int[] {48000, 44100, 32000, 16000},
                JournaledMp3Recorder.candidateInputSampleRates(builtIn));
    }
    @Test public void highQualityProfileUsesFortyEightKilohertzAndHighBitrate() {
        org.junit.Assert.assertEquals(48000, ReliableSessionManifest.OUTPUT_SAMPLE_RATE);
        org.junit.Assert.assertEquals(192, Mp3Converter.BITRATE_KBPS);
    }

    @Test public void captureKeepsEncodingOffMicrophoneThread() {
        assertFalse(JournaledMp3Recorder.encodesWhileCapturing());
        assertTrue(JournaledMp3Recorder.captureBufferBytes(48000, 4096)
                >= 48000 * 2 * 30);
        org.junit.Assert.assertEquals(1000,
                JournaledMp3Recorder.syncIntervalMs());
    }

    @Test public void stalledWriterRejectsNextCaptureBeforeBufferOverflow()throws Exception{
        Class<?> type=Class.forName(JournaledMp3Recorder.class.getName()+"$PcmJournalWriter");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        JournaledMp3Recorder.Listener listener=(JournaledMp3Recorder.Listener)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{JournaledMp3Recorder.Listener.class},(proxy,method,args)->null);
        Object writer=constructor.newInstance(null,0,48000,listener);
        java.lang.reflect.Method check=type.getDeclaredMethod("requireCapacity"),enqueue=type.getDeclaredMethod("enqueue",short[].class,int.class,long.class);check.setAccessible(true);enqueue.setAccessible(true);
        int capacity=(int)RuntimePolicy.value("pcm_queue_blocks");for(int i=0;i<capacity;i++){check.invoke(writer);enqueue.invoke(writer,new short[]{1,2},2,(long)i*2);}
        try{check.invoke(writer);org.junit.Assert.fail("Capture admitted beyond bounded queue");}catch(java.lang.reflect.InvocationTargetException expected){assertTrue(expected.getCause() instanceof java.io.IOException);}
        java.lang.reflect.Field queue=type.getDeclaredField("queue");queue.setAccessible(true);org.junit.Assert.assertEquals(capacity,((java.util.Queue<?>)queue.get(writer)).size());
    }
}
