package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

public final class BaselineNgramsTest {
    @Test public void suppliesContextEvenWithoutAnyTypedPrefix() {
        BaselineNgrams m=new BaselineNgrams();
        m.add("B","","möchte","ein",30,"de");
        m.add("B","","möchte","die",20,"de");
        m.add("B","","möchte","das",15,"de");
        Map<String,Double> scores=m.candidates("","möchte","ich");
        assertTrue(scores.get("ein")>scores.get("das"));
        assertEquals(3,scores.size());
        assertTrue(m.candidates("ei","möchte","ich").containsKey("ein"));
    }
    @Test public void trigramOverridesAmbiguousBigramAndNeverAddsWrongPrefix() {
        BaselineNgrams m=new BaselineNgrams();
        m.add("B","","bin","gut",90,"de");
        m.add("T","ich","bin","hier",140,"de");
        Map<String,Double> scores=m.candidates("","bin","ich");
        assertTrue(scores.get("hier")>scores.get("gut"));
        assertEquals(0,m.candidates("zzzz","bin","ich").size());
    }
    @Test public void fallbacksAtStartAndUnknownContextStillExist() {
        BaselineNgrams m=new BaselineNgrams();
        m.add("S","","","ich",140,"de");
        m.add("S","","","i",200,"en");
        m.add("S","","","das",90,"de");
        assertEquals(3,m.candidates("","","").size());
        assertEquals(3,m.candidates("","unbekannt","irgendwas").size());
    }
}
