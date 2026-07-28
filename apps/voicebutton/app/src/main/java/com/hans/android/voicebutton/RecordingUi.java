package com.hans.android.voicebutton;

import android.content.Context;

import com.hans.android.audio.reliable.ReliableSessionManifest;
import com.hans.android.common_ui.AndroidUi;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class RecordingUi {
    private RecordingUi() {}

    static String formatDuration(long millis) {
        long total = Math.max(0L, millis / 1000L);
        return String.format(Locale.US, "%02d:%02d:%02d",
                total / 3600L, (total / 60L) % 60L, total % 60L);
    }

    static String formatPlayerTime(int position, int duration) {
        return formatDuration(position) + " / " + formatDuration(duration);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static String date(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    static long recordingBytes(ReliableSessionManifest manifest) {
        if (manifest == null) return 0L;
        if (manifest.finalMp3Bytes > 0L) return manifest.finalMp3Bytes;
        if (manifest.totalSegmentBytes > 0L) return manifest.totalSegmentBytes;
        return manifest.totalPcmBytes;
    }

    static File recordingFile(Context context, ReliableSessionManifest manifest) {
        if (manifest == null) return null;
        String name = manifest.finalMp3Name == null || manifest.finalMp3Name.isEmpty()
                ? "recording.mp3" : manifest.finalMp3Name;
        File root = new File(context.getNoBackupFilesDir(), "reliable_audio_sessions");
        File folder = new File(new File(root, "folders"),
                manifest.folderId == null || manifest.folderId.isEmpty() ? "default" : manifest.folderId);
        File session = new File(new File(folder, "sessions"), manifest.sessionId);
        return new File(session, name);
    }

    static File transcriptFile(Context context, ReliableSessionManifest manifest) {
        if (manifest == null) return null;
        File root = new File(context.getNoBackupFilesDir(), "reliable_audio_sessions");
        File folder = new File(new File(root, "folders"),
                manifest.folderId == null || manifest.folderId.isEmpty() ? "default" : manifest.folderId);
        return new File(new File(new File(folder, "sessions"), manifest.sessionId), "transcript.txt");
    }

    static String transcriptText(Context context, ReliableSessionManifest manifest) {
        File file = transcriptFile(context, manifest);
        if (file == null || !file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) { return ""; }
    }

    static String humanState(ReliableSessionManifest manifest) {
        if (manifest == null) return "Unknown";
        if (!manifest.recordingFinished) {
            if (manifest.paused) return "Paused";
            if ("RECORDING".equals(manifest.state)) return "Current recording";
            return "Recovery required";
        }
        if (!manifest.conversionFinished) return "Preparing final MP3";
        return manifest.remoteCommitted ? "Complete on phone and server" : "Complete on phone; server pending";
    }

    static int stateColor(String state) {
        if (state == null) return AndroidUi.BLUE;
        if (state.contains("FAILED")) return AndroidUi.RED;
        if (state.contains("RECOVERY") || state.contains("WAIT")) return AndroidUi.ORANGE;
        if (state.contains("RECORDING") || state.contains("PAUSING")) return AndroidUi.RED;
        if (state.contains("PAUSED")) return AndroidUi.ORANGE;
        if (state.contains("UPLOADING") || state.contains("FINISH") || state.contains("RECONCIL")) return AndroidUi.BLUE;
        return AndroidUi.GREEN;
    }
}
