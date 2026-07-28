package com.hans.android.audio;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SpoolStore {
    private static final Pattern SAFE_SESSION = Pattern.compile("^[A-Za-z0-9-]{1,96}$");
    private static final long MIN_FREE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_SPOOL_BYTES = 512L * 1024L * 1024L;

    private final File root;
    private final String conversationId;

    public SpoolStore(Context context) throws IOException {
        root = new File(context.getNoBackupFilesDir(), "voice_spool");
        ensureDirectory(root);
        conversationId = loadOrCreateConversationId();
        recoverClaims();
    }

    public File getRoot() { return root; }

    public synchronized String createSession() throws IOException {
        String sessionId = UUID.randomUUID().toString();
        ensureDirectory(sessionDirectory(sessionId));
        return sessionId;
    }

    public synchronized File writeChunk(String sessionId, int sequence, boolean finalChunk,
                                        byte[] pcm, int length, int sampleRate) throws IOException {
        validateSession(sessionId);
        byte[] wav = PcmWav.wrapPcm16Mono(pcm, length, sampleRate);
        ensureCapacity(wav.length);
        File session = sessionDirectory(sessionId);
        ensureDirectory(session);
        SpoolName name = new SpoolName(sequence, finalChunk, SpoolName.State.PENDING);
        File target = new File(session, name.fileName());
        File temp = new File(session, name.fileName() + ".tmp-" + UUID.randomUUID());
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(wav);
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not publish temporary audio chunk");
        }
        return target;
    }

    public synchronized SpoolChunk claimNext() throws IOException {
        List<File> files = allSpoolFiles(SpoolName.State.PENDING);
        files.sort(Comparator
                .comparing((File file) -> file.getParentFile().getName())
                .thenComparingInt(file -> {
                    SpoolName parsed = SpoolName.parse(file.getName());
                    return parsed == null ? Integer.MAX_VALUE : parsed.getSequence();
                }));
        for (File pending : files) {
            SpoolName parsed = SpoolName.parse(pending.getName());
            if (parsed == null) continue;
            File claimed = new File(pending.getParentFile(), parsed.withState(SpoolName.State.UPLOADING).fileName());
            if (pending.renameTo(claimed)) {
                return new SpoolChunk(claimed, pending.getParentFile().getName(),
                        parsed.getSequence(), parsed.isFinalChunk(), conversationId);
            }
        }
        return null;
    }

    public synchronized void acknowledge(SpoolChunk chunk) throws IOException {
        if (chunk == null || !chunk.getFile().exists()) return;
        File sent = new File(chunk.getFile().getParentFile(), "sent");
        ensureDirectory(sent);
        File retained = new File(sent, chunk.getFile().getName());
        if (retained.exists() && !retained.delete()) {
            throw new IOException("Could not replace retained audio chunk");
        }
        if (!chunk.getFile().renameTo(retained)) {
            throw new IOException("Could not retain acknowledged audio chunk");
        }
        cleanupChunkCopiesIfComplete(chunk.getSessionId());
    }

    public synchronized void release(SpoolChunk chunk) throws IOException {
        if (chunk == null || !chunk.getFile().exists()) return;
        SpoolName parsed = SpoolName.parse(chunk.getFile().getName());
        if (parsed == null) throw new IOException("Invalid claimed audio filename");
        File pending = new File(chunk.getFile().getParentFile(),
                parsed.withState(SpoolName.State.PENDING).fileName());
        if (!chunk.getFile().renameTo(pending)) {
            throw new IOException("Could not release audio chunk for retry");
        }
    }

    public synchronized File finalizeRecording(String sessionId) throws IOException {
        validateSession(sessionId);
        File session = sessionDirectory(sessionId);
        ensureDirectory(session);
        File ready = new File(session, "recording.wav");
        if (ready.isFile() && ready.length() > 44L) return ready;

        List<File> chunks = sessionChunkFiles(session);
        if (chunks.isEmpty()) throw new IOException("No local audio chunks are available");
        chunks.sort(Comparator.comparingInt(file -> {
            SpoolName parsed = SpoolName.parse(file.getName());
            return parsed == null ? Integer.MAX_VALUE : parsed.getSequence();
        }));

        File temp = new File(session, "recording.wav.tmp-" + UUID.randomUUID());
        long pcmBytes = 0L;
        try (RandomAccessFile out = new RandomAccessFile(temp, "rw")) {
            writeWavHeader(out, 0L, 16000);
            byte[] buffer = new byte[16384];
            for (File chunk : chunks) {
                try (FileInputStream in = new FileInputStream(chunk)) {
                    long skipped = 0L;
                    while (skipped < 44L) {
                        long n = in.skip(44L - skipped);
                        if (n <= 0L) throw new IOException("Invalid local WAV chunk");
                        skipped += n;
                    }
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        pcmBytes += read;
                    }
                }
            }
            out.getFD().sync();
            out.seek(0L);
            writeWavHeader(out, pcmBytes, 16000);
            out.getFD().sync();
        }
        if (!temp.renameTo(ready)) {
            temp.delete();
            throw new IOException("Could not publish the complete local recording");
        }
        cleanupChunkCopiesIfComplete(sessionId);
        return ready;
    }

    public synchronized File recordingFile(String sessionId) throws IOException {
        validateSession(sessionId);
        return new File(sessionDirectory(sessionId), "recording.wav");
    }

    public synchronized int pendingCount() { return allSpoolFiles(null).size(); }

    public synchronized long pendingBytes() {
        long total = 0L;
        for (File file : allSpoolFiles(null)) total += Math.max(0L, file.length());
        return total;
    }

    public synchronized void recoverClaims() throws IOException {
        for (File claimed : allSpoolFiles(SpoolName.State.UPLOADING)) {
            SpoolName parsed = SpoolName.parse(claimed.getName());
            if (parsed == null) continue;
            File pending = new File(claimed.getParentFile(), parsed.withState(SpoolName.State.PENDING).fileName());
            if (!claimed.renameTo(pending)) throw new IOException("Could not recover interrupted upload");
        }
    }

    public String getConversationId() { return conversationId; }

    private String loadOrCreateConversationId() throws IOException {
        File file = new File(root, "conversation.id");
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[256];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                String existing = new String(out.toByteArray(), java.nio.charset.StandardCharsets.US_ASCII).trim();
                if (SAFE_SESSION.matcher(existing).matches()) return existing;
            }
        }
        String created = "conversation-" + UUID.randomUUID();
        File temp = new File(root, "conversation.id.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(created.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not replace conversation identity");
        if (!temp.renameTo(file)) throw new IOException("Could not publish conversation identity");
        return created;
    }

    private void ensureCapacity(long nextBytes) throws IOException {
        StatFs stats = new StatFs(root.getAbsolutePath());
        long available = stats.getAvailableBytes();
        if (available - nextBytes < MIN_FREE_BYTES) {
            throw new IOException("Temporary storage is too full to protect more audio");
        }
        if (pendingBytes() + nextBytes > MAX_SPOOL_BYTES) {
            throw new IOException("Temporary audio limit reached");
        }
    }

    private List<File> allSpoolFiles(SpoolName.State state) {
        List<File> result = new ArrayList<>();
        File[] sessions = root.listFiles(File::isDirectory);
        if (sessions == null) return result;
        for (File session : sessions) {
            File[] files = session.listFiles(File::isFile);
            if (files == null) continue;
            for (File file : files) {
                SpoolName parsed = SpoolName.parse(file.getName());
                if (parsed != null && (state == null || parsed.getState() == state)) result.add(file);
            }
        }
        return result;
    }

    private List<File> sessionChunkFiles(File session) {
        List<File> result = new ArrayList<>();
        File[] direct = session.listFiles(File::isFile);
        if (direct != null) {
            for (File file : direct) if (SpoolName.parse(file.getName()) != null) result.add(file);
        }
        File sent = new File(session, "sent");
        File[] retained = sent.listFiles(File::isFile);
        if (retained != null) {
            for (File file : retained) if (SpoolName.parse(file.getName()) != null) result.add(file);
        }
        return result;
    }

    private void cleanupChunkCopiesIfComplete(String sessionId) throws IOException {
        File session = sessionDirectory(sessionId);
        File recording = new File(session, "recording.wav");
        if (!recording.isFile() || recording.length() <= 44L) return;
        File[] direct = session.listFiles(File::isFile);
        if (direct != null) {
            for (File file : direct) {
                if (SpoolName.parse(file.getName()) != null) return;
            }
        }
        File sent = new File(session, "sent");
        File[] retained = sent.listFiles(File::isFile);
        if (retained != null) for (File file : retained) file.delete();
        sent.delete();
    }

    private File sessionDirectory(String sessionId) throws IOException {
        validateSession(sessionId);
        return new File(root, sessionId);
    }

    private static void validateSession(String sessionId) throws IOException {
        if (sessionId == null || !SAFE_SESSION.matcher(sessionId).matches()) {
            throw new IOException("Invalid recording session id");
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create private audio storage");
        }
    }

    private static void writeWavHeader(RandomAccessFile out, long pcmBytes, int sampleRate) throws IOException {
        out.writeBytes("RIFF"); writeLe32(out, 36L + pcmBytes); out.writeBytes("WAVE");
        out.writeBytes("fmt "); writeLe32(out, 16L); writeLe16(out, 1); writeLe16(out, 1);
        writeLe32(out, sampleRate); writeLe32(out, sampleRate * 2L); writeLe16(out, 2); writeLe16(out, 16);
        out.writeBytes("data"); writeLe32(out, pcmBytes);
    }

    private static void writeLe16(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xff); out.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(RandomAccessFile out, long value) throws IOException {
        out.write((int)(value & 0xff)); out.write((int)((value >>> 8) & 0xff));
        out.write((int)((value >>> 16) & 0xff)); out.write((int)((value >>> 24) & 0xff));
    }
}
