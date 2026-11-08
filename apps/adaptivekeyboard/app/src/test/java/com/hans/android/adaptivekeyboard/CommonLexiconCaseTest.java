package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public final class CommonLexiconCaseTest {
    @Test public void knownCommonGermanWordsAreNeverAccidentalAcronyms() {
        assertEquals("so",CommonLexiconCase.canonical("SO","de"));
        assertEquals("Haus",CommonLexiconCase.canonical("HAUS","de"));
        assertEquals("die",CommonLexiconCase.canonical("DIE","de"));
        assertEquals("NASA",CommonLexiconCase.canonical("NASA","de"));
    }
    @Test public void preservesProperNamesUnknownToCommonDictionary() {
        assertEquals("NASA",CommonLexiconCase.canonical("NASA","en"));
        assertEquals("Now",CommonLexiconCase.canonical("Now","en"));
        assertEquals("now",CommonLexiconCase.canonical("NOW","en"));
        assertEquals("iPhone",CommonLexiconCase.canonical("iPhone","en"));
        assertEquals("Haus",CommonLexiconCase.canonical("HAUS","de"));
    }
}
