package com.hans.android.voicebutton;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;

final class PlayerMemorySnapshot {
    final long javaUsed;
    final long javaMax;
    final long nativeUsed;
    final long processPss;
    final long systemAvailable;
    final long systemTotal;

    private PlayerMemorySnapshot(long javaUsed, long javaMax, long nativeUsed,
                                 long processPss, long systemAvailable, long systemTotal) {
        this.javaUsed=javaUsed; this.javaMax=javaMax; this.nativeUsed=nativeUsed;
        this.processPss=processPss; this.systemAvailable=systemAvailable; this.systemTotal=systemTotal;
    }

    static PlayerMemorySnapshot capture(Context context) {
        Runtime runtime=Runtime.getRuntime();
        long javaUsed=runtime.totalMemory()-runtime.freeMemory();
        Debug.MemoryInfo process=new Debug.MemoryInfo(); Debug.getMemoryInfo(process);
        ActivityManager.MemoryInfo system=new ActivityManager.MemoryInfo();
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null)manager.getMemoryInfo(system);
        return new PlayerMemorySnapshot(javaUsed,runtime.maxMemory(),Debug.getNativeHeapAllocatedSize(),
                process.getTotalPss()*1024L,system.availMem,system.totalMem);
    }

    String describe(long sourceBytes, long studioCacheBytes,
                    long waveformBitmapBytes, String engine) {
        return "App process PSS: "+RecordingUi.formatBytes(processPss)
                +"\nJava heap: "+RecordingUi.formatBytes(javaUsed)+" / "+RecordingUi.formatBytes(javaMax)
                +"\nNative heap: "+RecordingUi.formatBytes(nativeUsed)
                +"\nSystem available: "+RecordingUi.formatBytes(systemAvailable)+" / "+RecordingUi.formatBytes(systemTotal)
                +"\nSelected source size: "+RecordingUi.formatBytes(sourceBytes)
                +"\nStudio disk cache: "+RecordingUi.formatBytes(studioCacheBytes)
                +"\nWaveform bitmap in RAM: "+RecordingUi.formatBytes(waveformBitmapBytes)
                +"\nDecoder: "+engine
                +"\nThe source is streamed from storage; it is not loaded completely into RAM.";
    }
}
