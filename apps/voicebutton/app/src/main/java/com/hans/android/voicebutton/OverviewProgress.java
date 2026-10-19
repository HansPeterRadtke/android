package com.hans.android.voicebutton;

import com.hans.android.audio.reliable.RecordingFileNames;
import com.hans.android.audio.reliable.ReliableSessionManifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class OverviewProgress {
    private OverviewProgress() {}

    static boolean needsUpload(ReliableSessionManifest session) {
        if (session == null || session.isDiscardableEmptySession()) return false;
        if ("UNREADABLE_METADATA".equals(session.state)) return false;
        if (session.remoteCommitted || !session.recordingFinished) return false;
        if (!session.conversionFinished) return true;
        for (ReliableSessionManifest.Segment segment : session.segments) {
            if (!segment.remoteAccepted) return true;
        }
        return !session.remoteCommitted;
    }


    static List<ReliableSessionManifest> pendingUploads(
            List<ReliableSessionManifest> sessions, Set<String> serverCommittedIds) {
        ArrayList<ReliableSessionManifest> result = new ArrayList<>();
        if (sessions == null) return result;
        for (ReliableSessionManifest session : sessions) {
            if (session == null || !session.recordingFinished) continue;
            if (serverCommittedIds != null && serverCommittedIds.contains(session.sessionId)) continue;
            if (needsUpload(session)) result.add(session);
        }
        result.sort(Comparator.comparingLong(value -> value.createdAt));
        return result;
    }

    static boolean allSegmentsAccepted(ReliableSessionManifest session) {
        if (session == null || session.segments.isEmpty()) return false;
        for (ReliableSessionManifest.Segment segment : session.segments) {
            if (!segment.remoteAccepted) return false;
        }
        return true;
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

    /** Only locally encoded MP3 bytes are measurable.  A phone recording
     * changes the denominator as each new chunk closes; no future bytes are
     * guessed.  ACKs include the server-confirmed partial offset, never bytes
     * merely written to a socket.  Commit confirmation is tracked separately.
     */
    static final class UploadEvidence {
        final long totalBytes;
        final long durableBytes;
        final int totalChunks;
        final int durableChunks;
        final int finishedPending;
        final int uncommittedSessions;
        final boolean hasSessions;
        final boolean hasOpenRecording;
        final boolean unfinishedEncoding;

        UploadEvidence(long totalBytes, long durableBytes, int totalChunks,
                       int durableChunks, int finishedPending,
                       int uncommittedSessions, boolean hasSessions,
                       boolean hasOpenRecording, boolean unfinishedEncoding) {
            this.totalBytes = totalBytes;
            this.durableBytes = durableBytes;
            this.totalChunks = totalChunks;
            this.durableChunks = durableChunks;
            this.finishedPending = finishedPending;
            this.uncommittedSessions = uncommittedSessions;
            this.hasSessions = hasSessions;
            this.hasOpenRecording = hasOpenRecording;
            this.unfinishedEncoding = unfinishedEncoding;
        }

        int progressPermille() {
            return RecordingFeedback.uploadPermille(durableBytes, totalBytes);
        }
    }

    static UploadEvidence uploadEvidence(List<ReliableSessionManifest> sessions,
            String liveOperation, String liveSessionId, int liveSequence,
            long liveDurableBytes, long liveTotalBytes) {
        long total = 0L, confirmed = 0L;
        int chunks = 0, confirmedChunks = 0, finishedPending = 0, uncommitted = 0;
        boolean present = false, open = false, unmeasured = false;
        if (sessions != null) for (ReliableSessionManifest session : sessions) {
            if (session == null || session.isDiscardableEmptySession()
                    || "UNREADABLE_METADATA".equals(session.state)) continue;
            present = true;
            if (!session.recordingFinished) open = true;
            if (!session.remoteCommitted) {
                uncommitted++;
                if (needsUpload(session)) finishedPending++;
                if (session.recordingFinished && !session.conversionFinished) {
                    unmeasured = true;
                }
            }
            for (ReliableSessionManifest.Segment segment : session.segments) {
                if (segment.mp3Bytes <= 0L) {
                    if (session.recordingFinished && !session.remoteCommitted
                            && !segment.remoteAccepted) unmeasured = true;
                    continue;
                }
                chunks++;
                total += segment.mp3Bytes;
                if (session.remoteCommitted || segment.remoteAccepted) {
                    confirmed += segment.mp3Bytes;
                    confirmedChunks++;
                    continue;
                }
                long durable = Math.max(0L,
                        Math.min(segment.mp3Bytes, segment.remotePartialBytes));
                if (matchesLiveChunk(session, segment, liveOperation, liveSessionId,
                        liveSequence, liveTotalBytes)) {
                    durable = Math.max(durable, Math.max(0L,
                            Math.min(segment.mp3Bytes, liveDurableBytes)));
                }
                confirmed += durable;
            }
        }
        return new UploadEvidence(total, confirmed, chunks, confirmedChunks,
                finishedPending, uncommitted, present, open, unmeasured);
    }

    private static boolean matchesLiveChunk(ReliableSessionManifest session,
            ReliableSessionManifest.Segment segment, String operation,
            String liveSessionId, int liveSequence, long liveTotalBytes) {
        return ("upload_chunk".equals(operation) || "upload_part".equals(operation)
                || "chunk_complete".equals(operation))
                && session.sessionId != null && session.sessionId.equals(liveSessionId)
                && segment.seq == liveSequence && segment.mp3Bytes == liveTotalBytes;
    }

    static int fileProgressPermille(ReliableSessionManifest session,
            String operation, String liveSessionId, int liveSequence,
            long liveDurableBytes, long liveTotalBytes) {
        if (session == null) return -1;
        long total = 0L, confirmed = 0L;
        for (ReliableSessionManifest.Segment segment : session.orderedSegments()) {
            if (segment.mp3Bytes <= 0L) {
                if (session.recordingFinished && !segment.remoteAccepted) return -1;
                continue;
            }
            total += segment.mp3Bytes;
            if (session.remoteCommitted || segment.remoteAccepted) {
                confirmed += segment.mp3Bytes;
                continue;
            }
            long durable = Math.max(0L,
                    Math.min(segment.mp3Bytes, segment.remotePartialBytes));
            if (matchesLiveChunk(session, segment, operation, liveSessionId,
                    liveSequence, liveTotalBytes)) {
                durable = Math.max(durable, Math.max(0L,
                        Math.min(segment.mp3Bytes, liveDurableBytes)));
            }
            confirmed += durable;
        }
        return total <= 0L ? -1 : RecordingFeedback.uploadPermille(confirmed, total);
    }

    static ReliableSessionManifest visibleUpload(List<ReliableSessionManifest> sessions,
                                                 String liveSessionId) {
        List<ReliableSessionManifest> pending = pendingUploads(sessions, null);
        for (ReliableSessionManifest item : pending) {
            if (item.sessionId != null && item.sessionId.equals(liveSessionId)) return item;
        }
        if (!pending.isEmpty()) return pending.get(0);
        ReliableSessionManifest active = findSession(sessions, liveSessionId);
        return active != null && !active.remoteCommitted ? active : null;
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
