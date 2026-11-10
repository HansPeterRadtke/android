package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
import java.util.stream.Collectors;
public class GrammarTokensTest {
    private static List<String> parts(String s) {
        return GrammarTokens.tokenize(s).stream().map(t->t.text).collect(Collectors.toList());
    }
    @Test public void dotsHyphensContractionsAndNumbersAreIndependentTokens(){
        assertEquals(List.of("Hi","...","there","-","and","3",".","14"),
            parts("Hi... there - and 3.14"));
        assertEquals(List.of("Let's","go","!"),parts("Let's go!"));
        assertEquals(List.of("ich","-","und","öfter"),parts("ich-und öfter"));
        assertEquals(List.of("www",".","example","-","shop",".","com"),
            parts("www.example-shop.com"));
    }
    @Test public void ellipsisIsNotAFullStop(){
        assertFalse(GrammarTokens.isSentenceStart("Well... "));
        assertFalse(GrammarTokens.isSentenceStart("Well… "));
        assertFalse(GrammarTokens.isSentenceStart("Hello - "));
        assertEquals(0,GrammarTokens.lastSentenceStart("Well... maybe"));
        assertEquals("...",GrammarTokens.lastToken("well..."));
    }
    @Test public void onlyTrueEndOfSentenceCapitalizes(){
        assertTrue(GrammarTokens.isSentenceStart("Hello. "));
        assertTrue(GrammarTokens.isSentenceStart("Really? "));
        assertTrue(GrammarTokens.isSentenceStart("Okay! "));
        assertTrue(GrammarTokens.isSentenceStart("Hello.\n"));
        assertFalse(GrammarTokens.isSentenceStart("3.14 "));
        assertFalse(GrammarTokens.isSentenceStart("example.com "));
        assertFalse(GrammarTokens.isSentenceStart("Dr. "));
        assertFalse(GrammarTokens.isSentenceStart("z. B. "));
        assertFalse(GrammarTokens.isSentenceStart("I am going - "));
        assertEquals(6,GrammarTokens.lastSentenceStart("Hello. Next"));
    }
    @Test public void decimalsUrlsAndEllipsesPreserveEarlierWords(){
        TextContext c=TextContext.fromBeforeCursor("I think... maybe ");
        assertEquals("",c.prefix);
        assertEquals("maybe",c.previous);
        assertEquals("think",c.beforePrevious);
        c=TextContext.fromBeforeCursor("hello-world ");
        assertEquals("world",c.previous);
        assertEquals("hello",c.beforePrevious);
        c=TextContext.fromBeforeCursor("visit example.com ");
        assertEquals("com",c.previous);
    }

    @Test public void fullStopAfterOrdinaryWordIsNotAnAbbreviationSuffix() {
        assertTrue(WordCase.sentenceStart("Wir sind vier Teams. "));
        assertTrue(WordCase.sentenceStart("I liked both films. "));
        assertEquals("Wir sind vier Teams.".length(),
                GrammarTokens.lastSentenceStart("Wir sind vier Teams. Weiter geht es"));
        assertFalse(WordCase.sentenceStart("Bitte fragen Sie Dr. "));
    }

    @Test public void bothDotsOfSpacedGermanAbbreviationsRetainContext() {
        assertEquals(0, GrammarTokens.lastSentenceStart("Das gilt z. B. heute"));
        assertEquals(0, GrammarTokens.lastSentenceStart("Das gilt d. h. auch hier"));
        assertEquals(0, GrammarTokens.lastSentenceStart("Das gilt u. a. heute"));
    }

    @Test public void casingUsesTheWholeDomainAndRecognizesGermanClosingQuotes() {
        assertFalse(WordCase.sentenceStart("Besuche beispiel.de"));
        assertTrue(WordCase.sentenceStart("Sie sagte „Bis morgen.“ "));
        assertTrue(WordCase.sentenceStart("Sie sagte „Bis morgen.“\u00a0"));
        assertTrue(WordCase.sentenceStart("Er fragte »Wirklich?« "));
        assertFalse(WordCase.sentenceStart("Er sagte „vielleicht…“ "));
    }
}
