package com.hans.android.adaptivekeyboard;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayDeque;
import java.util.Map;

public final class PersonalNextWordsTest {
    @Test public void recentSequenceBeatsOldLifetimeAfterRepeatedChange() {
        ArrayDeque<String> history=new ArrayDeque<>();
        for(int i=0;i<48;i++)PersonalNextWords.append(history,"gehen");
        int oldCount=100,newCount=0;
        assertTrue(PersonalNextWords.score(oldCount,oldCount+newCount,
            PersonalNextWords.recentShares(history).get("gehen"),history.size())>0.9);
        for(int i=0;i<7;i++) {
            PersonalNextWords.append(history,"bleiben");
            newCount++;
        }
        Map<String,Double> recent=PersonalNextWords.recentShares(history);
        double old=PersonalNextWords.score(oldCount,oldCount+newCount,
            recent.getOrDefault("gehen",0.0),history.size());
        double newer=PersonalNextWords.score(newCount,oldCount+newCount,
            recent.getOrDefault("bleiben",0.0),history.size());
        assertTrue("recent repeated choice should become preferable",newer>old);
    }
    @Test public void oneRecentChoiceDoesNotImmediatelyEraseLargeHistoricalPreference() {
        ArrayDeque<String> history=new ArrayDeque<>();
        PersonalNextWords.append(history,"bleiben");
        double old=PersonalNextWords.score(100,101,0.0,history.size());
        double recent=PersonalNextWords.score(1,101,1.0,history.size());
        assertTrue(old>recent);
    }
    @Test public void boundedLosslessLatestHistoryRoundTripAndUndo() {
        ArrayDeque<String> original=new ArrayDeque<>();
        for(int i=0;i<100;i++)PersonalNextWords.append(original,(i%2==0)?"let's":"go");
        assertEquals(48,original.size());
        String encoded=PersonalNextWords.encode(original);
        ArrayDeque<String> restored=PersonalNextWords.decode(encoded);
        assertEquals(new java.util.ArrayList<>(original),new java.util.ArrayList<>(restored));
        PersonalNextWords.undoLast(restored,"go");
        assertEquals(47,restored.size());
        ArrayDeque<String> copy=PersonalNextWords.decode(encoded);
        String evicted=PersonalNextWords.append(copy,"new");
        PersonalNextWords.undoLast(copy,"new",evicted);
        assertEquals(encoded,PersonalNextWords.encode(copy));
        String changed=PersonalNextWords.updated(encoded,"new",1);
        assertEquals(encoded,PersonalNextWords.updated(changed,"new",-1,evicted));
    }
    @Test public void invalidTokensCannotCorruptHistory() {
        ArrayDeque<String> history=new ArrayDeque<>();
        PersonalNextWords.append(history,"hello\u001fworld");
        PersonalNextWords.append(history,"hello");
        assertEquals(1,history.size());
        assertTrue(PersonalNextWords.valid("let's"));
        assertTrue(PersonalNextWords.valid("über"));
        assertFalse(PersonalNextWords.valid("malicious\u001fpart"));
    }
}
