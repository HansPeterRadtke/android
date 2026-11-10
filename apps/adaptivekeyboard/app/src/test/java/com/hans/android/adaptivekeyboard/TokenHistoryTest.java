package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class TokenHistoryTest {
    @Test public void punctuationAndWhitespaceCannotBeLearnedOrRestored() {
        TokenHistory history=new TokenHistory();
        PredictionModel words=new PredictionModel();
        for(String noise:List.of("!", "!!!", "! !", "...", " ", "a b", " word", "word ", "a b", "—", "-", "’", "🎉")) {
            assertNull(history.learn("hello",noise));
            assertNull(history.learn(noise,"next"));
            history.restore("hello",noise,20);
            assertNull(words.learnTyped(noise,"hello",""));
            assertNull(words.learnSelection("",noise,"hello",""));
            words.restoreWord(noise,25,0);
            words.restorePair("hello",noise,12);
        }
        assertTrue(history.predict("hello","",3).isEmpty());
        assertTrue(words.predictPersonal("","hello","","en").isEmpty());
        assertEquals(0,words.size());
        List<String> fallback=words.predict("","say","",java.util.Map.of("!",100.0,"two words",90.0,"hello",10.0),"en");
        assertTrue(fallback.contains("hello"));
        for(String word:fallback)assertTrue(PersonalNextWords.valid(word));
    }

    @Test public void restorationPreservesLegitimateWordsAndOrdering() {
        String saved=String.join("\u001f","!","Grüße","! !","don't","E-Mail","!!!","R2D2");
        assertEquals(List.of("Grüße","don't","E-Mail","R2D2"),new java.util.ArrayList<>(TokenHistory.decode(saved)));
        TokenHistory history=new TokenHistory();
        history.restore(TokenHistory.START,"hallo",9);
        history.learn("hallo","welt");
        assertEquals(List.of("hallo"),history.predict("","",3));
        assertEquals(List.of("welt"),history.predict("hallo","",3));
    }

    @Test public void realWordRecencyStillOutweighsOlderObservations() {
        TokenHistory history=new TokenHistory();
        for(int i=0;i<60;i++)history.learn("well","then");
        assertEquals("then",history.predict("well","",3).get(0));
        for(int i=0;i<8;i++)history.learn("well","maybe");
        assertEquals("maybe",history.predict("well","",3).get(0));
        assertTrue(history.predict("else","",3).isEmpty());
    }
}
