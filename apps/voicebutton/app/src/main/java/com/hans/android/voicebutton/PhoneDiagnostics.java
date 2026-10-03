package com.hans.android.voicebutton;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import com.hans.android.network.reliable.DiagnosticsClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PhoneDiagnostics {
    public static final String DEBUG = "DEBUG";
    public static final String INFO = "INFO";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";

    private static final long MAX_PENDING_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_NONCRITICAL_PENDING_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_BATCH_EVENTS = 100;
    private static final int MAX_BATCH_BYTES = 512 * 1024;
    private static final AtomicReference<PhoneDiagnostics> INSTANCE = new AtomicReference<>();
    private static final ScheduledThreadPoolExecutor INITIALIZER =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "voicebutton-diagnostics-init");
                thread.setDaemon(true);
                return thread;
            });
    static { INITIALIZER.setRemoveOnCancelPolicy(true); }

    private final Context context;
    private final File root;
    private final File installationFile;
    private final File pendingFile;
    private final DiagnosticsClient client;
    private final ScheduledThreadPoolExecutor executor;
    private final long processStartElapsedMs = SystemClock.elapsedRealtime();
    private final String installationId;
    private final String appVersion;
    private volatile long droppedDebugEvents;
    private volatile long droppedInfoEvents;
    private final java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong droppedQueuedEvents = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean flushQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean INITIALIZING = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long lastStorageFailureElapsedMs;

    private PhoneDiagnostics(Context context, String baseUrl, String appVersion) throws Exception {
        this.context = context.getApplicationContext();
        this.appVersion = appVersion;
        root = new File(this.context.getNoBackupFilesDir(), "phone_diagnostics");
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
            throw new IllegalStateException("Could not create diagnostic storage");
        }
        installationFile = new File(root, "installation.id");
        pendingFile = new File(root, "pending.jsonl");
        installationId = loadOrCreateInstallationId();
        client = new DiagnosticsClient(baseUrl, "VoiceButton/" + appVersion + " Android");
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "voicebutton-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleWithFixedDelay(this::flushSafely, 2L, 5L, TimeUnit.SECONDS);
    }

    public static PhoneDiagnostics initialize(Context context, String baseUrl, String appVersion) {
        PhoneDiagnostics current = INSTANCE.get();
        if (current != null) return current;
        try {
            PhoneDiagnostics created = new PhoneDiagnostics(context, baseUrl, appVersion);
            if (INSTANCE.compareAndSet(null, created)) {
                created.log(INFO, "app.process_start", null,
                        "Voice Button process started",
                        fields("process_start_elapsed_ms", created.processStartElapsedMs,
                                "manufacturer", Build.MANUFACTURER,
                                "model", Build.MODEL,
                                "product", Build.PRODUCT,
                                "android_release", Build.VERSION.RELEASE,
                                "sdk_int", Build.VERSION.SDK_INT,
                                "app_version", appVersion));
                CrashRecorder.reportPending(context, created);
                return created;
            }
            created.shutdown();
            return INSTANCE.get();
        } catch (Exception failure) {
            return null;
        }
    }

    public static void initializeAsync(Context context, String baseUrl,
                                       String appVersion) {
        if (INSTANCE.get() != null || !INITIALIZING.compareAndSet(false,true)) return;
        try { INITIALIZER.execute(() -> {try{initialize(context,baseUrl,appVersion);}finally{INITIALIZING.set(false);}}); }
        catch (RuntimeException ignored) {INITIALIZING.set(false);}
    }

    public static PhoneDiagnostics get() { return INSTANCE.get(); }

    public String getInstallationId() { return installationId; }

    public void log(String level, String event, String sessionId, String message, JSONObject fields) {
        if (!AppSettings.logAllowed(context,level,event)) return;
        JSONObject value = event(level, event, sessionId, message, fields, null);
        enqueueEvent(value);
    }

    public void error(String event, String sessionId, String operation, Throwable failure, JSONObject fields) {
        JSONObject value = event(ERROR, event, sessionId,
                exactFailure(operation, failure), fields, failure);
        enqueueEvent(value);
    }

    private void enqueueEvent(JSONObject value) {
        if(value.toString().length()>32768)try {
            value.put("fields",fields("omitted","Event exceeded entry budget"));
            value.put("stack_trace","See bounded crash report");
            value.put("message",bounded(value.optString("message",""),4096));
            value.put("exception_message",bounded(value.optString("exception_message",""),4096));
        } catch(Exception ignored){}
        int limit=(int)com.hans.android.audio.reliable.RuntimePolicy.value("log_queue_events"), n;
        do{n=admitted.get();if(n>=limit){droppedQueuedEvents.incrementAndGet();return;}}while(!admitted.compareAndSet(n,n+1));
        try {executor.execute(()->{try {
            if(AppSettings.logAllowed(context,value.optString("level",INFO),value.optString("event","")))appendSafely(value);
        }finally{admitted.decrementAndGet();}});}
        catch(RuntimeException failure){admitted.decrementAndGet();droppedQueuedEvents.incrementAndGet();}
    }

    public void flushSoon() {
        if(!flushQueued.compareAndSet(false,true))return;
        try {executor.execute(()->{try{flushSafely();}finally{flushQueued.set(false);}});}
        catch(RuntimeException failure){flushQueued.set(false);}
    }

    public void shutdown() {
        flushSoon();
        executor.shutdown();
    }

    public void cancelActiveTransmission() {
        client.cancelActiveRequest();
    }

    public void shutdownForAppExit() {
        client.cancelActiveRequest();
        flushSoon();
        executor.shutdownNow();
        try { executor.awaitTermination(500L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        INSTANCE.compareAndSet(this, null);
    }

    public static JSONObject fields(Object... keyValues) {
        JSONObject fields = new JSONObject();
        if (keyValues == null) return fields;
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            try { fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]); }
            catch (Exception ignored) {}
        }
        return fields;
    }

    public static String exactFailure(String operation, Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String stage = operation == null || operation.trim().isEmpty() ? "Operation" : operation.trim();
        if (root == null) return stage + " failed for an unknown reason";
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        if (message == null || message.trim().isEmpty()) message = "no exception message";
        return stage + " failed: " + type + ": " + message.trim();
    }

    private JSONObject event(String level, String event, String sessionId,
                             String message, JSONObject fields, Throwable failure) {
        JSONObject value = new JSONObject();
        long wall = System.currentTimeMillis();
        try {
            value.put("event_id", UUID.randomUUID().toString());
            value.put("event", bounded(event,256));
            value.put("level", level);
            value.put("wall_time_ms", wall);
            value.put("wall_time_iso_utc", isoUtc(wall));
            value.put("elapsed_realtime_ms", SystemClock.elapsedRealtime());
            value.put("process_uptime_ms", Math.max(0L,
                    SystemClock.elapsedRealtime() - processStartElapsedMs));
            value.put("process_id", Process.myPid());
            value.put("thread", Thread.currentThread().getName());
            value.put("app_version", appVersion);
            value.put("manufacturer", Build.MANUFACTURER);
            value.put("model", Build.MODEL);
            value.put("sdk_int", Build.VERSION.SDK_INT);
            if (sessionId != null && !sessionId.isEmpty()) value.put("session_id", bounded(sessionId,256));
            value.put("message", bounded(message,4096));
            value.put("fields", safeFields(fields));
            if (failure != null) {
                value.put("exception_class", failure.getClass().getName());
                value.put("exception_message", bounded(failure.getMessage(),4096));
                value.put("stack_trace", stackTrace(failure));
            }
        } catch (Exception ignored) {}
        return value;
    }

    private static String bounded(String value,int limit) {
        if(value==null)return "";
        return value.length()<=limit?value:value.substring(0,limit)+"…";
    }
    private static JSONObject safeFields(JSONObject original) {
        JSONObject safe=new JSONObject();if(original==null)return safe;
        try {
            java.util.Iterator<String> keys=original.keys();int count=0;
            while(keys.hasNext()&&count++<40) {
                String key=keys.next(),lower=key.toLowerCase(java.util.Locale.ROOT);Object value=original.opt(key);
                safe.put(bounded(key,128),lower.matches(".*(token|password|secret|authorization|credential).*")?"[redacted]":
                    (value instanceof Number||value instanceof Boolean)?value:bounded(String.valueOf(value),500));
            }
        }catch(Exception ignored){}
        return safe;
    }

    private void appendSafely(JSONObject value) {
        try {
            long queueDrops=droppedQueuedEvents.getAndSet(0L);
            if(queueDrops>0L)appendBytes((event(WARN,"diagnostics.queue_overflow",null,
                    "Diagnostic tasks dropped to protect recording memory",fields("dropped_events",queueDrops),null).toString()+"\n").getBytes(StandardCharsets.UTF_8),true);
            byte[] bytes = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            String level = value.optString("level", INFO);
            long projected = pendingFile.length() + bytes.length;
            if (projected > MAX_PENDING_BYTES && DEBUG.equals(level)) {
                droppedDebugEvents++;
                writeQueuePressureState();
                return;
            }
            if (projected > MAX_NONCRITICAL_PENDING_BYTES
                    && (DEBUG.equals(level) || INFO.equals(level))) {
                if (DEBUG.equals(level)) droppedDebugEvents++;
                else droppedInfoEvents++;
                writeQueuePressureState();
                return;
            }
            if (droppedDebugEvents > 0L || droppedInfoEvents > 0L) {
                JSONObject dropped = event(WARN, "diagnostics.events_dropped", null,
                        "Diagnostic queue reached its safety limit; lower-priority events were dropped to protect audio storage",
                        fields("dropped_debug_events", droppedDebugEvents,
                                "dropped_info_events", droppedInfoEvents,
                                "pending_bytes", pendingFile.length()), null);
                appendBytes((dropped.toString() + "\n").getBytes(StandardCharsets.UTF_8), true);
                droppedDebugEvents = 0L;
                droppedInfoEvents = 0L;
                writeQueuePressureState();
            }
            appendBytes(bytes, WARN.equals(level) || ERROR.equals(level));
        } catch (Exception failure) {
            long now=SystemClock.elapsedRealtime();
            if(now-lastStorageFailureElapsedMs>60000L){lastStorageFailureElapsedMs=now;
                android.util.Log.e("VoiceButton","Diagnostic storage failed; recording remains independent",failure);}
        }
    }

    private void appendBytes(byte[] bytes,boolean durable) throws Exception {
        BoundedLogFile.append(pendingFile,bytes,com.hans.android.audio.reliable.RuntimePolicy.value("log_pending_bytes"),durable);
    }

    private void syncPendingFile() throws Exception {
        if (!pendingFile.isFile() || pendingFile.length() <= 0L) return;
        try (FileOutputStream out = new FileOutputStream(pendingFile, true)) {
            out.flush();
            out.getFD().sync();
        }
    }

    private void flushSafely() {
        try { flush(); }
        catch (Exception ignored) {
            // The pending file remains untouched and will retry later.
        }
    }

    private void flush() throws Exception {
        if (!pendingFile.isFile() || pendingFile.length() <= 0L) return;
        syncPendingFile();
        Batch batch = readBatch();
        if (batch.events.length() == 0) {
            if (batch.consumedLines > 0) removeLeadingLines(batch.consumedLines);
            return;
        }
        long started = SystemClock.elapsedRealtime();
        DiagnosticsClient.Result result = client.send(installationId, batch.events);
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - started);
        if (result.acceptedCount != batch.events.length()
                || !result.acceptedEventIds.containsAll(batch.expectedEventIds)) {
            throw new IllegalStateException("Server diagnostic acknowledgement did not match the sent batch");
        }
        removeLeadingLines(batch.consumedLines);
        writeDeliveryState(result.acceptedCount, elapsed, result.serverReceivedWallMs);
    }

    private void writeDeliveryState(int acceptedCount, long requestDurationMs,
                                    long serverReceivedWallMs) throws Exception {
        JSONObject state = new JSONObject();
        state.put("last_accepted_count", acceptedCount);
        state.put("last_request_duration_ms", requestDurationMs);
        state.put("last_server_received_wall_ms", serverReceivedWallMs);
        state.put("remaining_pending_bytes", pendingFile.length());
        state.put("updated_wall_time_ms", System.currentTimeMillis());
        File target = new File(root, "delivery-state.json");
        File temp = new File(root, "delivery-state.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IllegalStateException("Could not replace diagnostic delivery state");
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IllegalStateException("Could not publish diagnostic delivery state");
        }
    }

    private Batch readBatch() throws Exception {
        Batch batch = new Batch();
        int bytes = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(pendingFile))) {
            String line;
            while ((line = reader.readLine()) != null
                    && batch.events.length() < MAX_BATCH_EVENTS) {
                int next = line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (batch.events.length() > 0 && bytes + next > MAX_BATCH_BYTES) break;
                batch.consumedLines++;
                if (line.trim().isEmpty()) continue;
                try {
                    JSONObject value = new JSONObject(line);
                    String eventId = value.getString("event_id");
                    if (!eventId.matches("[A-Za-z0-9_-]{1,128}")) {
                        throw new IllegalArgumentException("invalid event_id");
                    }
                    batch.events.put(value);
                    batch.expectedEventIds.add(eventId);
                    bytes += next;
                } catch (Exception malformed) {
                    quarantineMalformedLine(line, malformed, batch.consumedLines);
                }
            }
        }
        return batch;
    }

    private void quarantineMalformedLine(String line, Throwable failure,
                                         int lineNumberInBatch) throws Exception {
        File corrupt = new File(root, "corrupt.jsonl");
        JSONObject record = new JSONObject();
        long now = System.currentTimeMillis();
        record.put("wall_time_ms", now);
        record.put("wall_time_iso_utc", isoUtc(now));
        record.put("line_number_in_batch", lineNumberInBatch);
        record.put("exception_class", failure.getClass().getName());
        record.put("exception_message", failure.getMessage() == null ? "" : failure.getMessage());
        String preserved = line == null ? "" : line;
        if (preserved.length() > 65536) preserved = preserved.substring(0, 65536);
        record.put("preserved_line", preserved);
        BoundedLogFile.append(corrupt,(record.toString()+"\n").getBytes(StandardCharsets.UTF_8),
                com.hans.android.audio.reliable.RuntimePolicy.value("log_corrupt_bytes"),true);
    }

    private void writeQueuePressureState() throws Exception {
        JSONObject state = new JSONObject();
        state.put("pending_bytes", pendingFile.length());
        state.put("dropped_debug_events", droppedDebugEvents);
        state.put("dropped_info_events", droppedInfoEvents);
        state.put("updated_wall_time_ms", System.currentTimeMillis());
        File target = new File(root, "queue-pressure.json");
        File temp = new File(root, "queue-pressure.json.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(state.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            temp.delete();
            throw new IllegalStateException("Could not replace diagnostic queue pressure state");
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IllegalStateException("Could not publish diagnostic queue pressure state");
        }
    }

    private void removeLeadingLines(int count) throws Exception {
        File temp = new File(root, "pending.jsonl.tmp");
        try (BufferedReader reader = new BufferedReader(new FileReader(pendingFile));
             FileOutputStream fileOut = new FileOutputStream(temp);
             BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(
                     fileOut, StandardCharsets.UTF_8))) {
            String line;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                if (skipped < count) {
                    skipped++;
                    continue;
                }
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
            fileOut.getFD().sync();
        }
        if (pendingFile.exists() && !pendingFile.delete()) {
            temp.delete();
            throw new IllegalStateException("Could not replace acknowledged diagnostic queue");
        }
        if (!temp.renameTo(pendingFile)) {
            temp.delete();
            throw new IllegalStateException("Could not publish remaining diagnostic queue");
        }
    }

    private static final class Batch {
        final JSONArray events = new JSONArray();
        final Set<String> expectedEventIds = new HashSet<>();
        int consumedLines;
    }

    private String loadOrCreateInstallationId() throws Exception {
        if (installationFile.isFile()) {
            String existing = readText(installationFile).trim();
            if (existing.matches("[A-Za-z0-9_-]{1,128}")) return existing;
        }
        String created = "android-" + UUID.randomUUID();
        try (FileOutputStream out = new FileOutputStream(installationFile)) {
            out.write(created.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            out.getFD().sync();
        }
        return created;
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String isoUtc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String stackTrace(Throwable failure) {
        StringWriter buffer = new StringWriter();
        failure.printStackTrace(new PrintWriter(buffer));
        String value = buffer.toString();
        return value.length() <= 32768 ? value : value.substring(0, 32768);
    }
}
