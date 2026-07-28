package com.hans.android.audio;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpoolName {
    public enum State { PENDING, UPLOADING }

    private static final Pattern PATTERN = Pattern.compile(
            "^chunk_(\\d{6})_(data|final)\\.(pending|uploading)\\.wav$");

    private final int sequence;
    private final boolean finalChunk;
    private final State state;

    public SpoolName(int sequence, boolean finalChunk, State state) {
        if (sequence < 0 || sequence > 999999) throw new IllegalArgumentException("sequence out of range");
        this.sequence = sequence;
        this.finalChunk = finalChunk;
        this.state = state;
    }

    public int getSequence() { return sequence; }
    public boolean isFinalChunk() { return finalChunk; }
    public State getState() { return state; }

    public String fileName() {
        return String.format(Locale.US, "chunk_%06d_%s.%s.wav", sequence,
                finalChunk ? "final" : "data",
                state == State.PENDING ? "pending" : "uploading");
    }

    public SpoolName withState(State next) { return new SpoolName(sequence, finalChunk, next); }

    public static SpoolName parse(String fileName) {
        Matcher matcher = PATTERN.matcher(fileName == null ? "" : fileName);
        if (!matcher.matches()) return null;
        int sequence = Integer.parseInt(matcher.group(1));
        boolean finalChunk = "final".equals(matcher.group(2));
        State state = "pending".equals(matcher.group(3)) ? State.PENDING : State.UPLOADING;
        return new SpoolName(sequence, finalChunk, state);
    }
}
