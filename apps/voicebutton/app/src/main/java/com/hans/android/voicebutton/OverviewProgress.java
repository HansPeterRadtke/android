package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.RecordingFileNames;
import com.hans.android.audio.reliable.ReliableSessionManifest;

import java.util.List;

final class OverviewProgress {
    private OverviewProgress() {}

    static boolean needsUpload(ReliableSessionManifest session) {
        if (session == null || session.isDiscardableEmptySession()) return false;
        if (session.remoteCommitted) return false;
        if (!session.recordingFinished || !session.conversionFinished) return true;
        for (ReliableSessionManifest.Segment segment : session.segments) {
            if (!segment.remoteAccepted) return true;
        }
        return !session.remoteCommitted;
    }

    static int uploadFilesRemaining(List<ReliableSessionManifest> sessions) {
        if (sessions == null) return 0;
        int count = 0;
        for (ReliableSessionManifest session : sessions) if (needsUpload(session)) count++;
        return count;
    }

    static ReliableSessionManifest findSession(List<ReliableSessionManifest> sessions,
                                                String sessionId) {
        if (sessions == null || sessionId == null || sessionId.isEmpty()) return null;
        for (ReliableSessionManifest session : sessions) {
            if (session != null && sessionId.equals(session.sessionId)) return session;
        }
        return null;
    }

    static String fileName(ReliableSessionManifest session) {
        if (session == null) return "";
        String finalName = session.finalMp3Name == null ? "" : session.finalMp3Name.trim();
        if (!finalName.isEmpty()) return finalName;
        return RecordingFileNames.visibleMp3Name(session.createdAt, session.displayName);
    }

    static int fileProgressPermille(ReliableSessionManifest session) {
        if (session == null) return -1;
        long total = 0L;
        long durable = 0L;
        boolean unmeasured = false;
        for (ReliableSessionManifest.Segment segment : session.orderedSegments()) {
            if (segment.mp3Bytes <= 0L) {
                if (!segment.remoteAccepted) unmeasured = true;
                continue;
            }
            total += segment.mp3Bytes;
            durable += segment.remoteAccepted ? segment.mp3Bytes
                    : Math.max(0L, Math.min(segment.mp3Bytes, segment.remotePartialBytes));
        }
        if (unmeasured || total <= 0L) return -1;
        return (int)Math.max(0L, Math.min(1000L, durable * 1000L / total));
    }

    static boolean hasUnmeasuredUpload(List<ReliableSessionManifest> sessions) {
        if (sessions == null) return false;
        for (ReliableSessionManifest session : sessions) {
            if (!needsUpload(session)) continue;
            if (!session.recordingFinished || !session.conversionFinished) return true;
            for (ReliableSessionManifest.Segment segment : session.segments) {
                if (!segment.remoteAccepted && segment.mp3Bytes <= 0L) return true;
            }
        }
        return false;
    }
}
