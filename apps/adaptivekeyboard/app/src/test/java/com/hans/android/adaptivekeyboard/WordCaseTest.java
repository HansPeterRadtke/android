package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public final class WordCaseTest {
    @Test public void preservesExactTypedPrefixCase() {
        assertEquals("haus",WordCase.matchPrefix("Haus","hau"));
        assertEquals("Haus",WordCase.matchPrefix("haus","Ha"));
        assertEquals("HAUS",WordCase.matchPrefix("Haus","HA"));
        assertEquals("hAus",WordCase.matchPrefix("haus","hA"));
        assertEquals("Haus",WordCase.matchPrefix("Haus","Haus"));
        assertEquals("häuser",WordCase.matchPrefix("Häuser","häu"));
    }
    @Test public void startAndAfterPunctuationAreCapitalsButNotMidSentence() {
        assertTrue(WordCase.sentenceStart(""));
        assertTrue(WordCase.sentenceStart("Das war gut. "));
        assertTrue(WordCase.sentenceStart("Are you there? "));
        assertTrue(WordCase.sentenceStart("Okay! "));
        assertFalse(WordCase.sentenceStart("Ich bin "));
        assertFalse(WordCase.sentenceStart("I am not"));
        assertEquals("Nicht",WordCase.display("nicht","","Okay. ",false));
        assertEquals("nicht",WordCase.display("nicht","","Ich bin ",false));
    }
    @Test public void lowerCaseManualInputOverridesSentenceCapital() {
        assertEquals("nichT",WordCase.display("Nicht","nichT","Hallo. nichT",true));
        assertEquals("hallo",WordCase.display("Hallo","hallo","",true));
        assertEquals("i",WordCase.display("i","i","",true));
        assertEquals("I",WordCase.display("i","","you and ",false));
    }
    @Test public void abbreviationsAndDecimalsDoNotTriggerSpuriousCapitals() {
        assertFalse(WordCase.sentenceStart("z. B. "));
        assertFalse(WordCase.sentenceStart("Das ist z.B. "));
        assertFalse(WordCase.sentenceStart("Etwas bzw. "));
        assertFalse(WordCase.sentenceStart("Dr. "));
        assertFalse(WordCase.sentenceStart("3.5 "));
        assertTrue(WordCase.sentenceStart("Das ist richtig. "));
        assertTrue(WordCase.sentenceStart("Wirklich? "));
    }
    @Test public void coldStartupDoesNotReuseSentenceInitialCapitalization() {
        // The core lexicon is not necessarily loaded when the keyboard opens.
        assertEquals("now",WordCase.display("Now","","I am ",false));
        assertEquals("okay",WordCase.display("Okay","","I think it's ",false));
        assertEquals("Now",WordCase.display("Now","","I am done. ",false));
        assertEquals("Now",WordCase.display("Now","","",false));
        assertEquals("Now",WordCase.display("Now","No","I am No",false));
        assertEquals("NOW",WordCase.display("Now","NOW","I am NOW",false));
    }
    @Test public void preserveProperNamesInitialismsAndExplicitCaps() {
        assertEquals("Alice",WordCase.display("Alice","","I met ",false));
        assertEquals("May",WordCase.display("May","","I travel in ",false));
        assertEquals("NASA",WordCase.display("NASA","","I work at ",false));
        assertEquals("iPhone",WordCase.display("iPhone","","I use an ",false));
        assertEquals("I",WordCase.display("i","","I think ",false));
        assertEquals("haus",WordCase.display("Haus","hau","ich sehe ein hau",false));
    }
    @Test public void germanCanonicalNounsRetainTheirCapitalWhenUnprefixed() {
        assertEquals("Haus",WordCase.display("Haus","","ich sehe ein ",false));
        assertEquals("schön",WordCase.display("schön","","das ist ",false));
    }
}
