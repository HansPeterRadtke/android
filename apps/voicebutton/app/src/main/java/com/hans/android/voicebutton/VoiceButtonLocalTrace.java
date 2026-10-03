package com.hans.android.voicebutton;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class VoiceButtonLocalTrace {
    private static final Object LOCK = new Object();

    private VoiceButtonLocalTrace() {}

    private static final java.util.concurrent.atomic.AtomicLong DROPPED=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ThreadPoolExecutor WRITER=new java.util.concurrent.ThreadPoolExecutor(
            1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>((int)com.hans.android.audio.reliable.RuntimePolicy.value("log_queue_events")),
            runnable->{Thread t=new Thread(runnable,"voicebutton-local-trace");t.setDaemon(true);return t;},
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    static void log(Context context,String event,Object... keyValues) {
        if(context==null||!AppSettings.logAllowed(context,"DEBUG",event))return;
        Context app=context.getApplicationContext();StringBuilder line=new StringBuilder(256);
        line.append(isoUtc(System.currentTimeMillis())).append(" elapsed=").append(SystemClock.elapsedRealtime())
                .append(" thread=").append(Thread.currentThread().getName()).append(" event=").append(safe(event));
        if(keyValues!=null)for(int i=0;i+1<keyValues.length&&i<40;i+=2) {
            String key=String.valueOf(keyValues[i]);
            boolean secret=key.toLowerCase(Locale.ROOT).matches(".*(token|password|secret|authorization).*");
            line.append(' ').append(safe(key)).append('=').append(secret?"[redacted]":safe(String.valueOf(keyValues[i+1])));
        }
        final String captured=line.append('\n').toString();
        try {WRITER.execute(()->{
            if(!AppSettings.logAllowed(app,"DEBUG",event))return;
            synchronized(LOCK){try {
                long dropped=DROPPED.getAndSet(0L);
                String text=(dropped==0?"":"trace_queue_dropped="+dropped+"\n")+captured;
                BoundedLogFile.append(traceFile(app),text.getBytes(StandardCharsets.UTF_8),
                        com.hans.android.audio.reliable.RuntimePolicy.value("trace_bytes"),false);
            }catch(Exception failure){DROPPED.incrementAndGet();}}
        });}catch(java.util.concurrent.RejectedExecutionException full){DROPPED.incrementAndGet();}
    }

    static String tail(Context context, int maxBytes) {
        if (context == null) return "unavailable";
        synchronized (LOCK) {
            try {
                File file = traceFile(context);
                if (!file.isFile()) return "unavailable";
                long length = file.length();
                int size = (int)Math.max(0, Math.min(Math.max(1, maxBytes), length));
                byte[] buffer = new byte[size];
                try (FileInputStream in = new FileInputStream(file)) {
                    long skip = Math.max(0L, length - size);
                    while (skip > 0L) {
                        long skipped = in.skip(skip);
                        if (skipped <= 0L) break;
                        skip -= skipped;
                    }
                    int offset = 0;
                    while (offset < size) {
                        int read = in.read(buffer, offset, size - offset);
                        if (read < 0) break;
                        offset += read;
                    }
                    return new String(buffer, 0, offset, StandardCharsets.UTF_8);
                }
            } catch (Exception failure) {
                return "unreadable: " + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage();
            }
        }
    }

    private static File traceFile(Context context) {
        return new File(new File(context.getNoBackupFilesDir(), "local_trace"),
                "voicebutton-trace.log");
    }

    private static String isoUtc(long wall) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(wall));
    }

    private static String safe(String value) {
        if (value == null) return "";
        String text = value.replace('\n', '_').replace('\r', '_').replace('\t', '_');
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
