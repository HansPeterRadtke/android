package com.hans.android.network.reliable;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.audio.reliable.ReliableSessionStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public final class ReliableUploader {
    public interface Listener {
        void onState(String sessionId, String humanState);
        void onChanged();
        void onDiagnostic(String level, String event, String sessionId,
                          String message, org.json.JSONObject fields, Throwable failure);
    }

    private static final long PERMANENT_RECHECK_MS = 15L * 60L * 1000L;
    private static final ReentrantLock PROCESS_UPLOAD_LEASE = new ReentrantLock(true);
    private static final AtomicReference<ReliableUploader> BACKGROUND_OWNER =
            new AtomicReference<>();

    private final Context context;
    private final ReliableSessionStore store;
    private final ReliableUploadClient client;
    private final Listener listener;
    private final boolean backgroundWorker;
    private final boolean completedOnly;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object wake = new Object();
    private final Set<String> remoteFoldersKnown = new HashSet<>();
    private final Set<String> quarantinedSessionIds =
            Collections.synchronizedSet(new HashSet<>());
    private Thread thread;
    private volatile String currentOperation = "idle";
    private volatile String currentSessionId = "";
    private volatile int currentSequence = -1;
    private volatile long currentDurableBytes;
    private volatile long currentTotalBytes;
    private volatile long lastProgressWallMs;
    private volatile String lastFailure = "";
    private volatile boolean lastFailureRetryable = true;
    private volatile int watchdogTrips;
    private volatile int retryAttempt;

    public ReliableUploader(Context context, ReliableSessionStore store,
                            String baseUrl, Listener listener) {
        this(context, store, baseUrl, listener, false, false);
    }

    public ReliableUploader(Context context, ReliableSessionStore store,
                            String baseUrl, Listener listener,
                            boolean backgroundWorker, boolean completedOnly) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.client = new ReliableUploadClient(baseUrl);
        this.listener = listener;
        this.backgroundWorker = backgroundWorker;
        this.completedOnly = completedOnly;
    }

    public synchronized void start() { ensureRunning(); }

    public synchronized boolean ensureRunning() {
        Thread value = thread;
        if (value != null && value.isAlive()) return false;
        if (!backgroundWorker) {
            ReliableUploader background = BACKGROUND_OWNER.get();
            if (background != null && background != this) background.stop();
        }
        running.set(true);
        Thread replacement = new Thread(this::loop, "reliable-chunk-uploader");
        replacement.setDaemon(true);
        thread = replacement;
        replacement.start();
        return true;
    }

    public synchronized boolean isWorkerAlive() {
        Thread value = thread;
        return running.get() && value != null && value.isAlive();
    }

    public boolean isPermanentlyPaused() {
        return "paused_permanent".equals(currentOperation) && !lastFailureRetryable;
    }

    public boolean hasActionableTransferWork() {
        try {
            if (!store.foldersNeedingSync().isEmpty()) return true;
            for (ReliableSessionManifest manifest : store.list()) {
                if (completedOnly && !manifest.recordingFinished) continue;
                if (needsTransferWork(manifest)
                        && !quarantinedSessionIds.contains(manifest.sessionId)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    public boolean hasPendingTransferWork() {
        try {
            if (!store.foldersNeedingSync().isEmpty()) return true;
            for (ReliableSessionManifest manifest : store.list()) {
                if (completedOnly && !manifest.recordingFinished) continue;
                if (needsTransferWork(manifest)) return true;
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    public void signal() { synchronized (wake) { wake.notifyAll(); } }

    public void clearQuarantines() {
        quarantinedSessionIds.clear();
        if (!lastFailureRetryable) {
            lastFailureRetryable = true;
            lastFailure = "";
        }
        signal();
    }

    public void onNetworkChanged() {
        client.cancelActiveRequest();
        ensureRunning();
        signal();
    }

    public void stop() {
        running.set(false);
        client.cancelActiveRequest();
        Thread value = thread;
        if (value != null) value.interrupt();
        signal();
    }

    public boolean awaitStopped(long timeoutMs) {
        Thread value = thread;
        if (value == null) return true;
        try {
            value.join(Math.max(0L, timeoutMs));
            return !value.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
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
                + ", adaptive_part_bytes=" + client.currentPartBytes()
                + ", retry_attempt=" + retryAttempt
                + ", quarantined_sessions=" + quarantinedSessionIds.size()
                + ", last_progress_wall_ms=" + lastProgressWallMs
                + ", watchdog_trips=" + watchdogTrips
                + ", last_failure_retryable=" + lastFailureRetryable
                + ", last_failure=" + visibleLastFailure();
    }

    private String visibleLastFailure() {
        if (!lastFailure.isEmpty()) return lastFailure;
        if (!lastFailureRetryable && !quarantinedSessionIds.isEmpty()) {
            return "older recordings are quarantined until retry";
        }
        return "";
    }

    private void loop() {
        boolean leaseHeld = false;
        try {
            if (backgroundWorker
                    && !BACKGROUND_OWNER.compareAndSet(null, this)) {
                currentOperation = "delegated_to_existing_worker";
                return;
            }
            PROCESS_UPLOAD_LEASE.lockInterruptibly();
            leaseHeld = true;
            while (running.get()) {
                boolean found = false;
                boolean actionable = false;
                boolean quarantinedFound = false;
                boolean urgentAudio = false;
                boolean networkUnavailable = false;
                try {
                    List<ReliableSessionStore.Folder> pendingFolders =
                            store.foldersNeedingSync();
                    if (!pendingFolders.isEmpty()) {
                        found = true;
                        actionable = true;
                        if (!hasNetwork()) {
                            networkUnavailable = true;
                            listener.onState("",
                                    "Waiting for network; folder changes remain queued");
                        } else {
                            for (ReliableSessionStore.Folder folder : pendingFolders) {
                                if (!running.get()) break;
                                listener.onState("", "Synchronizing folder " + folder.name);
                                client.createFolder(folder.id, folder.name, folder.parentId);
                                store.markFolderRemote(folder.id, folder.name, folder.parentId);
                                remoteFoldersKnown.add(folder.id + "\u0000"
                                        + folder.name + "\u0000" + folder.parentId);
                                listener.onChanged();
                            }
                        }
                    }
                    List<ReliableSessionManifest> sessions = store.list();
                    orderForUpload(sessions);
                    for (ReliableSessionManifest manifest : sessions) {
                        if (!running.get()) break;
                        if (completedOnly && !manifest.recordingFinished) continue;
                        if (!needsWork(manifest)) continue;
                        found = true;
                        if (quarantinedSessionIds.contains(manifest.sessionId)) {
                            quarantinedFound = true;
                            continue;
                        }
                        actionable = true;
                        urgentAudio |= hasPendingAudio(manifest);
                        if (!hasNetwork()) {
                            networkUnavailable = true;
                            listener.onState(manifest.sessionId,
                                    "Waiting for network; every local chunk remains queued");
                            break;
                        }
                        try {
                            reconcile(manifest);
                        } catch (Exception failure) {
                            if (!running.get() || isRetryableFailure(failure)) {
                                throw failure;
                            }
                            quarantineSession(manifest, failure);
                            quarantinedFound = true;
                        }
                    }
                    retryAttempt = 0;
                    if (actionable || !quarantinedFound) lastFailureRetryable = true;
                    if (!found) currentOperation = "idle";
                    else if (!actionable && quarantinedFound) {
                        currentOperation = "waiting_quarantined_recordings";
                    }
                    waitForSignal(networkUnavailable ? 60_000L
                            : urgentAudio ? 250L
                            : (!actionable && quarantinedFound)
                            ? PERMANENT_RECHECK_MS
                            : (found ? 1000L : 1500L));
                } catch (Exception failure) {
                    if (shouldStopAfterFailure(running.get(), failure)) break;
                    if (Thread.currentThread().isInterrupted()) Thread.interrupted();
                    boolean retryable = isRetryableFailure(failure);
                    lastFailureRetryable = retryable;
                    lastFailure = failure.getClass().getSimpleName() + ": "
                            + String.valueOf(failure.getMessage());
                    long delay;
                    if (retryable) {
                        retryAttempt++;
                        delay = RetryBackoff.fullJitterDelayMs(retryAttempt,
                                retryAfterMs(failure),
                                ThreadLocalRandom.current().nextDouble());
                        currentOperation = "retry_backoff";
                    } else {
                        retryAttempt = 0;
                        delay = PERMANENT_RECHECK_MS;
                        currentOperation = "paused_permanent";
                    }
                    String exact = humanFailure(failure, retryable, delay);
                    listener.onDiagnostic(retryable ? "WARN" : "ERROR",
                            retryable ? "upload.retryable_failure"
                                    : "upload.permanent_failure",
                            currentSessionId, exact,
                            fields("retryable", retryable,
                                    "retry_delay_ms", delay,
                                    "retry_attempt", retryAttempt,
                                    "adaptive_part_bytes", client.currentPartBytes(),
                                    "exception_class", failure.getClass().getName(),
                                    "exception_message", String.valueOf(failure.getMessage())),
                            failure);
                    listener.onState(currentSessionId, exact);
                    waitForSignal(delay);
                }
            }
        } catch (InterruptedException interrupted) {
            if (running.get()) {
                lastFailure = "InterruptedException: uploader ownership wait interrupted";
            }
            Thread.currentThread().interrupt();
        } finally {
            if (leaseHeld) PROCESS_UPLOAD_LEASE.unlock();
            if (backgroundWorker) BACKGROUND_OWNER.compareAndSet(this, null);
            currentOperation = "stopped";
            synchronized (this) {
                if (thread == Thread.currentThread()) running.set(false);
            }
        }
    }

    private void quarantineSession(ReliableSessionManifest manifest,
                                   Exception failure) {
        String sessionId = manifest.sessionId;
        quarantinedSessionIds.add(sessionId);
        lastFailureRetryable = false;
        lastFailure = failure.getClass().getSimpleName() + ": "
                + String.valueOf(failure.getMessage());
        currentOperation = "session_quarantined";
        String exact = "One recording was skipped after a non-retryable "
                + "synchronization failure; every other recording continues. "
                + lastFailure;
        listener.onDiagnostic("ERROR", "upload.session_quarantined",
                sessionId, exact,
                fields("retryable", false,
                        "quarantined_session_count",
                        quarantinedSessionIds.size(),
                        "exception_class", failure.getClass().getName(),
                        "exception_message",
                        String.valueOf(failure.getMessage())),
                failure);
        listener.onState(sessionId, exact);
        listener.onChanged();
    }

    static boolean shouldQuarantineSessionFailure(Throwable failure) {
        return !isRetryableFailure(failure);
    }

    static boolean shouldStopAfterFailure(boolean stillRunning, Throwable failure) {
        return !stillRunning;
    }

    static boolean isRetryableFailure(Throwable failure) {
        Throwable root = rootCause(failure);
        if (root instanceof ReliableUploadClient.ProtocolException) {
            int code = ((ReliableUploadClient.ProtocolException)root).httpCode;
            return code == 408 || code == 409 || code == 425 || code == 429
                    || code >= 500;
        }
        if (root instanceof IOException) return true;
        return !(root instanceof IllegalArgumentException)
                && !(root instanceof IllegalStateException)
                && !(root instanceof SecurityException);
    }

    static long retryAfterMs(Throwable failure) {
        Throwable value = failure;
        while (value != null) {
            if (value instanceof ReliableUploadClient.ProtocolException) {
                return ((ReliableUploadClient.ProtocolException)value).retryAfterMs;
            }
            if (value.getCause() == value) break;
            value = value.getCause();
        }
        return 0L;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == null ? failure : root;
    }

    private void orderForUpload(List<ReliableSessionManifest> sessions) {
        Collections.sort(sessions, new Comparator<ReliableSessionManifest>() {
            @Override public int compare(ReliableSessionManifest left,
                                         ReliableSessionManifest right) {
                int leftQuarantined = quarantinedSessionIds.contains(left.sessionId) ? 1 : 0;
                int rightQuarantined = quarantinedSessionIds.contains(right.sessionId) ? 1 : 0;
                if (leftQuarantined != rightQuarantined) return leftQuarantined - rightQuarantined;
                int leftPriority = uploadPriority(left);
                int rightPriority = uploadPriority(right);
                if (leftPriority != rightPriority) return leftPriority - rightPriority;
                long leftUpdated = Math.max(left.updatedAt, left.createdAt);
                long rightUpdated = Math.max(right.updatedAt, right.createdAt);
                int updated = Long.compare(rightUpdated, leftUpdated);
                if (updated != 0) return updated;
                return left.sessionId.compareTo(right.sessionId);
            }
        });
    }

    private static int uploadPriority(ReliableSessionManifest manifest) {
        if (hasPendingAudio(manifest)) return 0;
        if (needsTransferWork(manifest)) return 1;
        if (needsWork(manifest)) return 2;
        return 3;
    }

    private static boolean hasPendingAudio(ReliableSessionManifest manifest) {
        for (ReliableSessionManifest.Segment segment : manifest.segments) {
            if (!segment.remoteAccepted) return true;
        }
        return false;
    }

    private static boolean needsTransferWork(ReliableSessionManifest manifest) {
        if (manifest.hasPendingMetadata() || hasPendingAudio(manifest)) return true;
        return manifest.recordingFinished && !manifest.remoteCommitted;
    }

    private static boolean needsWork(ReliableSessionManifest manifest) {
        if (needsTransferWork(manifest)) return true;
        return manifest.transcriptChunkCount() < manifest.segments.size();
    }

    private void reconcile(ReliableSessionManifest snapshot) throws Exception {
        String sessionId = snapshot.sessionId;
        currentSessionId = sessionId;
        currentOperation = "reconcile";
        lastProgressWallMs = System.currentTimeMillis();
        ReliableSessionStore.Folder localFolder = store.getFolder(snapshot.folderId);
        String folderKey = snapshot.folderId + "\u0000" + snapshot.folderName
                + "\u0000" + localFolder.parentId;
        if (!remoteFoldersKnown.contains(folderKey)) {
            client.createFolder(snapshot.folderId, snapshot.folderName,
                    localFolder.parentId);
            store.markFolderRemote(snapshot.folderId, snapshot.folderName,
                    localFolder.parentId);
            remoteFoldersKnown.add(folderKey);
        }
        ReliableSessionManifest manifest = store.load(sessionId);
        boolean serverHasSession = manifest.remoteCommitted
                || manifest.remoteManifestRevision > 0L;
        if (!serverHasSession) {
            for (ReliableSessionManifest.Segment value : manifest.segments) {
                if (value.remoteAccepted || value.remotePartialBytes > 0L) {
                    serverHasSession = true;
                    break;
                }
            }
        }
        String remoteFolder = manifest.remoteFolderId == null
                || manifest.remoteFolderId.isEmpty()
                ? manifest.folderId : manifest.remoteFolderId;
        if (!remoteFolder.equals(manifest.folderId)) {
            if (serverHasSession) {
                listener.onState(sessionId,
                        "Moving recording to " + manifest.folderName + " on the server");
                client.moveSession(remoteFolder, manifest.folderId, manifest.sessionId);
            }
            store.markRemoteFolder(sessionId, manifest.folderId, manifest.folderName);
            manifest = store.load(sessionId);
        } else if (!manifest.folderName.equals(manifest.remoteFolderName)) {
            store.markRemoteFolder(sessionId, manifest.folderId, manifest.folderName);
            manifest = store.load(sessionId);
        }
        client.updateMetadata(manifest);
        store.markRemoteDisplayName(sessionId, manifest.displayName);
        manifest = store.load(sessionId);

        listener.onState(sessionId, "Checking Jetson's durable recording progress");
        ReliableUploadClient.Status status = client.status(manifest);
        applyStatus(manifest, status);
        manifest = store.load(sessionId);
        listener.onState(sessionId, "Jetson has " + manifest.durableRemoteChunkCount()
                + " of " + manifest.segments.size() + " chunks · provisional transcripts "
                + status.provisionalTranscriptComplete + " of "
                + status.provisionalTranscriptTotal + " · final transcript "
                + status.finalTranscriptState);

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
                        lastFailure = "Upload made no durable progress for two minutes";
                        listener.onDiagnostic("WARN", "upload.no_progress_timeout",
                                sessionId, lastFailure,
                                fields("seq", segment.seq,
                                        "durable_bytes", currentDurableBytes,
                                        "total_bytes", currentTotalBytes,
                                        "operation", currentOperation,
                                        "adaptive_part_bytes", client.currentPartBytes(),
                                        "watchdog_trips", watchdogTrips), null);
                        client.cancelActiveRequest();
                    });
            try {
                final int chunkNumber = segment.seq + 1;
                final int chunkCount = manifest.segments.size();
                ReliableUploadClient.Ack ack = client.uploadSegment(
                        manifest, segment, file,
                        (durableBytes, totalBytes, serverId, revision) -> {
                            watchdog.heartbeat();
                            currentDurableBytes = durableBytes;
                            currentTotalBytes = totalBytes;
                            lastProgressWallMs = System.currentTimeMillis();
                            lastFailure = "";
                            lastFailureRetryable = true;
                            retryAttempt = 0;
                            store.markRemotePartProgress(sessionId, segment.seq,
                                    durableBytes, serverId, revision);
                            listener.onState(sessionId, "Sending chunk " + chunkNumber
                                    + " of " + chunkCount + " · " + durableBytes
                                    + " of " + totalBytes + " bytes durable");
                        });
                store.markRemoteAccepted(sessionId, segment.seq, ack.serverId,
                        ack.manifestRevision, ack.receivedAtMs, ack.durableAtMs);
                currentDurableBytes = segment.mp3Bytes;
                lastProgressWallMs = System.currentTimeMillis();
                lastFailure = "";
            } catch (Exception failure) {
                lastFailure = failure.getClass().getSimpleName() + ": "
                        + String.valueOf(failure.getMessage());
                store.markSendError(sessionId, segment.seq,
                        System.currentTimeMillis(), lastFailure);
                throw failure;
            } finally {
                watchdog.close();
            }
            currentOperation = "chunk_complete";
            manifest = store.load(sessionId);
        }

        listener.onState(sessionId,
                "Reconciling durable chunks and transcript chunks");
        status = client.status(store.load(sessionId));
        applyStatus(store.load(sessionId), status);
        manifest = store.load(sessionId);
        boolean allRemote = true;
        for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
            ReliableUploadClient.RemoteSegment remote = status.received.get(segment.seq);
            if (remote == null || !segment.sha256.equals(remote.sha256)
                    || segment.mp3Bytes != remote.bytes) {
                allRemote = false;
                break;
            }
        }
        if (manifest.recordingFinished && manifest.conversionFinished
                && allRemote && !status.committed) {
            listener.onState(sessionId,
                    "Committing the complete recording manifest");
            status = client.commit(manifest);
            applyStatus(manifest, status);
        }
        currentOperation = "idle";
        currentSequence = -1;
        currentDurableBytes = 0L;
        currentTotalBytes = 0L;
        lastProgressWallMs = System.currentTimeMillis();
        listener.onChanged();
    }

    private void applyStatus(ReliableSessionManifest local,
                             ReliableUploadClient.Status status) throws Exception {
        List<ReliableSessionStore.RemoteChunkState> remoteChunks = new ArrayList<>();
        for (Map.Entry<Integer, ReliableUploadClient.RemoteSegment> entry
                : status.received.entrySet()) {
            ReliableSessionManifest.Segment segment = local.findSegment(entry.getKey());
            if (segment == null) continue;
            ReliableUploadClient.RemoteSegment remote = entry.getValue();
            if (!segment.sha256.equals(remote.sha256)
                    || segment.mp3Bytes != remote.bytes) {
                throw new ReliableUploadClient.ProtocolException(409,
                        "Server chunk conflict at sequence " + segment.seq);
            }
            remoteChunks.add(new ReliableSessionStore.RemoteChunkState(
                    segment.seq, status.serverId, status.manifestRevision,
                    remote.receivedAtMs, remote.durableAtMs));
        }
        List<ReliableSessionStore.TranscriptState> transcripts = new ArrayList<>();
        for (ReliableUploadClient.Transcript transcript : status.transcripts.values()) {
            transcripts.add(new ReliableSessionStore.TranscriptState(
                    transcript.seq, transcript.state, transcript.text,
                    transcript.engine, transcript.createdAtMs, transcript.error));
        }
        store.reconcileRemoteState(local.sessionId, remoteChunks, transcripts,
                status.committed);
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void waitForSignal(long millis) {
        synchronized (wake) {
            if (!running.get()) return;
            try {
                wake.wait(Math.max(1L, millis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static org.json.JSONObject fields(Object... values) {
        org.json.JSONObject result = new org.json.JSONObject();
        for (int i = 0; values != null && i + 1 < values.length; i += 2) {
            try {
                result.put(String.valueOf(values[i]), values[i + 1]);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static String humanFailure(Throwable failure, boolean retryable,
                                       long retryDelayMs) {
        Throwable root = rootCause(failure);
        String type = root == null ? "UnknownException"
                : root.getClass().getSimpleName();
        String message = root == null || root.getMessage() == null
                ? "no exception message" : root.getMessage();
        if (!retryable) {
            return "Synchronization paused because the server or local data rejected "
                    + "the request: " + type + ": " + message
                    + ". Local audio remains intact; the persistent scheduler will "
                    + "recheck after configuration or data is corrected.";
        }
        return "Temporary transmission interruption: " + type + ": " + message
                + ". Every acknowledged byte remains durable on Jetson; retrying in "
                + retryDelayMs + " ms with resumable offset reconciliation.";
    }
}
