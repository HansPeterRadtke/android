package com.hans.android.audio.reliable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ReliableSessionManifest {
    public static final int SCHEMA_VERSION = 2;
    public static final int OUTPUT_SAMPLE_RATE = 48000;

    public static final class Segment {
        public int seq;
        public String wavName = "";
        public String pcmJournalName = "";
        public int pcmInputSampleRate = OUTPUT_SAMPLE_RATE;
        public String mp3Name = "";
        public long pcmBytes;
        public long durationMs;
        public long mp3Bytes;
        public String sha256 = "";
        public long startSample;
        public long endSample;
        public int sampleRate = OUTPUT_SAMPLE_RATE;
        public long createdAtMs;
        public long closedAtMs;
        public long localDurableAtMs;
        public boolean remoteAccepted;
        public long remotePartialBytes;
        public int sendAttempts;
        public long firstSendAtMs;
        public long lastSendAtMs;
        public String lastSendError = "";
        public String remoteServerId = "";
        public long remoteManifestRevision;
        public long remoteReceivedAtMs;
        public long remoteDurableAtMs;
        public String transcriptState = "PENDING";
        public String transcriptText = "";
        public String transcriptEngine = "";
        public long transcriptCreatedAtMs;
        public String transcriptError = "";

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("seq", seq);
            value.put("wav_name", wavName);
            value.put("pcm_journal_name", pcmJournalName);
            value.put("pcm_input_sample_rate", pcmInputSampleRate);
            value.put("mp3_name", mp3Name);
            value.put("pcm_bytes", pcmBytes);
            value.put("duration_ms", durationMs);
            value.put("mp3_bytes", mp3Bytes);
            value.put("sha256", sha256);
            value.put("start_sample", startSample);
            value.put("end_sample", endSample);
            value.put("sample_rate", sampleRate);
            value.put("created_at_ms", createdAtMs);
            value.put("closed_at_ms", closedAtMs);
            value.put("local_durable_at_ms", localDurableAtMs);
            value.put("remote_accepted", remoteAccepted);
            value.put("remote_partial_bytes", remotePartialBytes);
            value.put("send_attempts", sendAttempts);
            value.put("first_send_at_ms", firstSendAtMs);
            value.put("last_send_at_ms", lastSendAtMs);
            value.put("last_send_error", lastSendError);
            value.put("remote_server_id", remoteServerId);
            value.put("remote_manifest_revision", remoteManifestRevision);
            value.put("remote_received_at_ms", remoteReceivedAtMs);
            value.put("remote_durable_at_ms", remoteDurableAtMs);
            value.put("transcript_state", transcriptState);
            value.put("transcript_text", transcriptText);
            value.put("transcript_engine", transcriptEngine);
            value.put("transcript_created_at_ms", transcriptCreatedAtMs);
            value.put("transcript_error", transcriptError);
            return value;
        }

        static Segment fromJson(JSONObject value) {
            Segment segment = new Segment();
            segment.seq = value.optInt("seq", -1);
            segment.wavName = value.optString("wav_name", "");
            segment.pcmJournalName = value.optString("pcm_journal_name", "");
            segment.pcmInputSampleRate = value.optInt("pcm_input_sample_rate", OUTPUT_SAMPLE_RATE);
            segment.mp3Name = value.optString("mp3_name", "");
            segment.pcmBytes = value.optLong("pcm_bytes", 0L);
            segment.durationMs = value.optLong("duration_ms", 0L);
            segment.mp3Bytes = value.optLong("mp3_bytes", 0L);
            segment.sha256 = value.optString("sha256", "");
            segment.startSample = value.optLong("start_sample", 0L);
            segment.endSample = value.optLong("end_sample",
                    segment.startSample + segment.durationMs * OUTPUT_SAMPLE_RATE / 1000L);
            segment.sampleRate = value.optInt("sample_rate", OUTPUT_SAMPLE_RATE);
            segment.createdAtMs = value.optLong("created_at_ms", 0L);
            segment.closedAtMs = value.optLong("closed_at_ms", 0L);
            segment.localDurableAtMs = value.optLong("local_durable_at_ms", 0L);
            segment.remoteAccepted = value.optBoolean("remote_accepted", false);
            segment.remotePartialBytes = value.optLong("remote_partial_bytes",
                    segment.remoteAccepted ? segment.mp3Bytes : 0L);
            segment.sendAttempts = value.optInt("send_attempts", 0);
            segment.firstSendAtMs = value.optLong("first_send_at_ms", 0L);
            segment.lastSendAtMs = value.optLong("last_send_at_ms", 0L);
            segment.lastSendError = value.optString("last_send_error", "");
            segment.remoteServerId = value.optString("remote_server_id", "");
            segment.remoteManifestRevision = value.optLong("remote_manifest_revision", 0L);
            segment.remoteReceivedAtMs = value.optLong("remote_received_at_ms", 0L);
            segment.remoteDurableAtMs = value.optLong("remote_durable_at_ms", 0L);
            segment.transcriptState = value.optString("transcript_state", "PENDING");
            segment.transcriptText = value.optString("transcript_text", "");
            segment.transcriptEngine = value.optString("transcript_engine", "");
            segment.transcriptCreatedAtMs = value.optLong("transcript_created_at_ms", 0L);
            segment.transcriptError = value.optString("transcript_error", "");
            return segment;
        }
    }

    public String sessionId;
    public String conversationId;
    public String folderId = "default";
    public String folderName = "Default";
    public String remoteFolderId = "default";
    public String remoteFolderName = "Default";
    public String displayName = "";
    public String remoteDisplayName = "";
    public long createdAt;
    public long updatedAt;
    public String selectedInput = "Built-in microphone";
    public int selectedDeviceId = -1;
    public String state = "RECORDING";
    public boolean recordingFinished;
    public boolean paused;
    public boolean autoResumeRequested;
    public boolean conversionFinished;
    public boolean remoteCommitted;
    public String finishReason = "normal";
    public int nextSeq;
    public long totalPcmBytes;
    public long totalSegmentBytes;
    public long totalDurationMs;
    public long totalOutputSamples;
    public String finalMp3Name = "";
    public long finalMp3Bytes;
    public String finalMp3Sha256 = "";
    public String remoteServerId = "";
    public long remoteManifestRevision;
    public String error = "";
    public final List<Segment> segments = new ArrayList<>();

    public ReliableSessionManifest copy() {
        try { return fromJson(toJson()); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public Segment findSegment(int seq) {
        for (Segment segment : segments) if (segment.seq == seq) return segment;
        return null;
    }

    public List<Segment> orderedSegments() {
        List<Segment> result = new ArrayList<>(segments);
        result.sort(Comparator.comparingInt(segment -> segment.seq));
        return result;
    }

    public int durableRemoteChunkCount() {
        int count = 0;
        for (Segment segment : segments) if (segment.remoteAccepted) count++;
        return count;
    }

    public int transcriptChunkCount() {
        int count = 0;
        for (Segment segment : segments) if ("COMPLETE".equals(segment.transcriptState)) count++;
        return count;
    }

    public long durableRemoteBytes() {
        long bytes = 0L;
        for (Segment segment : segments) {
            bytes += segment.remoteAccepted ? Math.max(0L, segment.mp3Bytes)
                    : Math.max(0L, Math.min(segment.mp3Bytes, segment.remotePartialBytes));
        }
        return bytes;
    }

    public long pendingRemoteBytes() {
        long bytes = 0L;
        for (Segment segment : segments) {
            long durable = segment.remoteAccepted ? segment.mp3Bytes
                    : Math.max(0L, Math.min(segment.mp3Bytes, segment.remotePartialBytes));
            bytes += Math.max(0L, segment.mp3Bytes - durable);
        }
        return bytes;
    }


    public boolean hasPendingMetadata() {
        String localFolderName = folderName == null ? "" : folderName;
        String remoteName = remoteFolderName == null ? "" : remoteFolderName;
        String localTitle = displayName == null ? "" : displayName;
        String remoteTitle = remoteDisplayName == null ? "" : remoteDisplayName;
        return !folderId.equals(remoteFolderId)
                || !localFolderName.equals(remoteName)
                || !localTitle.equals(remoteTitle);
    }

    public boolean isOpen() { return !recordingFinished; }
    public boolean isDiscardableEmptySession() {
        return !remoteCommitted && segments.isEmpty() && totalDurationMs <= 0L
                && totalSegmentBytes <= 0L && totalPcmBytes <= 0L
                && finalMp3Bytes <= 0L;
    }
    public boolean isInterrupted() {
        return !recordingFinished && !paused && "INTERRUPTED".equals(state);
    }
    public boolean isLocallyReady() { return recordingFinished && conversionFinished; }
    public boolean isDone() { return isLocallyReady() && remoteCommitted; }

    public JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject();
        value.put("schema_version", SCHEMA_VERSION);
        value.put("session_id", sessionId);
        value.put("conversation_id", conversationId);
        value.put("folder_id", folderId);
        value.put("folder_name", folderName);
        value.put("remote_folder_id", remoteFolderId);
        value.put("remote_folder_name", remoteFolderName);
        value.put("display_name", displayName);
        value.put("remote_display_name", remoteDisplayName);
        value.put("created_at", createdAt);
        value.put("updated_at", updatedAt);
        value.put("selected_input", selectedInput);
        value.put("selected_device_id", selectedDeviceId);
        value.put("state", state);
        value.put("recording_finished", recordingFinished);
        value.put("paused", paused);
        value.put("auto_resume_requested", autoResumeRequested);
        value.put("conversion_finished", conversionFinished);
        value.put("remote_committed", remoteCommitted);
        value.put("done", isDone());
        value.put("finish_reason", finishReason);
        value.put("next_seq", nextSeq);
        value.put("total_pcm_bytes", totalPcmBytes);
        value.put("total_segment_bytes", totalSegmentBytes);
        value.put("total_duration_ms", totalDurationMs);
        value.put("total_output_samples", totalOutputSamples);
        value.put("final_mp3_name", finalMp3Name);
        value.put("final_mp3_bytes", finalMp3Bytes);
        value.put("final_mp3_sha256", finalMp3Sha256);
        value.put("remote_server_id", remoteServerId);
        value.put("remote_manifest_revision", remoteManifestRevision);
        value.put("error", error);
        JSONArray array = new JSONArray();
        for (Segment segment : orderedSegments()) array.put(segment.toJson());
        value.put("segments", array);
        return value;
    }

    public static ReliableSessionManifest fromJson(JSONObject value) {
        ReliableSessionManifest manifest = new ReliableSessionManifest();
        manifest.sessionId = value.optString("session_id", "");
        manifest.conversationId = value.optString("conversation_id", manifest.sessionId);
        manifest.folderId = value.optString("folder_id", "default");
        manifest.folderName = value.optString("folder_name", "Default");
        manifest.remoteFolderId = value.optString("remote_folder_id", manifest.folderId);
        manifest.remoteFolderName = value.optString("remote_folder_name", manifest.folderName);
        manifest.displayName = value.optString("display_name", "");
        manifest.remoteDisplayName = value.optString("remote_display_name", "");
        manifest.createdAt = value.optLong("created_at", System.currentTimeMillis());
        manifest.updatedAt = value.optLong("updated_at", manifest.createdAt);
        manifest.selectedInput = value.optString("selected_input", "Built-in microphone");
        manifest.selectedDeviceId = value.optInt("selected_device_id", -1);
        manifest.state = value.optString("state", "INTERRUPTED");
        manifest.recordingFinished = value.optBoolean("recording_finished", false);
        manifest.paused = value.optBoolean("paused", false);
        manifest.autoResumeRequested = value.optBoolean("auto_resume_requested", false);
        manifest.conversionFinished = value.optBoolean("conversion_finished", false);
        manifest.remoteCommitted = value.optBoolean("remote_committed", false);
        manifest.finishReason = value.optString("finish_reason", "normal");
        manifest.nextSeq = value.optInt("next_seq", 0);
        manifest.totalPcmBytes = value.optLong("total_pcm_bytes", 0L);
        manifest.totalSegmentBytes = value.optLong("total_segment_bytes", 0L);
        manifest.totalDurationMs = value.optLong("total_duration_ms", 0L);
        manifest.totalOutputSamples = value.optLong("total_output_samples",
                manifest.totalDurationMs * OUTPUT_SAMPLE_RATE / 1000L);
        manifest.finalMp3Name = value.optString("final_mp3_name", "");
        manifest.finalMp3Bytes = value.optLong("final_mp3_bytes", 0L);
        manifest.finalMp3Sha256 = value.optString("final_mp3_sha256", "");
        manifest.remoteServerId = value.optString("remote_server_id", "");
        manifest.remoteManifestRevision = value.optLong("remote_manifest_revision", 0L);
        manifest.error = value.optString("error", "");
        JSONArray array = value.optJSONArray("segments");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) manifest.segments.add(Segment.fromJson(item));
        }
        return manifest;
    }

    public String canonicalCommitJson() {
        StringBuilder out = new StringBuilder();
        out.append("{\"conversation_id\":")
                .append(JSONObject.quote(conversationId)).append(',');
        out.append("\"display_name\":")
                .append(JSONObject.quote(displayName == null ? "" : displayName)).append(',');
        out.append("\"final_mp3_name\":")
                .append(JSONObject.quote(finalMp3Name == null ? "" : finalMp3Name)).append(',');
        out.append("\"finish_reason\":")
                .append(JSONObject.quote(finishReason)).append(',');
        out.append("\"folder_id\":")
                .append(JSONObject.quote(folderId)).append(',');
        List<Segment> ordered = orderedSegments();
        out.append("\"segment_count\":").append(ordered.size()).append(',');
        out.append("\"segments\":[");
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) out.append(',');
            Segment segment = ordered.get(i);
            out.append("{\"bytes\":").append(segment.mp3Bytes)
                    .append(",\"duration_ms\":").append(segment.durationMs)
                    .append(",\"end_sample\":").append(segment.endSample)
                    .append(",\"seq\":").append(segment.seq)
                    .append(",\"sha256\":\"").append(segment.sha256).append("\"")
                    .append(",\"start_sample\":").append(segment.startSample).append('}');
        }
        out.append("],\"session_id\":")
                .append(JSONObject.quote(sessionId)).append(',');
        out.append("\"total_duration_ms\":").append(totalDurationMs).append('}');
        return out.toString();
    }

    public String commitSha256() {
        return sha256(canonicalCommitJson().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : hash) out.append(String.format(Locale.US, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
