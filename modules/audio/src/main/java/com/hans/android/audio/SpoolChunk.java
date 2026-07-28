package com.hans.android.audio;

import java.io.File;

public final class SpoolChunk {
    private final File file;
    private final String sessionId;
    private final int sequence;
    private final boolean finalChunk;
    private final String conversationId;

    public SpoolChunk(File file, String sessionId, int sequence, boolean finalChunk,
                      String conversationId) {
        this.file = file;
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.finalChunk = finalChunk;
        this.conversationId = conversationId;
    }

    public File getFile() { return file; }
    public String getSessionId() { return sessionId; }
    public int getSequence() { return sequence; }
    public boolean isFinalChunk() { return finalChunk; }
    public String getConversationId() { return conversationId; }
}
