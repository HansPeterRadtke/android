package com.hans.android.audio.reliable;

import android.content.Context;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReliableSessionStore {
    public static final class RemoteChunkState {
        public final int seq;
        public final String serverId;
        public final long revision;
        public final long receivedAtMs;
        public final long durableAtMs;

        public RemoteChunkState(int seq, String serverId, long revision,
                                long receivedAtMs, long durableAtMs) {
            this.seq = seq;
            this.serverId = serverId == null ? "" : serverId;
            this.revision = revision;
            this.receivedAtMs = receivedAtMs;
            this.durableAtMs = durableAtMs;
        }
    }

    public static final class TranscriptState {
        public final int seq;
        public final String state;
        public final String text;
        public final String engine;
        public final long createdAtMs;
        public final String error;

        public TranscriptState(int seq, String state, String text, String engine,
                               long createdAtMs, String error) {
            this.seq = seq;
            this.state = state == null ? "PENDING" : state;
            this.text = text == null ? "" : text;
            this.engine = engine == null ? "" : engine;
            this.createdAtMs = createdAtMs;
            this.error = error == null ? "" : error;
        }
    }

    public static final class Folder {
        public final String id;
        public final String name;
        public final String parentId;
        public final long createdAtMs;
        public final String remoteName;
        public final String remoteParentId;
        public final String path;

        public Folder(String id, String name, long createdAtMs) {
            this(id, name, "", createdAtMs, name, "", name);
        }

        public Folder(String id, String name, long createdAtMs,
                      String remoteName) {
            this(id, name, "", createdAtMs, remoteName, "", name);
        }

        public Folder(String id, String name, String parentId,
                      long createdAtMs, String remoteName,
                      String remoteParentId, String path) {
            this.id = id;
            this.name = name;
            this.parentId = parentId == null ? "" : parentId;
            this.createdAtMs = createdAtMs;
            this.remoteName = remoteName == null ? "" : remoteName;
            this.remoteParentId = remoteParentId == null ? "" : remoteParentId;
            this.path = path == null || path.isEmpty() ? name : path;
        }

        public boolean needsRemoteSync() {
            return !name.equals(remoteName)
                    || !parentId.equals(remoteParentId);
        }

        @Override public String toString() { return path; }
    }

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern WAV_PATTERN = Pattern.compile("^segment_(\\d{6})(?:\\.open)?\\.wav$");
    private static final Pattern MP3_PATTERN = Pattern.compile("^segment_(\\d{6})\\.mp3$");
    private static final Pattern OPEN_MP3_PATTERN = Pattern.compile("^segment_(\\d{6})\\.open\\.mp3$");
    private static final Pattern PCM_PATTERN = Pattern.compile("^segment_(\\d{6})_(\\d{5})(?:\\.open)?\\.pcm$");
    private static final long MIN_FREE_BYTES = 256L * 1024L * 1024L;

    private final File root;
    private final File foldersRoot;
    private final File folderIndex;
    private final String conversationId;

    public ReliableSessionStore(Context context) throws IOException {
        this(context, true);
    }

    private ReliableSessionStore(Context context, boolean recover) throws IOException {
        root = new File(context.getNoBackupFilesDir(), "reliable_audio_sessions");
        foldersRoot = new File(root, "folders");
        folderIndex = new File(root, "folders.json");
        ensureDirectory(root);
        ensureDirectory(foldersRoot);
        ensureDefaultFolder();
        repairFolderIndexFromDisk();
        conversationId = loadOrCreateConversationId();
        if (recover) {
            migrateLegacySessions();
            recoverAll();
        }
    }

    public static ReliableSessionStore openForBrowsing(Context context)
            throws IOException {
        return new ReliableSessionStore(context, false);
    }

    public File getRoot() { return root; }
    public String getConversationId() { return conversationId; }

    static boolean isPcmJournalName(String name) {
        return name != null && PCM_PATTERN.matcher(name).matches();
    }

    static boolean isOpenPcmJournalName(String name) {
        return isPcmJournalName(name) && name.contains(".open.");
    }

    public synchronized List<Folder> listFolders() {
        List<Folder> result = new ArrayList<>();
        try {
            JSONObject index = readFolderIndex();
            JSONArray array = index.optJSONArray("folders");
            Map<String, Folder> raw = new LinkedHashMap<>();
            if (array != null) for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("folder_id", "default");
                String name = item.optString("name", "Default");
                String parentId = item.optString("parent_folder_id", "");
                String remoteName = item.has("remote_name")
                        ? item.optString("remote_name", "") : name;
                String remoteParentId = item.has("remote_parent_folder_id")
                        ? item.optString("remote_parent_folder_id", "")
                        : parentId;
                raw.put(id, new Folder(id, name, parentId,
                        item.optLong("created_at_ms", 0L), remoteName,
                        remoteParentId, name));
            }
            for (Folder value : raw.values()) {
                result.add(new Folder(value.id, value.name, value.parentId,
                        value.createdAtMs, value.remoteName,
                        value.remoteParentId,
                        buildFolderPath(value.id, raw)));
            }
        } catch (Exception ignored) {}
        if (result.isEmpty()) {
            result.add(new Folder("default", "Default", "", 0L,
                    "Default", "", "Default"));
        }
        result.sort(Comparator.comparing(folder ->
                folder.path.toLowerCase(Locale.US)));
        return result;
    }

    private static String buildFolderPath(String folderId,
                                          Map<String, Folder> folders) {
        ArrayList<String> names = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        String current = folderId;
        while (current != null && !current.isEmpty()) {
            if (!seen.add(current)) break;
            Folder value = folders.get(current);
            if (value == null) break;
            names.add(0, value.name);
            current = value.parentId;
        }
        return names.isEmpty() ? folderId : android.text.TextUtils.join("/", names);
    }

    public synchronized List<Folder> childFolders(String parentFolderId) {
        String parent = parentFolderId == null ? "" : parentFolderId;
        List<Folder> result = new ArrayList<>();
        for (Folder folder : listFolders()) {
            if (parent.equals(folder.parentId)) result.add(folder);
        }
        result.sort(Comparator.comparing(folder ->
                folder.name.toLowerCase(Locale.US)));
        return result;
    }

    public synchronized String folderPath(String folderId) throws IOException {
        return getFolder(folderId).path;
    }

    public synchronized Folder createFolder(String requestedName)
            throws IOException {
        return createFolder(requestedName, "");
    }

    public synchronized Folder createFolder(String requestedName,
                                             String parentFolderId)
            throws IOException {
        String name = requestedName == null ? ""
                : requestedName.trim().replaceAll("\s+", " ");
        if (name.isEmpty() || name.length() > 96) {
            throw new IOException(
                    "Folder name must contain one to ninety-six characters");
        }
        String parentId = parentFolderId == null ? "" : parentFolderId;
        Folder parent = null;
        if (!parentId.isEmpty()) parent = getFolder(parentId);
        String base = name.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "folder";
        String id = (base.length() > 48 ? base.substring(0, 48) : base)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        JSONObject index = readFolderIndex();
        JSONArray array = index.optJSONArray("folders");
        long now = System.currentTimeMillis();
        JSONObject value = new JSONObject();
        try {
            if (array == null) {
                array = new JSONArray();
                index.put("folders", array);
            }
            value.put("folder_id", id);
            value.put("name", name);
            value.put("parent_folder_id", parentId);
            value.put("remote_name", "");
            value.put("remote_parent_folder_id", "");
            value.put("created_at_ms", now);
            value.put("updated_at_ms", now);
            array.put(value);
            index.put("schema_version", 2);
            index.put("revision", index.optLong("revision", 0L) + 1L);
        } catch (Exception failure) {
            throw new IOException("Could not serialize folder metadata", failure);
        }
        durableJson(folderIndex, index);
        ensureDirectory(new File(new File(foldersRoot, id), "sessions"));
        fsyncDirectory(foldersRoot);
        String path = parent == null ? name : parent.path + "/" + name;
        return new Folder(id, name, parentId, now, "", "", path);
    }

    public synchronized Folder getFolder(String folderId) throws IOException {
        String id = folderId == null || folderId.isEmpty() ? "default" : folderId;
        validateId(id);
        for (Folder folder : listFolders()) if (folder.id.equals(id)) return folder;
        throw new IOException("Unknown recording folder");
    }

    public synchronized List<Folder> foldersNeedingSync() {
        List<Folder> result = new ArrayList<>();
        for (Folder folder : listFolders()) if (folder.needsRemoteSync()) result.add(folder);
        return result;
    }

    public synchronized boolean hasPendingFolderSync() {
        for (Folder folder : listFolders()) if (folder.needsRemoteSync()) return true;
        return false;
    }

    public synchronized void markFolderRemote(String folderId,
                                              String remoteName)
            throws IOException {
        Folder folder = getFolder(folderId);
        markFolderRemote(folderId, remoteName, folder.parentId);
    }

    public synchronized void markFolderRemote(String folderId,
                                              String remoteName,
                                              String remoteParentFolderId)
            throws IOException {
        validateId(folderId);
        JSONObject index = readFolderIndex();
        JSONArray array = index.optJSONArray("folders");
        boolean found = false;
        try {
            if (array != null) for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null
                        && folderId.equals(item.optString("folder_id"))) {
                    item.put("remote_name",
                            remoteName == null ? "" : remoteName);
                    item.put("remote_parent_folder_id",
                            remoteParentFolderId == null
                                    ? "" : remoteParentFolderId);
                    item.put("updated_at_ms", System.currentTimeMillis());
                    found = true;
                    break;
                }
            }
            if (!found) throw new IOException("Unknown recording folder");
            index.put("revision", index.optLong("revision", 0L) + 1L);
        } catch (org.json.JSONException failure) {
            throw new IOException(
                    "Could not update folder synchronization metadata",
                    failure);
        }
        durableJson(folderIndex, index);
    }

    public synchronized ReliableSessionManifest createSession(String selectedInput, int selectedDeviceId) throws IOException {
        return createSession(selectedInput, selectedDeviceId, "default", "Default");
    }

    public synchronized ReliableSessionManifest createSession(String selectedInput, int selectedDeviceId,
                                                               String folderId, String folderName) throws IOException {
        Folder folder = getFolder(folderId);
        ReliableSessionManifest manifest = new ReliableSessionManifest();
        manifest.sessionId = UUID.randomUUID().toString();
        manifest.conversationId = conversationId;
        manifest.createdAt = System.currentTimeMillis();
        manifest.updatedAt = manifest.createdAt;
        manifest.folderId = folder.id;
        manifest.folderName = folderName == null || folderName.isEmpty() ? folder.name : folderName;
        manifest.remoteFolderId = folder.id;
        manifest.remoteFolderName = folder.name;
        manifest.displayName = RecordingFileNames.defaultDisplayName(manifest.createdAt);
        manifest.finalMp3Name = RecordingFileNames.defaultMp3Name(
                manifest.createdAt, manifest.sessionId);
        manifest.selectedInput = selectedInput;
        manifest.selectedDeviceId = selectedDeviceId;
        manifest.state = "RECORDING";
        manifest.paused = false;
        manifest.autoResumeRequested = true;
        ensureDirectory(sessionDir(manifest.folderId, manifest.sessionId));
        save(manifest);
        return manifest.copy();
    }

    public synchronized ReliableSessionManifest load(String sessionId) throws IOException {
        validateId(sessionId);
        File file = manifestFile(sessionId);
        if (!file.isFile()) throw new IOException("Missing session metadata");
        try {
            return ReliableSessionManifest.fromJson(new JSONObject(readText(file)));
        } catch (Exception failure) {
            throw new IOException("Could not parse session metadata", failure);
        }
    }

    public synchronized List<ReliableSessionManifest> list() {
        List<ReliableSessionManifest> result = new ArrayList<>();
        File[] folders = foldersRoot.listFiles(File::isDirectory);
        if (folders == null) return result;
        for (File folder : folders) {
            File sessions = new File(folder, "sessions");
            File[] dirs = sessions.listFiles(File::isDirectory);
            if (dirs == null) continue;
            for (File dir : dirs) {
                File metadata = new File(dir, "manifest.json");
                if (!metadata.isFile()) continue;
                try { result.add(ReliableSessionManifest.fromJson(new JSONObject(readText(metadata)))); }
                catch (Exception ignored) {}
            }
        }
        result.sort(Comparator.comparingLong((ReliableSessionManifest value) -> value.createdAt));
        return result;
    }

    public synchronized ReliableSessionManifest latestInterrupted() {
        ReliableSessionManifest latest = null;
        for (ReliableSessionManifest manifest : list()) {
            if (manifest.isInterrupted() && (latest == null || manifest.createdAt > latest.createdAt)) latest = manifest;
        }
        return latest == null ? null : latest.copy();
    }

    public synchronized ReliableSessionManifest latestUnfinished() {
        ReliableSessionManifest latest = null;
        for (ReliableSessionManifest manifest : list()) {
            if (!manifest.recordingFinished && (latest == null || manifest.createdAt > latest.createdAt)) latest = manifest;
        }
        return latest == null ? null : latest.copy();
    }

    public synchronized boolean discardIfEmpty(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        if (!manifest.isDiscardableEmptySession()) return false;
        File directory = sessionDir(sessionId);
        File[] audio = directory.listFiles(file -> {
            String name = file.getName();
            return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".pcm");
        });
        if (audio != null) {
            for (File file : audio) if (file.length() > 0L) return false;
        }
        deleteRecursively(directory);
        return true;
    }

    public synchronized void fsyncSessionDirectory(String sessionId) throws IOException {
        fsyncDirectory(sessionDir(sessionId));
    }

    public synchronized File sessionDirectory(String sessionId) throws IOException { return sessionDir(sessionId); }

    public synchronized int nextAvailableSegmentSeq(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        int next = Math.max(0, manifest.nextSeq);
        File[] files = sessionDir(sessionId).listFiles();
        if (files != null) for (File file : files) {
            Matcher matcher = PCM_PATTERN.matcher(file.getName());
            if (!matcher.matches()) matcher = WAV_PATTERN.matcher(file.getName());
            if (!matcher.matches()) matcher = MP3_PATTERN.matcher(file.getName());
            if (!matcher.matches()) matcher = OPEN_MP3_PATTERN.matcher(file.getName());
            if (matcher.matches()) {
                next = Math.max(next, Integer.parseInt(matcher.group(1)) + 1);
            }
        }
        return next;
    }

    public synchronized void commitPcmJournal(String sessionId, int seq,
                                               File pcmFile,
                                               int inputSampleRate,
                                               long pcmBytes,
                                               long durationMs,
                                               long createdAtMs,
                                               long durableAtMs)
            throws IOException {
        if (pcmFile == null || !pcmFile.isFile() || pcmFile.length() < 2L) {
            throw new IOException("PCM journal is missing or empty");
        }
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) {
            segment = new ReliableSessionManifest.Segment();
            segment.seq = seq;
            manifest.segments.add(segment);
        }
        int rate = inputSampleRate <= 0 ? 16000 : inputSampleRate;
        long bytes = Math.max(0L, Math.min(pcmBytes, pcmFile.length()));
        long inputSamples = bytes / 2L;
        long outputSamples = inputSamples
                * ReliableSessionManifest.OUTPUT_SAMPLE_RATE / rate;
        long startSample = 0L;
        for (ReliableSessionManifest.Segment existing : manifest.segments) {
            if (existing.seq < seq) {
                startSample = Math.max(startSample, existing.endSample);
            }
        }
        segment.pcmJournalName = pcmFile.getName();
        segment.pcmInputSampleRate = rate;
        segment.pcmBytes = bytes;
        segment.durationMs = durationMs > 0L ? durationMs
                : outputSamples * 1000L
                / ReliableSessionManifest.OUTPUT_SAMPLE_RATE;
        segment.startSample = startSample;
        segment.endSample = startSample + outputSamples;
        segment.sampleRate = ReliableSessionManifest.OUTPUT_SAMPLE_RATE;
        segment.createdAtMs = createdAtMs > 0L
                ? createdAtMs : System.currentTimeMillis();
        segment.closedAtMs = System.currentTimeMillis();
        segment.localDurableAtMs = durableAtMs > 0L
                ? durableAtMs : segment.closedAtMs;
        manifest.nextSeq = Math.max(manifest.nextSeq, seq + 1);
        recalculate(manifest);
        manifest.error = "";
        manifest.state = manifest.recordingFinished
                ? "FINALIZING" : "RECORDING";
        save(manifest);
    }

    public synchronized void commitWavSegment(String sessionId, int seq, File wavFile, long pcmBytes) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) {
            segment = new ReliableSessionManifest.Segment();
            segment.seq = seq;
            manifest.segments.add(segment);
        }
        segment.wavName = wavFile.getName();
        segment.pcmBytes = Math.max(0L, pcmBytes);
        segment.durationMs = pcmBytes * 1000L / (16000L * 2L);
        manifest.nextSeq = Math.max(manifest.nextSeq, seq + 1);
        recalculate(manifest);
        manifest.state = manifest.recordingFinished ? "FINALIZING" : "RECORDING";
        manifest.error = "";
        save(manifest);
    }

    public synchronized void commitMp3Segment(String sessionId, int seq, File mp3File,
                                              long durationMs) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        long start = manifest.totalOutputSamples;
        long end = start + Math.max(0L, durationMs) * ReliableSessionManifest.OUTPUT_SAMPLE_RATE / 1000L;
        commitMp3Segment(sessionId, seq, mp3File, durationMs, start, end,
                ReliableSessionManifest.OUTPUT_SAMPLE_RATE, System.currentTimeMillis(),
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    public synchronized void commitMp3Segment(String sessionId, int seq, File mp3File,
                                              long durationMs, long startSample, long endSample,
                                              int sampleRate, long createdAtMs, long closedAtMs,
                                              long durableAtMs) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) {
            segment = new ReliableSessionManifest.Segment();
            segment.seq = seq;
            manifest.segments.add(segment);
        }
        segment.mp3Name = mp3File.getName();
        segment.mp3Bytes = mp3File.length();
        segment.sha256 = sha256File(mp3File);
        segment.durationMs = Math.max(0L, durationMs);
        segment.startSample = Math.max(0L, startSample);
        segment.endSample = Math.max(segment.startSample, endSample);
        segment.sampleRate = sampleRate <= 0 ? ReliableSessionManifest.OUTPUT_SAMPLE_RATE : sampleRate;
        segment.createdAtMs = createdAtMs;
        segment.closedAtMs = closedAtMs;
        segment.localDurableAtMs = durableAtMs;
        segment.transcriptState = "PENDING";
        segment.wavName = "";
        segment.pcmBytes = 0L;
        manifest.nextSeq = Math.max(manifest.nextSeq, seq + 1);
        recalculate(manifest);
        manifest.error = "";
        manifest.state = manifest.recordingFinished ? "FINALIZING" : "RECORDING";
        save(manifest);
    }

    public synchronized File pcmJournalFile(String sessionId, ReliableSessionManifest.Segment segment) throws IOException {
        return new File(sessionDir(sessionId), segment.pcmJournalName);
    }

    public synchronized void markPcmJournalEncoded(String sessionId, int seq, File mp3File,
                                                   long durationMs, long startSample,
                                                   long endSample) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment before = manifest.findSegment(seq);
        if (before == null || before.pcmJournalName.isEmpty()) {
            throw new IOException("Missing PCM journal metadata");
        }
        String journalName = before.pcmJournalName;
        int inputRate = before.pcmInputSampleRate;
        long createdAt = before.createdAtMs > 0L ? before.createdAtMs : manifest.createdAt;
        commitMp3Segment(sessionId, seq, mp3File, durationMs, startSample, endSample,
                ReliableSessionManifest.OUTPUT_SAMPLE_RATE, createdAt,
                System.currentTimeMillis(), System.currentTimeMillis());
        manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment != null) {
            segment.pcmJournalName = "";
            segment.pcmInputSampleRate = inputRate;
            segment.pcmBytes = 0L;
            save(manifest);
        }
        File journal = new File(sessionDir(sessionId), journalName);
        if (journal.exists() && !journal.delete()) {
            throw new IOException("Recovered PCM journal was encoded but could not be deleted");
        }
        fsyncDirectory(journal.getParentFile());
    }

    public synchronized boolean clearVerifiedPcmJournal(String sessionId, int seq) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null || segment.pcmJournalName.isEmpty()
                || segment.mp3Name.isEmpty() || segment.sha256.isEmpty()) return false;
        File mp3 = mp3File(sessionId, segment);
        if (!mp3.isFile() || mp3.length() != segment.mp3Bytes
                || !segment.sha256.equals(sha256File(mp3))) return false;
        String journalName = segment.pcmJournalName;
        segment.pcmJournalName = "";
        segment.pcmBytes = 0L;
        save(manifest);
        File journal = new File(sessionDir(sessionId), journalName);
        if (journal.exists() && !journal.delete()) {
            throw new IOException("Verified PCM journal could not be deleted");
        }
        fsyncDirectory(journal.getParentFile());
        return true;
    }

    public synchronized void markSegmentEncoded(String sessionId, int seq, File mp3File) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) throw new IOException("Missing WAV segment metadata");
        segment.mp3Name = mp3File.getName();
        segment.mp3Bytes = mp3File.length();
        segment.sha256 = sha256File(mp3File);
        manifest.error = "";
        save(manifest);
        if (!segment.wavName.isEmpty()) {
            File wav = new File(sessionDir(sessionId), segment.wavName);
            if (wav.exists()) wav.delete();
            manifest = load(sessionId);
            segment = manifest.findSegment(seq);
            if (segment != null) segment.wavName = "";
            save(manifest);
        }
    }

    public synchronized void markPaused(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        if (manifest.recordingFinished) throw new IOException("Recording is already finished");
        manifest.paused = true;
        manifest.autoResumeRequested = false;
        manifest.state = "PAUSED";
        manifest.error = "";
        save(manifest);
    }

    public synchronized void markPreviewReady(String sessionId, File previewMp3) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.finalMp3Name = previewMp3.getName();
        manifest.finalMp3Bytes = previewMp3.length();
        manifest.finalMp3Sha256 = sha256File(previewMp3);
        if (manifest.paused) manifest.state = "PAUSED";
        manifest.error = "";
        save(manifest);
    }

    public synchronized void markInterrupted(String sessionId, String error) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        if (manifest.recordingFinished) return;
        manifest.paused = false;
        manifest.state = "INTERRUPTED";
        manifest.error = error == null ? "" : error;
        save(manifest);
    }

    public synchronized void markResumed(String sessionId, String selectedInput, int selectedDeviceId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.selectedInput = selectedInput;
        manifest.selectedDeviceId = selectedDeviceId;
        manifest.paused = false;
        manifest.autoResumeRequested = true;
        manifest.state = "RECORDING";
        manifest.error = "";
        save(manifest);
    }

    public synchronized void markAutoResumeRequested(String sessionId, boolean requested) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        if (manifest.recordingFinished || manifest.paused) requested = false;
        if (manifest.autoResumeRequested == requested) return;
        manifest.autoResumeRequested = requested;
        save(manifest);
    }

    public synchronized void markRecordingFinished(String sessionId, String reason) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.recordingFinished = true;
        manifest.paused = false;
        manifest.autoResumeRequested = false;
        manifest.finishReason = reason == null ? "normal" : reason;
        manifest.state = "FINALIZING";
        save(manifest);
    }

    public synchronized void markConversionFinished(String sessionId, File finalMp3) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.conversionFinished = true;
        manifest.finalMp3Name = finalMp3.getName();
        manifest.finalMp3Bytes = finalMp3.length();
        manifest.finalMp3Sha256 = sha256File(finalMp3);
        manifest.state = manifest.remoteCommitted ? "COMPLETE" : "READY";
        manifest.error = "";
        save(manifest);
        deleteWavSources(sessionId);
    }

    public synchronized void markSendAttempt(String sessionId, int seq, long atMs, String error) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment != null) {
            segment.sendAttempts++;
            if (segment.firstSendAtMs <= 0L) segment.firstSendAtMs = atMs;
            segment.lastSendAtMs = atMs;
            segment.lastSendError = error == null ? "" : error;
        }
        save(manifest);
    }

    public synchronized void markSendError(String sessionId, int seq, long atMs,
                                               String error) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) return;
        segment.lastSendAtMs = atMs;
        segment.lastSendError = error == null ? "" : error;
        save(manifest);
    }

    public synchronized void markRemotePartProgress(String sessionId, int seq,
                                                        long durableBytes,
                                                        String serverId,
                                                        long revision) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null || segment.remoteAccepted) return;
        long normalized = Math.max(0L, Math.min(segment.mp3Bytes, durableBytes));
        String normalizedServer = serverId == null ? "" : serverId;
        if (segment.remotePartialBytes == normalized
                && segment.remoteServerId.equals(normalizedServer)
                && segment.remoteManifestRevision == revision) return;
        segment.remotePartialBytes = normalized;
        segment.remoteServerId = normalizedServer;
        segment.remoteManifestRevision = revision;
        manifest.remoteServerId = normalizedServer.isEmpty()
                ? manifest.remoteServerId : normalizedServer;
        manifest.remoteManifestRevision = Math.max(manifest.remoteManifestRevision, revision);
        save(manifest);
    }

    public synchronized void markRemoteAccepted(String sessionId, int seq) throws IOException {
        markRemoteAccepted(sessionId, seq, "", 0L, 0L, 0L);
    }

    public synchronized void markRemoteAccepted(String sessionId, int seq, String serverId,
                                                long revision, long receivedAtMs,
                                                long durableAtMs) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) return;
        String normalizedServer = serverId == null ? "" : serverId;
        boolean changed = !segment.remoteAccepted
                || !segment.remoteServerId.equals(normalizedServer)
                || segment.remoteManifestRevision != revision
                || segment.remoteReceivedAtMs != receivedAtMs
                || segment.remoteDurableAtMs != durableAtMs
                || !segment.lastSendError.isEmpty();
        if (!changed) return;
        segment.remoteAccepted = true;
        segment.remotePartialBytes = segment.mp3Bytes;
        segment.remoteServerId = normalizedServer;
        segment.remoteManifestRevision = revision;
        segment.remoteReceivedAtMs = receivedAtMs;
        segment.remoteDurableAtMs = durableAtMs;
        segment.lastSendError = "";
        manifest.remoteServerId = normalizedServer.isEmpty()
                ? manifest.remoteServerId : normalizedServer;
        manifest.remoteManifestRevision = Math.max(manifest.remoteManifestRevision, revision);
        save(manifest);
    }

    public synchronized void reconcileRemoteState(
            String sessionId, List<RemoteChunkState> remoteChunks,
            List<TranscriptState> transcripts, boolean committed) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        boolean changed = false;
        boolean transcriptChanged = false;
        if (remoteChunks != null) for (RemoteChunkState remote : remoteChunks) {
            ReliableSessionManifest.Segment segment = manifest.findSegment(remote.seq);
            if (segment == null) continue;
            if (!segment.remoteAccepted
                    || segment.remotePartialBytes != segment.mp3Bytes
                    || !segment.remoteServerId.equals(remote.serverId)
                    || segment.remoteManifestRevision != remote.revision
                    || segment.remoteReceivedAtMs != remote.receivedAtMs
                    || segment.remoteDurableAtMs != remote.durableAtMs
                    || !segment.lastSendError.isEmpty()) {
                segment.remoteAccepted = true;
                segment.remotePartialBytes = segment.mp3Bytes;
                segment.remoteServerId = remote.serverId;
                segment.remoteManifestRevision = remote.revision;
                segment.remoteReceivedAtMs = remote.receivedAtMs;
                segment.remoteDurableAtMs = remote.durableAtMs;
                segment.lastSendError = "";
                changed = true;
            }
            if (!remote.serverId.isEmpty()
                    && !manifest.remoteServerId.equals(remote.serverId)) {
                manifest.remoteServerId = remote.serverId;
                changed = true;
            }
            long nextRevision = Math.max(manifest.remoteManifestRevision,
                    remote.revision);
            if (nextRevision != manifest.remoteManifestRevision) {
                manifest.remoteManifestRevision = nextRevision;
                changed = true;
            }
        }
        if (transcripts != null) for (TranscriptState update : transcripts) {
            ReliableSessionManifest.Segment segment = manifest.findSegment(update.seq);
            if (segment == null) continue;
            if (!segment.transcriptState.equals(update.state)
                    || !segment.transcriptText.equals(update.text)
                    || !segment.transcriptEngine.equals(update.engine)
                    || segment.transcriptCreatedAtMs != update.createdAtMs
                    || !segment.transcriptError.equals(update.error)) {
                segment.transcriptState = update.state;
                segment.transcriptText = update.text;
                segment.transcriptEngine = update.engine;
                segment.transcriptCreatedAtMs = update.createdAtMs;
                segment.transcriptError = update.error;
                changed = true;
                transcriptChanged = true;
            }
        }
        if (committed && (!manifest.remoteCommitted
                || (manifest.conversionFinished
                && !"COMPLETE".equals(manifest.state))
                || !manifest.error.isEmpty())) {
            manifest.remoteCommitted = true;
            manifest.state = manifest.conversionFinished ? "COMPLETE" : manifest.state;
            manifest.error = "";
            changed = true;
        }
        File aggregateTranscript = new File(sessionDir(sessionId), "transcript.txt");
        if (transcripts != null && !transcripts.isEmpty()
                && !aggregateTranscript.isFile()) transcriptChanged = true;
        if (!changed && !transcriptChanged) return;
        if (changed) save(manifest);
        if (transcriptChanged) rebuildTranscript(manifest);
    }

    public synchronized void markTranscript(String sessionId, int seq, String state,
                                            String text, String engine, long createdAtMs,
                                            String error) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) return;
        String newState = state == null ? "PENDING" : state;
        String newText = text == null ? "" : text;
        String newEngine = engine == null ? "" : engine;
        String newError = error == null ? "" : error;
        if (segment.transcriptState.equals(newState)
                && segment.transcriptText.equals(newText)
                && segment.transcriptEngine.equals(newEngine)
                && segment.transcriptCreatedAtMs == createdAtMs
                && segment.transcriptError.equals(newError)) return;
        segment.transcriptState = newState;
        segment.transcriptText = newText;
        segment.transcriptEngine = newEngine;
        segment.transcriptCreatedAtMs = createdAtMs;
        segment.transcriptError = newError;
        File textDir = new File(sessionDir(sessionId), "text");
        ensureDirectory(textDir);
        File textFile = new File(textDir, String.format(Locale.US, "chunk_%09d.txt", seq));
        File temp = new File(textFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write((segment.transcriptText + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush(); out.getFD().sync();
        }
        if (textFile.exists() && !textFile.delete()) throw new IOException("Could not replace transcript chunk");
        if (!temp.renameTo(textFile)) throw new IOException("Could not publish transcript chunk");
        fsyncDirectory(textDir);
        save(manifest);
        rebuildTranscript(sessionId);
    }

    public synchronized String readTranscript(String sessionId) throws IOException {
        File file = new File(sessionDir(sessionId), "transcript.txt");
        return file.isFile() ? readText(file) : "";
    }

    public synchronized void markRemoteCommitted(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.remoteCommitted = true;
        manifest.state = manifest.conversionFinished ? "COMPLETE" : manifest.state;
        manifest.error = "";
        save(manifest);
    }

    public synchronized void markError(String sessionId, String error) {
        try {
            ReliableSessionManifest manifest = load(sessionId);
            if (error != null && error.equals(manifest.error)) return;
            manifest.error = error == null ? "" : error;
            manifest.state = "ERROR";
            save(manifest);
        } catch (Exception ignored) {}
    }

    public synchronized File wavFile(String sessionId, ReliableSessionManifest.Segment segment) throws IOException {
        return new File(sessionDir(sessionId), segment.wavName);
    }

    public synchronized File mp3File(String sessionId, ReliableSessionManifest.Segment segment) throws IOException {
        return new File(sessionDir(sessionId), segment.mp3Name);
    }

    public synchronized boolean trustLocalMp3SegmentFile(String sessionId, int seq) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
        if (segment == null) return false;
        File dir = sessionDir(sessionId);
        File mp3 = segment.mp3Name == null || segment.mp3Name.isEmpty()
                ? null : new File(dir, segment.mp3Name);
        if (mp3 == null || !mp3.isFile() || mp3.length() <= 0L) {
            File finalMp3 = finalMp3File(sessionId);
            if (manifest.segments.size() == 1
                    && finalMp3.isFile() && finalMp3.length() > 0L) {
                mp3 = finalMp3;
            }
        }
        if (mp3 == null || !mp3.isFile() || mp3.length() <= 0L) return false;
        long localBytes = mp3.length();
        String localSha = sha256File(mp3);
        boolean changed = false;
        if (!mp3.getName().equals(segment.mp3Name)) {
            segment.mp3Name = mp3.getName();
            changed = true;
        }
        if (segment.mp3Bytes != localBytes || !localSha.equals(segment.sha256)) {
            segment.mp3Bytes = localBytes;
            segment.sha256 = localSha;
            segment.remoteAccepted = false;
            segment.remotePartialBytes = 0L;
            segment.remoteServerId = "";
            segment.remoteManifestRevision = 0L;
            segment.remoteReceivedAtMs = 0L;
            segment.remoteDurableAtMs = 0L;
            segment.lastSendError = "";
            segment.transcriptState = "PENDING";
            segment.transcriptText = "";
            segment.transcriptEngine = "";
            segment.transcriptCreatedAtMs = 0L;
            segment.transcriptError = "";
            manifest.remoteCommitted = false;
            manifest.remoteManifestRevision = 0L;
            manifest.error = "";
            if (manifest.recordingFinished && manifest.conversionFinished) {
                manifest.state = "READY";
            }
            recalculate(manifest);
            changed = true;
        }
        if (changed) save(manifest);
        return true;
    }

    public synchronized File finalMp3File(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        String name = RecordingFileNames.isLegacyGenericName(manifest.finalMp3Name)
                ? RecordingFileNames.mp3Name(manifest.createdAt,
                        manifest.sessionId, manifest.displayName)
                : manifest.finalMp3Name;
        return new File(sessionDir(sessionId), name);
    }

    public synchronized Folder renameFolder(String folderId, String requestedName) throws IOException {
        Folder existing = getFolder(folderId);
        String name = cleanDisplayName(requestedName, "Folder name");
        JSONObject index = readFolderIndex();
        JSONArray array = index.optJSONArray("folders");
        boolean found = false;
        try {
            if (array != null) for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && folderId.equals(item.optString("folder_id"))) {
                    String previousName = item.optString("name", "Default");
                    if (!item.has("remote_name")) item.put("remote_name", previousName);
                    item.put("name", name);
                    item.put("updated_at_ms", System.currentTimeMillis());
                    found = true;
                    break;
                }
            }
            if (!found) throw new IOException("Unknown recording folder");
            index.put("revision", index.optLong("revision", 0L) + 1L);
        } catch (org.json.JSONException failure) {
            throw new IOException("Could not update folder metadata", failure);
        }
        durableJson(folderIndex, index);
        for (ReliableSessionManifest manifest : list()) {
            if (!folderId.equals(manifest.folderId)) continue;
            manifest.folderName = name;
            save(manifest);
        }
        return getFolder(existing.id);
    }

    public synchronized Folder moveFolder(String folderId,
                                          String parentFolderId)
            throws IOException {
        Folder existing = getFolder(folderId);
        String parentId = parentFolderId == null ? "" : parentFolderId;
        if (folderId.equals(parentId)) throw new IOException(
                "A folder cannot be its own parent");
        if (!parentId.isEmpty()) getFolder(parentId);
        String current = parentId;
        while (!current.isEmpty()) {
            if (folderId.equals(current)) throw new IOException(
                    "A folder cannot be moved into its own descendant");
            current = getFolder(current).parentId;
        }
        JSONObject index = readFolderIndex();
        JSONArray array = index.optJSONArray("folders");
        boolean found = false;
        try {
            if (array != null) for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && folderId.equals(
                        item.optString("folder_id"))) {
                    if (!item.has("remote_parent_folder_id")) {
                        item.put("remote_parent_folder_id",
                                item.optString("parent_folder_id", ""));
                    }
                    item.put("parent_folder_id", parentId);
                    item.put("updated_at_ms", System.currentTimeMillis());
                    found = true;
                    break;
                }
            }
            if (!found) throw new IOException("Unknown recording folder");
            index.put("revision", index.optLong("revision", 0L) + 1L);
        } catch (org.json.JSONException failure) {
            throw new IOException("Could not move folder metadata", failure);
        }
        durableJson(folderIndex, index);
        return getFolder(existing.id);
    }

    public synchronized ReliableSessionManifest renameSession(String sessionId,
                                                               String requestedName) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        String name = cleanDisplayName(requestedName, "Recording name");
        File directory = sessionDir(sessionId);
        File existing = finalMp3File(sessionId);
        if (existing.isFile()) {
            String targetName = safeAudioFileName(name);
            File target = new File(directory, targetName);
            if (!existing.equals(target)) {
                if (target.exists()) throw new IOException("A recording with that filename already exists");
                if (!existing.renameTo(target)) throw new IOException("Could not rename the audio file");
                fsyncDirectory(directory);
                manifest.finalMp3Name = targetName;
                manifest.finalMp3Bytes = target.length();
                manifest.finalMp3Sha256 = sha256File(target);
            }
        }
        manifest.displayName = name;
        save(manifest);
        return manifest.copy();
    }

    public synchronized ReliableSessionManifest moveSession(String sessionId,
                                                             String destinationFolderId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        if (!manifest.recordingFinished && !manifest.paused) {
            throw new IOException("Pause or finish the recording before moving it");
        }
        Folder destination = getFolder(destinationFolderId);
        if (destination.id.equals(manifest.folderId)) return manifest.copy();
        String oldFolderId = manifest.folderId;
        File oldDirectory = findSessionDir(sessionId);
        if (oldDirectory == null) throw new IOException("Recording directory is missing");
        File destinationParent = new File(new File(foldersRoot, destination.id), "sessions");
        ensureDirectory(destinationParent);
        File target = new File(destinationParent, sessionId);
        if (target.exists()) throw new IOException("Destination already contains this recording");
        if (!oldDirectory.renameTo(target)) throw new IOException("Could not move the recording directory");
        fsyncDirectory(oldDirectory.getParentFile());
        fsyncDirectory(destinationParent);
        try {
            if (manifest.remoteFolderId == null || manifest.remoteFolderId.isEmpty()) {
                manifest.remoteFolderId = oldFolderId;
            }
            manifest.folderId = destination.id;
            manifest.folderName = destination.name;
            save(manifest);
        } catch (IOException failure) {
            target.renameTo(oldDirectory);
            throw failure;
        }
        return manifest.copy();
    }

    public synchronized void markRemoteFolder(String sessionId, String folderId,
                                              String folderName) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.remoteFolderId = folderId == null || folderId.isEmpty()
                ? manifest.folderId : folderId;
        manifest.remoteFolderName = folderName == null || folderName.isEmpty()
                ? manifest.folderName : folderName;
        save(manifest);
    }

    public synchronized void markRemoteDisplayName(String sessionId,
                                                   String displayName) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        manifest.remoteDisplayName = displayName == null ? "" : displayName;
        save(manifest);
    }

    public synchronized long studioCacheSafeSourceBytes(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        return recordingBytes(manifest);
    }

    private static long recordingBytes(ReliableSessionManifest manifest) {
        if (manifest.finalMp3Bytes > 0L) return manifest.finalMp3Bytes;
        return Math.max(manifest.totalSegmentBytes, manifest.totalPcmBytes);
    }

    private static String cleanDisplayName(String value, String label) throws IOException {
        String name = value == null ? "" : value.trim().replaceAll("\s+", " ");
        if (name.isEmpty() || name.length() > 96 || containsUnsafePathCharacter(name)) {
            throw new IOException(label + " must contain one to ninety-six safe characters");
        }
        return name;
    }

    private static boolean containsUnsafePathCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 32 || character == 127 || character == '/' || character == '\\') {
                return true;
            }
        }
        return false;
    }

    private static String safeAudioFileName(String displayName) {
        StringBuilder out = new StringBuilder(displayName.length() + 4);
        for (int i = 0; i < displayName.length(); i++) {
            char character = displayName.charAt(i);
            out.append(character < 32 || character == 127 || character == '/' || character == '\\'
                    ? '_' : character);
        }
        String base = out.toString().trim();
        if (base.toLowerCase(Locale.US).endsWith(".mp3")) return base;
        return base + ".mp3";
    }

    public synchronized void ensureWritable(long additionalBytes) throws IOException {
        StatFs stat = new StatFs(root.getAbsolutePath());
        if (stat.getAvailableBytes() - additionalBytes < MIN_FREE_BYTES) {
            throw new IOException("Phone storage is too full to protect more audio");
        }
    }

    public synchronized long localBytes() { return directoryBytes(root); }

    public synchronized void deleteAll() throws IOException {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if ("conversation.id".equals(child.getName())) continue;
            deleteRecursively(child);
        }
    }

    public synchronized void recoverAll() throws IOException {
        File[] folders = foldersRoot.listFiles(File::isDirectory);
        if (folders == null) return;
        for (File folder : folders) {
            File sessions = new File(folder, "sessions");
            File[] dirs = sessions.listFiles(File::isDirectory);
            if (dirs == null) continue;
            for (File dir : dirs) recoverDirectory(dir);
        }
    }

    private void recoverDirectory(File dir) throws IOException {
        String sessionId = dir.getName();
        if (!SAFE_ID.matcher(sessionId).matches()) return;
        ReliableSessionManifest manifest;
        File metadata = new File(dir, "manifest.json");
        if (metadata.isFile()) {
            try { manifest = ReliableSessionManifest.fromJson(new JSONObject(readText(metadata))); }
            catch (Exception failure) { manifest = recoveredManifest(sessionId); }
        } else manifest = recoveredManifest(sessionId);
        String folderId = dir.getParentFile().getParentFile().getName();
        try {
            Folder folder = getFolder(folderId);
            manifest.folderId = folder.id;
            manifest.folderName = folder.name;
        } catch (Exception ignored) {
            manifest.folderId = "default";
            manifest.folderName = "Default";
        }

        String expectedFinalName = RecordingFileNames.mp3Name(
                manifest.createdAt, manifest.sessionId, manifest.displayName);
        File legacyFinal = new File(dir, "recording.mp3");
        File expectedFinal = new File(dir, expectedFinalName);
        if (legacyFinal.isFile() && !legacyFinal.equals(expectedFinal)
                && !expectedFinal.exists()) {
            if (!legacyFinal.renameTo(expectedFinal)) {
                throw new IOException("Could not migrate the legacy recording filename");
            }
            fsyncDirectory(dir);
        }
        if (RecordingFileNames.isLegacyGenericName(manifest.finalMp3Name)) {
            manifest.finalMp3Name = expectedFinalName;
        }

        boolean recoveredOpenSegment = false;
        java.util.Set<Integer> pcmSequences = new java.util.HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            Matcher pcm = PCM_PATTERN.matcher(file.getName());
            if (!pcm.matches()) continue;
            if (isOpenPcmJournalName(file.getName())) {
                recoveredOpenSegment = true;
            }
            int seq = Integer.parseInt(pcm.group(1));
            int inputRate = Integer.parseInt(pcm.group(2));
            pcmSequences.add(seq);
            ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
            if (segment == null) { segment = new ReliableSessionManifest.Segment(); segment.seq = seq; manifest.segments.add(segment); }
            segment.pcmJournalName = file.getName();
            segment.pcmInputSampleRate = inputRate;
            segment.pcmBytes = file.length();
            long inputSamples = file.length() / 2L;
            long outputSamples = inputSamples * ReliableSessionManifest.OUTPUT_SAMPLE_RATE / Math.max(1, inputRate);
            segment.durationMs = outputSamples * 1000L / ReliableSessionManifest.OUTPUT_SAMPLE_RATE;
            manifest.nextSeq = Math.max(manifest.nextSeq, seq + 1);
        }
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.contains(".tmp")) { file.delete(); continue; }
            Matcher wav = WAV_PATTERN.matcher(name);
            if (wav.matches()) {
                int seq = Integer.parseInt(wav.group(1));
                if (name.contains(".open.")) {
                    recoveredOpenSegment = true;
                    repairWav(file);
                    File closed = new File(dir, String.format(Locale.US, "segment_%06d.wav", seq));
                    if (closed.exists()) closed.delete();
                    if (!file.renameTo(closed)) throw new IOException("Could not recover interrupted WAV segment");
                    file = closed;
                } else repairWav(file);
                ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
                if (segment == null) { segment = new ReliableSessionManifest.Segment(); segment.seq = seq; manifest.segments.add(segment); }
                segment.wavName = file.getName();
                segment.pcmBytes = Math.max(0L, file.length() - 44L);
                segment.durationMs = segment.pcmBytes * 1000L / 32000L;
                manifest.nextSeq = Math.max(manifest.nextSeq, seq + 1);
                continue;
            }
            Matcher openMp3 = OPEN_MP3_PATTERN.matcher(name);
            if (openMp3.matches()) {
                recoveredOpenSegment = true;
                int seq = Integer.parseInt(openMp3.group(1));
                if (pcmSequences.contains(seq)) {
                    file.delete();
                    continue;
                }
                try {
                    Mp3Frames.normalizeInPlace(file);
                    File closed = new File(dir, String.format(Locale.US, "segment_%06d.mp3", seq));
                    if (closed.exists()) closed.delete();
                    if (!file.renameTo(closed)) throw new IOException("Could not recover interrupted MP3 segment");
                    file = closed;
                    name = file.getName();
                } catch (IOException noFrames) {
                    file.delete();
                    continue;
                }
            }
            Matcher mp3 = MP3_PATTERN.matcher(name);
            if (mp3.matches()) {
                int seq = Integer.parseInt(mp3.group(1));
                Mp3Frames.Stats stats = Mp3Frames.normalizeInPlace(file);
                ReliableSessionManifest.Segment segment = manifest.findSegment(seq);
                if (segment == null) { segment = new ReliableSessionManifest.Segment(); segment.seq = seq; manifest.segments.add(segment); }
                segment.mp3Name = file.getName();
                segment.mp3Bytes = file.length();
                segment.sha256 = sha256File(file);
                segment.durationMs = stats.durationMs;
                manifest.nextSeq = Math.max(manifest.nextSeq, seq + 1);
            }
        }
        File finalMp3 = new File(dir, "recording.mp3");
        if (recoveredOpenSegment && !manifest.recordingFinished && finalMp3.exists()) {
            finalMp3.delete();
            manifest.finalMp3Name = "";
            manifest.finalMp3Bytes = 0L;
            manifest.finalMp3Sha256 = "";
        } else if (finalMp3.isFile() && finalMp3.length() > 0L) {
            if (manifest.recordingFinished) manifest.conversionFinished = true;
            manifest.finalMp3Name = finalMp3.getName();
            manifest.finalMp3Bytes = finalMp3.length();
            manifest.finalMp3Sha256 = sha256File(finalMp3);
        }
        if (manifest.isDiscardableEmptySession()) {
            deleteRecursively(dir);
            return;
        }
        if (!manifest.recordingFinished) {
            if (manifest.paused && !recoveredOpenSegment) manifest.state = "PAUSED";
            else {
                manifest.paused = false;
                manifest.state = "INTERRUPTED";
            }
        }
        else if (!manifest.conversionFinished) manifest.state = "FINALIZING";
        else if (manifest.remoteCommitted) manifest.state = "COMPLETE";
        else manifest.state = "READY";
        recalculate(manifest);
        save(manifest);
    }

    private ReliableSessionManifest recoveredManifest(String sessionId) {
        ReliableSessionManifest value = new ReliableSessionManifest();
        value.sessionId = sessionId;
        value.conversationId = conversationId;
        value.createdAt = System.currentTimeMillis();
        value.updatedAt = value.createdAt;
        value.state = "INTERRUPTED";
        value.displayName = "Recovered recording " + sessionId.substring(0, Math.min(8, sessionId.length()));
        value.remoteFolderId = "default";
        value.remoteFolderName = "Default";
        return value;
    }

    private void recalculate(ReliableSessionManifest manifest) {
        long pcmBytes = 0L, segmentBytes = 0L, duration = 0L;
        for (ReliableSessionManifest.Segment segment : manifest.segments) {
            pcmBytes += Math.max(0L, segment.pcmBytes);
            segmentBytes += Math.max(0L, segment.mp3Bytes);
            duration += Math.max(0L, segment.durationMs);
        }
        manifest.totalPcmBytes = pcmBytes;
        manifest.totalSegmentBytes = segmentBytes;
        manifest.totalDurationMs = duration;
        long samples = 0L;
        for (ReliableSessionManifest.Segment segment : manifest.segments) samples = Math.max(samples, segment.endSample);
        manifest.totalOutputSamples = samples > 0L ? samples
                : duration * ReliableSessionManifest.OUTPUT_SAMPLE_RATE / 1000L;
    }

    private void save(ReliableSessionManifest manifest) throws IOException {
        manifest.updatedAt = System.currentTimeMillis();
        File file = new File(sessionDir(manifest.folderId, manifest.sessionId), "manifest.json");
        File temp = new File(file.getAbsolutePath() + ".tmp");
        File backup = new File(file.getAbsolutePath() + ".bak");
        byte[] bytes;
        try { bytes = manifest.toJson().toString(2).getBytes(StandardCharsets.UTF_8); }
        catch (Exception failure) { throw new IOException("Could not serialize session metadata", failure); }
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        if (backup.exists() && !backup.delete()) throw new IOException("Could not replace metadata backup");
        boolean had = file.exists();
        if (had && !file.renameTo(backup)) throw new IOException("Could not preserve metadata backup");
        if (!temp.renameTo(file)) {
            if (had) backup.renameTo(file);
            throw new IOException("Could not publish session metadata");
        }
        backup.delete();
        fsyncDirectory(file.getParentFile());
    }

    private File sessionDir(String folderId, String sessionId) throws IOException {
        validateId(folderId); validateId(sessionId);
        File sessions = new File(new File(foldersRoot, folderId), "sessions");
        ensureDirectory(sessions);
        File dir = new File(sessions, sessionId);
        ensureDirectory(dir);
        return dir;
    }

    private File findSessionDir(String sessionId) throws IOException {
        validateId(sessionId);
        File[] folders = foldersRoot.listFiles(File::isDirectory);
        if (folders != null) for (File folder : folders) {
            File candidate = new File(new File(folder, "sessions"), sessionId);
            if (new File(candidate, "manifest.json").isFile()) return candidate;
        }
        return null;
    }

    private File sessionDir(String sessionId) throws IOException {
        File found = findSessionDir(sessionId);
        return found != null ? found : sessionDir("default", sessionId);
    }

    private File manifestFile(String sessionId) throws IOException {
        File found = findSessionDir(sessionId);
        return new File(found != null ? found : sessionDir("default", sessionId), "manifest.json");
    }

    private void ensureDefaultFolder() throws IOException {
        if (!folderIndex.isFile()) {
            JSONObject index = new JSONObject();
            JSONArray folders = new JSONArray();
            JSONObject value = new JSONObject();
            try {
                long now = System.currentTimeMillis();
                value.put("folder_id", "default");
                value.put("name", "Default");
                value.put("parent_folder_id", "");
                value.put("remote_name", "Default");
                value.put("remote_parent_folder_id", "");
                value.put("created_at_ms", now);
                value.put("updated_at_ms", now);
                folders.put(value);
                index.put("schema_version", 2);
                index.put("revision", 1);
                index.put("folders", folders);
            } catch (Exception failure) { throw new IOException(failure); }
            durableJson(folderIndex, index);
        }
        ensureDirectory(new File(new File(foldersRoot, "default"), "sessions"));
    }

    private void repairFolderIndexFromDisk() throws IOException {
        JSONObject index = readFolderIndex();
        JSONArray array = index.optJSONArray("folders");
        if (array == null) {
            array = new JSONArray();
            try { index.put("folders", array); }
            catch (Exception failure) { throw new IOException(failure); }
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        boolean metadataChanged = index.optInt("schema_version", 1) < 2;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            known.add(item.optString("folder_id", ""));
            try {
                if (!item.has("parent_folder_id")) {
                    item.put("parent_folder_id", "");
                    metadataChanged = true;
                }
                if (!item.has("remote_parent_folder_id")) {
                    item.put("remote_parent_folder_id",
                            item.optString("parent_folder_id", ""));
                    metadataChanged = true;
                }
            } catch (Exception ignored) {}
        }
        try { index.put("schema_version", 2); }
        catch (Exception ignored) {}
        boolean changed = metadataChanged;
        for (Folder discovered : discoverFoldersFromDisk(foldersRoot)) {
            if (known.contains(discovered.id)) continue;
            JSONObject value = new JSONObject();
            try {
                value.put("folder_id", discovered.id);
                value.put("name", discovered.name);
                value.put("parent_folder_id", "");
                value.put("remote_name", discovered.name);
                value.put("remote_parent_folder_id", "");
                value.put("created_at_ms", discovered.createdAtMs);
                value.put("updated_at_ms", System.currentTimeMillis());
                array.put(value);
                known.add(discovered.id);
                ensureDirectory(new File(new File(foldersRoot,
                        discovered.id), "sessions"));
                changed = true;
            } catch (Exception failure) {
                throw new IOException("Could not rebuild folder metadata", failure);
            }
        }
        if (changed) {
            try { index.put("revision", index.optLong("revision", 0L) + 1L); }
            catch (Exception failure) { throw new IOException(failure); }
            durableJson(folderIndex, index);
            fsyncDirectory(foldersRoot);
        }
    }

    static List<Folder> discoverFoldersFromDisk(File foldersRoot) {
        Map<String, Folder> discovered = new LinkedHashMap<>();
        File[] physicalFolders = foldersRoot == null ? null
                : foldersRoot.listFiles(File::isDirectory);
        if (physicalFolders == null) return new ArrayList<>();
        for (File physicalFolder : physicalFolders) {
            String physicalId = physicalFolder.getName();
            if (!SAFE_ID.matcher(physicalId).matches()) continue;
            String physicalName = "default".equals(physicalId)
                    ? "Default" : physicalId;
            long physicalCreated = physicalFolder.lastModified();
            discovered.putIfAbsent(physicalId,
                    new Folder(physicalId, physicalName, physicalCreated));
            File sessions = new File(physicalFolder, "sessions");
            File[] sessionDirs = sessions.listFiles(File::isDirectory);
            if (sessionDirs == null) continue;
            for (File sessionDir : sessionDirs) {
                File metadata = new File(sessionDir, "manifest.json");
                if (!metadata.isFile()) continue;
                try {
                    ReliableSessionManifest manifest =
                            ReliableSessionManifest.fromJson(
                                    new JSONObject(readText(metadata)));
                    String manifestId = manifest.folderId == null
                            ? "" : manifest.folderId.trim();
                    if (!SAFE_ID.matcher(manifestId).matches()) continue;
                    String manifestName = manifest.folderName == null
                            || manifest.folderName.trim().isEmpty()
                            ? ("default".equals(manifestId)
                            ? "Default" : manifestId)
                            : manifest.folderName.trim();
                    long createdAt = manifest.createdAt > 0L
                            ? manifest.createdAt : physicalCreated;
                    Folder existing = discovered.get(manifestId);
                    if (existing == null || existing.name.equals(existing.id)
                            || ("default".equals(existing.id)
                            && "Default".equals(existing.name))) {
                        discovered.put(manifestId, new Folder(manifestId,
                                manifestName, createdAt));
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(discovered.values());
    }

    private JSONObject readFolderIndex() throws IOException {
        try { return new JSONObject(readText(folderIndex)); }
        catch (Exception failure) { throw new IOException("Could not parse folder metadata", failure); }
    }

    private void migrateLegacySessions() throws IOException {
        File defaultSessions = new File(new File(foldersRoot, "default"), "sessions");
        ensureDirectory(defaultSessions);
        File[] children = root.listFiles(File::isDirectory);
        if (children == null) return;
        for (File child : children) {
            if ("folders".equals(child.getName()) || !SAFE_ID.matcher(child.getName()).matches()) continue;
            File manifest = new File(child, "manifest.json");
            if (!manifest.isFile()) continue;
            File target = new File(defaultSessions, child.getName());
            if (!target.exists() && !child.renameTo(target)) throw new IOException("Could not migrate recording into Default folder");
        }
        fsyncDirectory(defaultSessions);
        fsyncDirectory(root);
    }

    private static void durableJson(File target, JSONObject value) throws IOException {
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush(); out.getFD().sync();
        } catch (Exception failure) { throw new IOException("Could not write durable JSON", failure); }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace JSON file");
        if (!temp.renameTo(target)) throw new IOException("Could not publish JSON file");
        fsyncDirectory(target.getParentFile());
    }

    private synchronized void rebuildTranscript(String sessionId) throws IOException {
        rebuildTranscript(load(sessionId));
    }

    private void rebuildTranscript(ReliableSessionManifest manifest) throws IOException {
        File target = new File(sessionDir(manifest.sessionId), "transcript.txt");
        File temp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            for (ReliableSessionManifest.Segment segment : manifest.orderedSegments()) {
                if (!"COMPLETE".equals(segment.transcriptState)) continue;
                String line = segment.transcriptText == null ? "" : segment.transcriptText.trim();
                if (!line.isEmpty()) out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            out.flush(); out.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace transcript");
        if (!temp.renameTo(target)) throw new IOException("Could not publish transcript");
        fsyncDirectory(target.getParentFile());
    }

    private static void fsyncDirectory(File directory) {
        if (directory == null || !directory.isDirectory()) return;
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {}
    }

    private String loadOrCreateConversationId() throws IOException {
        File file = new File(root, "conversation.id");
        if (file.isFile()) {
            String value = readText(file).trim();
            if (SAFE_ID.matcher(value).matches()) return value;
        }
        String value = "conversation-" + UUID.randomUUID();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII)); out.flush(); out.getFD().sync();
        }
        fsyncDirectory(root);
        return value;
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static void writeWavHeader(RandomAccessFile out, long pcmBytes) throws IOException {
        out.seek(0L); out.writeBytes("RIFF"); writeLe32(out, 36L + pcmBytes); out.writeBytes("WAVE");
        out.writeBytes("fmt "); writeLe32(out, 16L); writeLe16(out, 1); writeLe16(out, 1);
        writeLe32(out, 16000L); writeLe32(out, 32000L); writeLe16(out, 2); writeLe16(out, 16);
        out.writeBytes("data"); writeLe32(out, pcmBytes);
    }

    public static void repairWav(File file) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            long pcm = Math.max(0L, out.length() - 44L);
            if ((pcm & 1L) != 0L) { out.setLength(out.length() - 1L); pcm--; }
            writeWavHeader(out, pcm); out.getFD().sync();
        }
    }

    public static String sha256File(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024]; int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format(Locale.US, "%02x", value & 0xff));
            return out.toString();
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Could not hash file", failure); }
    }

    private void deleteWavSources(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        for (ReliableSessionManifest.Segment segment : manifest.segments) {
            if (!segment.wavName.isEmpty()) new File(sessionDir(sessionId), segment.wavName).delete();
            segment.wavName = "";
        }
        save(manifest);
    }

    private void deleteTransferSegments(String sessionId) throws IOException {
        ReliableSessionManifest manifest = load(sessionId);
        for (ReliableSessionManifest.Segment segment : manifest.segments) {
            if (!segment.mp3Name.isEmpty()) new File(sessionDir(sessionId), segment.mp3Name).delete();
        }
    }

    private static void validateId(String value) throws IOException {
        if (value == null || !SAFE_ID.matcher(value).matches()) throw new IOException("Invalid session id");
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Could not create audio directory");
    }

    private static long directoryBytes(File file) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return Math.max(0L, file.length());
        long total = 0L; File[] children = file.listFiles();
        if (children != null) for (File child : children) total += directoryBytes(child);
        return total;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Could not delete " + file.getName());
    }

    private static void writeLe16(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xff); out.write((value >>> 8) & 0xff);
    }

    private static void writeLe32(RandomAccessFile out, long value) throws IOException {
        out.write((int)(value & 0xff)); out.write((int)((value >>> 8) & 0xff));
        out.write((int)((value >>> 16) & 0xff)); out.write((int)((value >>> 24) & 0xff));
    }
}
