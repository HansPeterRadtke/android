package com.hans.android.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.hans.android.audio.SpoolChunk;
import com.hans.android.audio.SpoolStore;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SpoolUploader {
    public interface Listener {
        void onWaitingForNetwork();
        void onUploading(SpoolChunk chunk);
        void onAcknowledged(SpoolChunk chunk, int serverQueueDepth);
        void onRetryScheduled(String humanReason, long delayMs);
        void onIdle();
        void onFatal(String humanReason);
    }

    private final Context context;
    private final SpoolStore store;
    private final FdxClient client;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object wakeLock = new Object();
    private Thread thread;

    public SpoolUploader(Context context, SpoolStore store, String baseUrl, Listener listener) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.client = new FdxClient(baseUrl);
        this.listener = listener;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::loop, "voicebutton-uploader");
        thread.start();
    }

    public void signal() {
        synchronized (wakeLock) { wakeLock.notifyAll(); }
    }

    public void stop() {
        running.set(false);
        signal();
    }

    private void loop() {
        long backoffMs = 1000L;
        while (running.get()) {
            try {
                if (store.pendingCount() == 0) {
                    listener.onIdle();
                    waitForSignal(1500L);
                    continue;
                }
                if (!hasNetwork()) {
                    listener.onWaitingForNetwork();
                    waitForSignal(2500L);
                    continue;
                }
                SpoolChunk chunk = store.claimNext();
                if (chunk == null) {
                    waitForSignal(500L);
                    continue;
                }
                listener.onUploading(chunk);
                try {
                    FdxClient.UploadResult result = client.upload(chunk);
                    store.acknowledge(chunk);
                    listener.onAcknowledged(chunk, result.getServerQueueDepth());
                    backoffMs = 1000L;
                } catch (Exception uploadFailure) {
                    try {
                        store.release(chunk);
                    } catch (Exception releaseFailure) {
                        listener.onFatal("A queued audio file could not be returned to the retry queue");
                        waitForSignal(5000L);
                        continue;
                    }
                    String human = FdxClient.humanError(uploadFailure);
                    listener.onRetryScheduled(human, backoffMs);
                    waitForSignal(backoffMs);
                    backoffMs = Math.min(30000L, backoffMs * 2L);
                }
            } catch (Exception failure) {
                listener.onFatal("Temporary audio queue could not be read");
                waitForSignal(3000L);
            }
        }
    }

    private boolean hasNetwork() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                Network active = manager.getActiveNetwork();
                if (active == null) return false;
                NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (SecurityException ignored) {
            return true;
        }
    }

    private void waitForSignal(long millis) {
        synchronized (wakeLock) {
            if (!running.get()) return;
            try { wakeLock.wait(millis); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
