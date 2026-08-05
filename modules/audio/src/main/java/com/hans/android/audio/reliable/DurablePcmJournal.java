package com.hans.android.audio.reliable;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/** A minimal append-only PCM sink used directly by the microphone thread. */
final class DurablePcmJournal implements Closeable {
    private final File openFile;
    private final File closedFile;
    private final FileOutputStream output;
    private byte[] scratch = new byte[0];
    private long bytesWritten;
    private boolean closed;

    DurablePcmJournal(File directory, int sequence, int inputSampleRate)
            throws IOException {
        openFile = new File(directory, String.format(Locale.US,
                "segment_%06d_%05d.open.pcm", sequence,
                inputSampleRate));
        closedFile = new File(directory, String.format(Locale.US,
                "segment_%06d_%05d.pcm", sequence,
                inputSampleRate));
        if (openFile.exists() && !openFile.delete()) {
            throw new IOException("Could not replace stale PCM journal");
        }
        output = new FileOutputStream(openFile, false);
    }

    void append(short[] samples, int count) throws IOException {
        ensureOpen();
        if (samples == null || count < 0 || count > samples.length) {
            throw new IOException("Invalid PCM append request");
        }
        int byteCount = count * 2;
        if (scratch.length < byteCount) scratch = new byte[byteCount];
        for (int i = 0; i < count; i++) {
            short value = samples[i];
            scratch[i * 2] = (byte)(value & 0xff);
            scratch[i * 2 + 1] = (byte)((value >>> 8) & 0xff);
        }
        output.write(scratch, 0, byteCount);
        bytesWritten += byteCount;
    }

    long sync() throws IOException {
        ensureOpen();
        long started = System.nanoTime();
        output.flush();
        output.getFD().sync();
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    File publish() throws IOException {
        ensureOpen();
        sync();
        output.close();
        closed = true;
        if (closedFile.exists() && !closedFile.delete()) {
            throw new IOException("Could not replace closed PCM journal");
        }
        if (!openFile.renameTo(closedFile)) {
            throw new IOException("Could not atomically publish PCM journal");
        }
        return closedFile;
    }

    void closePreservingOpenJournal() throws IOException {
        if (closed) return;
        sync();
        output.close();
        closed = true;
    }

    void discard() throws IOException {
        if (!closed) {
            output.close();
            closed = true;
        }
        if (openFile.exists() && !openFile.delete()) {
            throw new IOException("Could not discard empty PCM journal");
        }
    }

    File openFile() { return openFile; }
    File closedFile() { return closedFile; }
    long bytesWritten() { return bytesWritten; }

    @Override public void close() throws IOException {
        closePreservingOpenJournal();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("PCM journal is already closed");
    }
}
