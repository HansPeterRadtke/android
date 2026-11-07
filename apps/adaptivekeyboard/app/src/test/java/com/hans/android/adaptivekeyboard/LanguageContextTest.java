package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public final class LanguageContextTest {
    @Test public void germanLayoutDefaultsToGerman() {
        assertEquals("de",LanguageContext.detect(""));
        assertEquals("de",LanguageContext.detect("hallo. "));
        assertEquals("de",LanguageContext.detect("Ich bin heute "));
        assertEquals("de",LanguageContext.detect("Ich bin hier. ha"));
        assertEquals("de",LanguageContext.detect("wie geht's mit dir?"));
    }
    @Test public void englishContextSelectsNeural() {
        assertEquals("en",LanguageContext.detect("I am "));
        assertEquals("en",LanguageContext.detect("I want to "));
        assertEquals("en",LanguageContext.detect("Hello. ha"));
        assertEquals("en",LanguageContext.detect("What are you going to do? "));
    }
    @Test public void letsIsStrongEnglishEvidenceEvenAfterGermanOrOkay(){
        assertEquals("en",LanguageContext.detect("Let's "));
        assertEquals("en",LanguageContext.detect("let's"));
        assertEquals("en",LanguageContext.detect("let’s "));
        assertEquals("en",LanguageContext.detect("Okay, let's "));
        assertEquals("en",LanguageContext.detect("Ich bin heute hier. Okay, let's "));
        assertEquals("en",LanguageContext.detect("Ich bin heute hier. Okay, let's g"));
        assertEquals("en",LanguageContext.detect("we're going to "));
        assertEquals("en",LanguageContext.detect("I don't want that "));
    }
    @Test public void latestLanguageMaySwitchMidConversation(){
        assertEquals("de",LanguageContext.detect("I am fine. Ich bin hier. "));
        assertEquals("en",LanguageContext.detect("Ich bin hier. I am fine. "));
    }
}
