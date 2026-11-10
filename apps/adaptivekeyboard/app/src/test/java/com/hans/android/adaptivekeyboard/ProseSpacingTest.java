package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProseSpacingTest {
    @Test public void punctuationClosesTheWordOrPreviousMark() {
        assertEquals(1,ProseSpacing.spacesBeforeMark("Hello ","!"));
        assertEquals(3,ProseSpacing.spacesBeforeMark("Hello!  \u00a0","!"));
        assertEquals(0,ProseSpacing.spacesBeforeMark("Hello!!","!"));
        assertEquals(0,ProseSpacing.spacesBeforeMark("Hello\n  ","!"));
        assertEquals(0,ProseSpacing.spacesBeforeMark("Hello\t  ","!"));
        assertEquals(0,ProseSpacing.spacesBeforeMark("Hello ","("));
    }
    @Test public void acceptedWordsAndPhrasesRespectAdjacentSeparators() {
        assertEquals(" Next ",ProseSpacing.completion("Wow!!!",false,"Next",""));
        assertEquals(" we are ",ProseSpacing.completion("Ok,",false,"we are",""));
        assertEquals("we are",ProseSpacing.completion("Ok, ",true,"we are","!"));
        assertEquals("great",ProseSpacing.completion("Really ",true,"great","\n"));
        assertEquals("well ",ProseSpacing.completion("Very ",true,"well"," world"));
        assertEquals("com ",ProseSpacing.completion("example.",true,"com",""));
        assertEquals("mail ",ProseSpacing.completion("E-",true,"mail",""));
    }
}
