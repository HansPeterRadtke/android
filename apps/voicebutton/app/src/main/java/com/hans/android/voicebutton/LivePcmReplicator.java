package com.hans.android.voicebutton;

import android.os.SystemClock;

import com.hans.android.network.reliable.MobileAudioCredential;
import com.hans.android.network.reliable.ReliableUploadClient;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Secondary, strictly authenticated backup of a recorder's already-fsynced
 * raw PCM prefix. Never touches AudioRecord or PCM journal writer locks.
 * Post-capture MP3 conversion/commit remains the canonical recording path.
 */
final class LivePcmReplicator {
    private static final int PART_BYTES = 64 * 1024;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private final Object wake = new Object();
    private final LinkedHashMap<String, Target> targets = new LinkedHashMap<>();
    private final ReliableUploadClient client;
    private final android.content.Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Progress progress = new Progress(false, false, 0L, 0L,
            "Live backup inactive");
    private String folderId = "default";
    private String sessionId = "";
    private String conversationId = "";
    private Thread worker;
    private long nextRetryElapsed;
    private int failedAttempts;
    private volatile boolean pairedConfigured;
    // The background worker may remain alive to finish pending raw backup
    // after capture stops. Do not report that as an active microphone session.
    private boolean captureActive;

    static final class Progress {
        final boolean paired;
        final boolean active;
        final long acknowledgedBytes;
        final long locallyDurableBytes;
        final String state;
        Progress(boolean paired, boolean active, long acknowledged,
                 long local, String state) {
            this.paired = paired;
            this.active = active;
            this.acknowledgedBytes = Math.max(0L, acknowledged);
            this.locallyDurableBytes = Math.max(0L, local);
            this.state = state;
        }
        int permille() {
            return locallyDurableBytes <= 0L ? 0
                    : (int)Math.min(1000L,
                    acknowledgedBytes * 1000L / locallyDurableBytes);
        }
    }

    private static final class Target {
        final String sessionId;
        final String folderId;
        final String conversationId;
        final int seq;
        final int rate;
        File file;
        long syncedBytes;
        long remoteOffset = -1L;
        Target(String session, String folder, String conversation,
               int sequence, int sampleRate, File source, long synced) {
            sessionId = session; folderId = folder;
            conversationId = conversation; seq = sequence; rate = sampleRate;
            file = source; syncedBytes = synced;
        }
    }

    LivePcmReplicator(android.content.Context context, String baseUrl) {
        this.context = context.getApplicationContext();
        client = new ReliableUploadClient(baseUrl);
    }

    static boolean mayForgetPrefix(long acknowledged, long local,
                                   String fileName) {
        // A fully acknowledged .open.pcm still has an unsynced tail that may
        // become durable on journal close. Preserve it for the final callback.
        return acknowledged >= 0L && acknowledged >= local
                && fileName != null && !fileName.endsWith(".open.pcm");
    }

    void begin(String session, String folder, String conversation) {
        // SharedPreferences may involve disk I/O. Do not hold the lock also
        // acquired by the fsync-completion callback while reading settings.
        boolean paired = MobileAudioCredential.isConfigured(context);
        synchronized (wake) {
            sessionId = session == null ? "" : session;
            folderId = folder == null || folder.isEmpty() ? "default" : folder;
            conversationId = conversation == null || conversation.isEmpty()
                    ? sessionId : conversation;
            // Preserve earlier session prefixes that were fsynced locally but
            // not yet acknowledged by Jetson. Pause/resume and rapid recording
            // switches must not discard their pending live backup work.
            targets.entrySet().removeIf(entry ->
                    mayForgetPrefix(entry.getValue().remoteOffset,
                            entry.getValue().syncedBytes,
                            entry.getValue().file.getName()));
            failedAttempts = 0;
            nextRetryElapsed = 0L;
            pairedConfigured = paired;
            captureActive = true;
            if (running.compareAndSet(false, true)) {
                worker = new Thread(this::loop, "voicebutton-live-pcm-replica");
                worker.setDaemon(true);
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.start();
            }
            updateProgressLocked(paired
                    ? "Live backup waiting for durable PCM; older sessions retained"
                    : "Live backup requires recording-server pairing");
            wake.notifyAll();
        }
    }

    /** Called on the storage writer, never on the microphone capture thread. */
    void onDurablePrefix(String session, int seq, File file,
                         int sampleRate, long bytes) {
        if (file == null || bytes <= 0 || (bytes & 1L) != 0L) return;
        synchronized (wake) {
            String key = session + ":" + seq;
            if (!running.get() || (!sessionId.equals(session)
                    && !targets.containsKey(key))) return;
            Target item = targets.get(key);
            if (item == null) {
                item = new Target(session, folderId, conversationId,
                        seq, sampleRate, file, bytes);
                targets.put(key, item);
            } else {
                if (item.rate != sampleRate || bytes < item.syncedBytes) return;
                if (!item.file.equals(file)) {
                    nextRetryElapsed = 0L; // journal was renamed on stop
                }
                item.file = file; // .open.pcm is renamed on pause/finish.
                item.syncedBytes = bytes;
            }
            updateProgressLocked("Live backup synchronized with recorder journal");
            wake.notifyAll();
        }
    }

    void captureStopped(String session) {
        synchronized (wake) {
            if (sessionId.equals(session)) {
                captureActive = false;
                updateProgressLocked(pairedConfigured
                        ? "Recording stopped; background live backup can finish"
                        : "Recording stopped; live backup was unpaired");
                wake.notifyAll();
            }
        }
    }

    void credentialsChanged() {
        pairedConfigured = MobileAudioCredential.isConfigured(context);
        synchronized (wake) {
            failedAttempts = 0;
            nextRetryElapsed = 0L;
            for (Target value : targets.values()) value.remoteOffset = -1L;
            wake.notifyAll();
        }
    }

    Progress progress() { return progress; }

    boolean awaitStopped(long timeoutMs) {
        Thread value = worker;
        if (value == null) return true;
        try {
            value.join(Math.max(0L, timeoutMs));
            return !value.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void stop() {
        running.set(false);
        synchronized (wake) { captureActive = false; }
        client.cancelActiveRequest();
        synchronized (wake) { wake.notifyAll(); }
        Thread value = worker;
        if (value != null) value.interrupt();
    }

    private void updateProgressLocked(String state) {
        long local = 0L, acknowledged = 0L;
        for (Target item : targets.values()) {
            local += item.syncedBytes;
            acknowledged += Math.max(0L, Math.min(item.syncedBytes,
                    item.remoteOffset));
        }
        progress = new Progress(pairedConfigured,
                captureActive && running.get(), acknowledged, local, state);
    }

    private void loop() {
        while (running.get()) {
            try {
                if (!AppSettings.uploadPermitted(context)
                        || !AppSettings.automaticUpload(context)) {
                    synchronized (wake) {
                        updateProgressLocked("Live backup paused by recording settings");
                        wake.wait(1000L);
                    }
                    continue;
                }
                String token = MobileAudioCredential.read(context);
                pairedConfigured = !token.isEmpty();
                if (token.isEmpty()) {
                    synchronized (wake) {
                        updateProgressLocked(
                                "Live backup requires recording-server pairing");
                        // Credentials are changed through settings, which
                        // signals this lock. No reason to poll every second
                        // forever on an unpaired phone.
                        wake.wait(60_000L);
                    }
                    continue;
                }
                client.setRecordingServerToken(token);
                Target item;
                synchronized (wake) {
                    long retryWait = nextRetryElapsed - SystemClock.elapsedRealtime();
                    if (retryWait > 0L) {
                        wake.wait(Math.min(retryWait, 2000L));
                        continue;
                    }
                    item = null;
                    for (Target candidate : targets.values()) {
                        if (candidate.syncedBytes > 0L
                                && (candidate.remoteOffset < 0L
                                    || candidate.remoteOffset < candidate.syncedBytes)) {
                            item = candidate; break;
                        }
                    }
                    if (item == null) {
                        updateProgressLocked("Live backup caught up with durable PCM");
                        wake.wait(1000L);
                        continue;
                    }
                }
                replicate(item);
                failedAttempts = 0;
                nextRetryElapsed = 0L;
            } catch (InterruptedException interrupted) {
                if (!running.get()) break;
                Thread.interrupted();
            } catch (Exception failure) {
                if (!running.get()) break;
                failedAttempts = Math.min(8, failedAttempts + 1);
                long delay = Math.min(MAX_BACKOFF_MS, 1000L << failedAttempts);
                String message = "Network transfer delayed; audio safe on phone";
                if (failure instanceof ReliableUploadClient.ProtocolException) {
                    int code = ((ReliableUploadClient.ProtocolException) failure).httpCode;
                    if (code == 401 || code == 403) {
                        delay = 60L * 60L * 1000L;
                        message = "Jetson rejected recording-server access; pair the phone";
                    } else if (code == 507) {
                        delay = 120_000L;
                        message = "Jetson storage low; live backup deferred, PCM safe on phone";
                    }
                }
                nextRetryElapsed = SystemClock.elapsedRealtime() + delay;
                synchronized (wake) { updateProgressLocked(message); }
            }
        }
        synchronized (wake) { updateProgressLocked("Live backup stopped"); }
    }

    static long verifiedOffset(JSONObject r, String id, int seq, int rate, long local)
            throws java.io.IOException {
        if (r == null || !r.optBoolean("durable", false)
                || r.optString("server_id", "").isEmpty()
                || !id.equals(r.optString("session_id", ""))
                || r.optInt("seq", -1) != seq
                || (r.optInt("sample_rate", 0) != 0
                    && r.optInt("sample_rate", 0) != rate)) {
            throw new java.io.IOException("Unverified live PCM receiver identity");
        }
        long offset = r.optLong("durable_offset", -1L);
        if (offset < 0 || (offset & 1L) != 0L || offset > local) {
            throw new java.io.IOException("Invalid live PCM receiver byte offset");
        }
        return offset;
    }

    private void replicate(Target target) throws Exception {
        if (target.remoteOffset < 0L) {
            JSONObject remote = client.livePcmStatus(target.folderId,
                    target.sessionId, target.seq);
            long offset = verifiedOffset(remote, target.sessionId, target.seq, target.rate, target.syncedBytes);
            if (remote.optBoolean("committed", false)) {
                synchronized (wake) { targets.remove(target.sessionId + ":" + target.seq); }
                return;
            }
            if (offset < 0 || (offset & 1L) != 0L) {
                throw new java.io.IOException("Invalid Jetson live PCM acknowledgement");
            }
            synchronized (wake) { target.remoteOffset = offset; }
        }
        while (running.get()) {
            final File file;
            final long known;
            final long offset;
            synchronized (wake) {
                file = target.file;
                known = target.syncedBytes;
                offset = target.remoteOffset;
            }
            if (offset >= known) return;
            int size = (int)Math.min(PART_BYTES, known - offset);
            size -= size % 2;
            if (size <= 0) return;
            byte[] body = new byte[size];
            try (RandomAccessFile source = new RandomAccessFile(file, "r")) {
                if (source.length() < offset + size) {
                    throw new java.io.IOException("Durable PCM prefix became unreadable");
                }
                source.seek(offset);
                source.readFully(body);
            }
            JSONObject result = client.uploadLivePcmPart(target.folderId,
                    target.sessionId, target.conversationId, target.seq,
                    target.rate, offset, body);
            if (result.optBoolean("committed", false)) {
                synchronized (wake) { targets.remove(target.sessionId + ":" + target.seq); }
                return;
            }
            long durable = result.optLong("durable_offset", -1L);
            if (durable < 0L || (durable & 1L) != 0L
                    || durable > known || (!result.optBoolean("offset_mismatch", false)
                    && durable < offset + size)) {
                throw new java.io.IOException("Jetson live PCM durable offset is inconsistent");
            }
            synchronized (wake) {
                target.remoteOffset = durable;
                updateProgressLocked("Live backup: server acknowledged durable PCM");
            }
        }
    }
}
