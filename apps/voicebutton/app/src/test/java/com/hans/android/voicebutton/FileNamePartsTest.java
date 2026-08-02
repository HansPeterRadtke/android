package com.hans.android.voicebutton;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileNamePartsTest {
    @Test public void longNameIsCompleteAndKeepsExtension() {
        String name = "2026-08-02 17-17-00 extremely long recording description that must remain visible.mp3";
        FileNameParts parts = FileNameParts.split(name, 42);
        assertEquals(name, parts.complete());
        assertTrue(parts.tail.endsWith(".mp3"));
    }

    @Test public void shortNameStaysOnFirstLine() {
        FileNameParts parts = FileNameParts.split("short.wav", 42);
        assertEquals("short.wav", parts.head);
        assertEquals("", parts.tail);
    }
}
