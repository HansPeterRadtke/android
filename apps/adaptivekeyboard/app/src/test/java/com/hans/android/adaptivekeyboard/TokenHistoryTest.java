package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;
public class TokenHistoryTest {
    @Test public void punctuationAndEllipsesAreLearnedAsRealTokens() {
        TokenObserver observer=new TokenObserver();
        assertEquals("hello",observer.observe("hello"," ").get(0)[1]);
        assertEquals(1,observer.observe("hello world",".").size());
        assertTrue(observer.observe("hello world.",".").isEmpty());
        assertTrue(observer.observe("hello world..",".").isEmpty());
        List<String[]> atSpace=observer.observe("hello world..."," ");
        assertEquals(1,atSpace.size());
        assertArrayEquals(new String[]{"world","..."},atSpace.get(0));
    }
    @Test public void tokenModelSuggestsObservedDotsAndHyphensNotInventedWords() {
        TokenHistory model=new TokenHistory();
        model.learn("well","...");model.learn("...","maybe");
        model.learn("foo","-");model.learn("-","bar");
        assertEquals(List.of("..."),model.predict("well","",3));
        assertEquals(List.of("maybe"),model.predict("...","",3));
        assertEquals(List.of("-"),model.predict("foo","",3));
        assertEquals(List.of("bar"),model.predict("-","",3));
        assertEquals(List.of(),model.predict("unknown","",3));
    }
    @Test public void recencyAndLifetimeChangeOrderFromActualTokens(){
        TokenHistory model=new TokenHistory();
        for(int i=0;i<60;i++)model.learn("well","...");
        assertEquals("...",model.predict("well","",3).get(0));
        for(int i=0;i<8;i++)model.learn("well","-");
        assertEquals("-",model.predict("well","",3).get(0));
    }
    @Test public void restoringCountsNeverCreatesUnobservedPhrases(){
        TokenHistory model=new TokenHistory();
        model.restore("well","...",15);
        model.restore("...","maybe",15);
        assertEquals("...",model.predict("well","",3).get(0));
        assertEquals("maybe",model.predict("...","",3).get(0));
    }
}
