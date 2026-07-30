package com.hans.android.network.reliable;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReliableUploader {
    public interface Listener {
        void onState(String sessionId, String humanState);
        void onChanged();
        void onDiagnostic(String level, String event, String sessionId,
                          String message, org.json.JSONObject fields, Throwable failure);
    }

    private final Context context;
    private final ReliableSessionStore store;
    private final ReliableUploadClient client;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object wake = new Object();
    private final Set<String> remoteFoldersKnown = new HashSet<>();
    private Thread thread;
    private volatile String currentOperation = "idle";
    private volatile String currentSessionId = "";
    private volatile int currentSequence = -1;
    private volatile long currentDurableBytes;
    private volatile long currentTotalBytes;
    private volatile long lastProgressWallMs;
    private volatile String lastFailure = "";
    private volatile int watchdogTrips;

    public ReliableUploader(Context context, ReliableSessionStore store,
                            String baseUrl, Listener listener) {
        this.context = context.getApplicationContext(); this.store = store;
        this.client = new ReliableUploadClient(baseUrl); this.listener = listener;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::loop, "reliable-chunk-uploader");
        thread.setDaemon(true); thread.start();
    }

    public void signal() { synchronized (wake) { wake.notifyAll(); } }

    public void stop() {
        running.set(false); client.cancelActiveRequest();
        Thread value = thread; if (value != null) value.interrupt();
        signal();
    }

    public boolean awaitStopped(long timeoutMs) {
        Thread value = thread; if (value == null) return true;
        try { value.join(Math.max(0L, timeoutMs)); return !value.isAlive(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return false;
        }
    }

    public String debugSummary() {
        Thread value = thread;
        return "running=" + running.get()
                + ", worker_alive=" + (value != null && value.isAlive())
                + ", operation=" + currentOperation
                + ", session=" + currentSessionId
                + ", seq=" + currentSequence
                + ", part=" + currentDurableBytes + "/" + currentTotalBytes
                + ", last_progress_wall_ms=" + lastProgressWallMs
                + ", watchdog_trips=" + watchdogTrips
                + ", last_failure=" + lastFailure;
    }

    private void loop() {
        long backoff = 250L;
        while (running.get()) {
            boolean found = false;
            boolean urgentAudio = false;
            try {
                List<ReliableSessionManifest> sessions = store.list();
                for (ReliableSessionManifest manifest : sessions) {
                    if (!running.get()) break;
                    if (!needsWork(manifest)) continue;
                    found = true;
                    urgentAudio |= hasPendingAudio(manifest);
                    if (!hasNetwork()) {
                        listener.onState(manifest.sessionId,
                                "Waiting for network; every local chunk remains queued");
                        break;
                    }
                    reconcile(manifest);
                }
                backoff = 250L;
                waitForSignal(urgentAudio ? 250L : (found ? 1000L : 1500L));
            } catch (Exception failure) {
                if (!running.get() || Thread.currentThread().isInterrupted()
                        || failure instanceof java.io.InterruptedIOException) break;
                lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                currentOperation = "retry_backoff";
                String exact = humanFailure(failure, backoff);
                listener.onDiagnostic("ERROR", "upload.worker_failure", "", exact,
                        fields("backoff_ms", backoff,
                                "exception_class", failure.getClass().getName(),
                                "exception_message", String.valueOf(failure.getMessage())), failure);
                listener.onState("", exact);
                waitForSignal(backoff);
                backoff = Math.min(5000L, Math.max(250L, backoff * 2L));
            }
        }
        currentOperation = "stopped";
    }

    private static boolean hasPendingAudio(ReliableSessionManifest manifest) {
        for (ReliableSessionManifest.Segment segment : manifest.segments) {
            if (!segment.remoteAccepted) return true;
        }
        return false;
    }

    private static boolean needsWork(ReliableSessionManifest manifest) {
        return !manifest.remoteCommitted
                || manifest.durableRemoteChunkCount() < manifest.segments.size()
                || manifest.transcriptChunkCount() < manifest.segments.size();
    }

    private void reconcile(ReliableSessionManifest snapshot) throws Exception {
        String sessionId = snapshot.sessionId;
        currentSessionId = sessionId;
        currentOperation = "reconcile";
        lastProgressWallMs = System.currentTimeMillis();
        if (!remoteFoldersKnown.contains(snapshot.folderId)) {
            client.createFolder(snapshot.folderId, snapshot.folderName);
            remoteFoldersKnown.add(snapshot.folderId);
        }
        ReliableSessionManifest manifest = store.load(sessionId);

        for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
            if (!running.get()) return;
            if (segment.remoteAccepted) continue;
            File file = store.mp3File(sessionId, segment);
            if (!file.isFile() || file.length() != segment.mp3Bytes) {
                throw new IllegalStateException("Local chunk " + segment.seq
                        + " is missing or has the wrong byte length");
            }
            long attemptAt = System.currentTimeMillis();
            store.markSendAttempt(sessionId, segment.seq, attemptAt, "");
            listener.onState(sessionId, "Sending chunk " + (segment.seq + 1)
                    + " of " + manifest.segments.size());
            currentOperation = "upload_chunk";
            currentSequence = segment.seq;
            currentDurableBytes = Math.max(0L, segment.remotePartialBytes);
            currentTotalBytes = segment.mp3Bytes;
            lastProgressWallMs = System.currentTimeMillis();
            UploadStallWatchdog watchdog = new UploadStallWatchdog(
                    UploadStallWatchdog.DEFAULT_TIMEOUT_MS, () -> {
                        watchdogTrips++;
                        lastFailure = "Upload stalled for twelve seconds without a durable acknowledgement";
                        listener.onDiagnostic("ERROR", "upload.watchdog_timeout", sessionId,
                                lastFailure,
                                fields("seq", segment.seq,
                                        "durable_bytes", currentDurableBytes,
                                        "total_bytes", currentTotalBytes,
                                        "operation", currentOperation,
                                        "watchdog_trips", watchdogTrips), null);
                        client.cancelActiveRequest();
                    });
            try {
                final int chunkNumber = segment.seq + 1;
                final int chunkCount = manifest.segments.size();
                ReliableUploadClient.Ack ack = client.uploadSegment(manifest, segment, file,
                        (durableBytes, totalBytes, serverId, revision) -> {
                            watchdog.heartbeat();
                            currentDurableBytes = durableBytes;
                            currentTotalBytes = totalBytes;
                            lastProgressWallMs = System.currentTimeMillis();
                            lastFailure = "";
                            store.markRemotePartProgress(sessionId, segment.seq,
                                    durableBytes, serverId, revision);
                            listener.onState(sessionId, "Sending chunk " + chunkNumber
                                    + " of " + chunkCount + " · " + durableBytes
                                    + " of " + totalBytes + " bytes durable");
                            listener.onDiagnostic("DEBUG", "upload.part_durable", sessionId,
                                    "Server durably acknowledged an upload part",
                                    fields("seq", segment.seq,
                                            "durable_bytes", durableBytes,
                                            "total_bytes", totalBytes,
                                            "server_id", serverId,
                                            "manifest_revision", revision), null);
                            listener.onChanged();
                        });
                store.markRemoteAccepted(sessionId, segment.seq, ack.serverId,
                        ack.manifestRevision, ack.receivedAtMs, ack.durableAtMs);
                currentDurableBytes = segment.mp3Bytes;
                lastProgressWallMs = System.currentTimeMillis();
                lastFailure = "";
            } catch (Exception failure) {
                lastFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                store.markSendError(sessionId, segment.seq, System.currentTimeMillis(),
                        failure.getClass().getSimpleName() + ": " + failure.getMessage());
                throw failure;
            } finally {
                watchdog.close();
            }
            currentOperation = "chunk_complete";
            manifest = store.load(sessionId);
        }

        listener.onState(sessionId, "Reconciling durable chunks and transcript chunks");
        ReliableUploadClient.Status status = client.status(store.load(sessionId));
        applyStatus(store.load(sessionId), status);
        manifest = store.load(sessionId);
        boolean allRemote = true;
        for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
            ReliableUploadClient.RemoteSegment remote = status.received.get(segment.seq);
            if (remote == null || !segment.sha256.equals(remote.sha256)
                    || segment.mp3Bytes != remote.bytes) { allRemote = false; break; }
        }
        if (manifest.recordingFinished && manifest.conversionFinished
                && allRemote && !status.committed) {
            listener.onState(sessionId, "Committing the complete recording manifest");
            status = client.commit(manifest);
            applyStatus(manifest, status);
        }
        if (status.committed) store.markRemoteCommitted(sessionId);
        currentOperation = "idle";
        currentSequence = -1;
        currentDurableBytes = 0L;
        currentTotalBytes = 0L;
        lastProgressWallMs = System.currentTimeMillis();
        listener.onChanged();
    }

    private void applyStatus(ReliableSessionManifest local,
                             ReliableUploadClient.Status status) throws Exception {
        for (Map.Entry<Integer, ReliableUploadClient.RemoteSegment> entry
                : status.received.entrySet()) {
            ReliableSessionManifest.Segment segment = local.findSegment(entry.getKey());
            if (segment == null) continue;
            ReliableUploadClient.RemoteSegment remote = entry.getValue();
            if (!segment.sha256.equals(remote.sha256) || segment.mp3Bytes != remote.bytes) {
                throw new ReliableUploadClient.ProtocolException(409,
                        "Server chunk conflict at sequence " + segment.seq);
            }
            store.markRemoteAccepted(local.sessionId, segment.seq, status.serverId,
                    status.manifestRevision, remote.receivedAtMs, remote.durableAtMs);
        }
        for (ReliableUploadClient.Transcript transcript : status.transcripts.values()) {
            store.markTranscript(local.sessionId, transcript.seq, transcript.state,
                    transcript.text, transcript.engine, transcript.createdAtMs,
                    transcript.error);
        }
        if (status.committed) store.markRemoteCommitted(local.sessionId);
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void waitForSignal(long millis) {
        synchronized (wake) {
            if (!running.get()) return;
            try { wake.wait(Math.max(1L, millis)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static org.json.JSONObject fields(Object... values) {
        org.json.JSONObject result = new org.json.JSONObject();
        for (int i = 0; values != null && i + 1 < values.length; i += 2) {
            try { result.put(String.valueOf(values[i]), values[i + 1]); }
            catch (Exception ignored) {}
        }
        return result;
    }

    private static String humanFailure(Throwable failure, long retryDelayMs) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) root = root.getCause();
        String type = root == null ? "UnknownException" : root.getClass().getSimpleName();
        String message = root == null || root.getMessage() == null ? "no exception message"
                : root.getMessage();
        return "Temporary network interruption: " + type + ": " + message
                + ". Every acknowledged part remains durable on Jetson; retrying in "
                + retryDelayMs + " ms, with a maximum delay of 5000 ms.";
    }
}
