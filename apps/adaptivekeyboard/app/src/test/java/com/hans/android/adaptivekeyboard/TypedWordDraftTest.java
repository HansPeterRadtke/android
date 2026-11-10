package com.hans.android.adaptivekeyboard;
import static org.junit.Assert.*;
import org.junit.Test;

public final class TypedWordDraftTest {
    @Test public void unfinishedLastWordKeepsItsContextAndFinishesOnce() {
        TypedWordDraft d=new TypedWordDraft();d.observe("das ist ","",false);
        d.append("Neu");d.append("wort");
        TextContext word=d.finish();
        assertEquals("Neuwort",word.prefix);assertEquals("ist",word.previous);
        assertEquals("das",word.beforePrevious);assertNull(d.finish());
    }
    @Test public void cursorMovePasteAndDeletionDoNotCreateWords() {
        TypedWordDraft d=new TypedWordDraft();d.observe("existing words","",false);
        assertNull(d.finish());d.append("x");d.observe("new paste","",false);assertNull(d.finish());
        d.reset();d.append("a");d.backspace(false);assertNull(d.finish());
        d.observe("ab","cd",false);d.append("e");assertNull(d.finish());
    }
    @Test public void backspaceAndApostropheRetainOnlyTheFinishedSpelling() {
        TypedWordDraft d=new TypedWordDraft();d.append("don");d.append("'");d.append("x");
        d.backspace(false);d.append("t");assertEquals("don't",d.finish().prefix);
        d.separator("don't"," ");assertNull(d.finish());
    }
    @Test public void returningToAnEndedDraftCanCorrectIt() {
        TypedWordDraft d=new TypedWordDraft();d.append("fredx");TextContext old=d.finish();
        d.resume("fredx",old);d.backspace(false);assertEquals("fred",d.finish().prefix);
    }
}
