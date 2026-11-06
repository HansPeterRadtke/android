package com.hans.android.adaptivekeyboard;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class PredictionModelTest {
    @Test public void contextIsTwoPriorWordsPlusCurrentPrefix() {
        TextContext c=TextContext.fromBeforeCursor("Ich hätte heute Hau");
        assertEquals("Hau",c.prefix);
        assertEquals("heute",c.previous);
        assertEquals("hätte",c.beforePrevious);
        TextContext end=TextContext.fromBeforeCursor("Ich hätte heute ");
        assertEquals("",end.prefix);
        assertEquals("heute",end.previous);
        assertEquals("hätte",end.beforePrevious);
    }
    @Test public void germanSpecialLettersAndSeparatorsWork() {
        TextContext c=TextContext.fromBeforeCursor("Grüße an Müller: Überr");
        assertEquals("Überr",c.prefix);
        assertEquals("Müller",c.previous);
        assertEquals("an",c.beforePrevious);
        assertEquals("straße",TextContext.normalize("Straße"));
    }
    @Test public void exactPrefixAndThreeSuggestions() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("habe",1);m.addBaseWord("Hallo",2);m.addBaseWord("Haus",3);
        List<String> suggestions=m.predict("ha","","");
        assertEquals(3,suggestions.size());
        assertEquals("habe",suggestions.get(0));
    }
    @Test public void aDisplayedPredictionDoesNotCountAsLearning() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("habe",0);m.addBaseWord("Hallo",5);
        assertEquals("habe",m.predict("ha","ich","").get(0));
        // Continuing to type must change the candidates, without any selection event.
        assertEquals("Hallo",m.predict("hall","ich","").get(0));
        assertEquals("habe",m.predict("ha","ich","").get(0));
    }
    @Test public void contextBeatsGenericFrequency() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("gehen",5);m.addBaseWord("gerne",6);m.addBaseWord("genau",7);
        for(int i=0;i<5;i++)m.learnTyped("genau","ich","war");
        for(int i=0;i<5;i++)m.learnTyped("gehen","wir","heute");
        assertEquals("gehen",m.predict("ge","wir","heute").get(0));
        assertEquals("genau",m.predict("ge","ich","war").get(0));
    }
    @Test public void exactChoicePrefixAndPrecedingWordsDriveRanking() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("gerne",1);m.addBaseWord("gehen",2);m.addBaseWord("genau",3);
        for(int i=0;i<4;i++)m.learnSelection("ge","gehen","wir","jetzt");
        assertEquals("gehen",m.predict("ge","wir","jetzt").get(0));
    }
    @Test public void rarePersonalWordsBecomeSuggested() {
        PredictionModel m=new PredictionModel();
        assertTrue(m.predict("xyzq","hallo","").isEmpty());
        m.learnTyped("xyzqwert","hallo","mein");
        assertEquals("xyzqwert",m.predict("xyzq","hallo","mein").get(0));
    }
    @Test public void undoRemovesTemporaryLearning() {
        PredictionModel m=new PredictionModel();
        PredictionModel.Event event=m.learnTyped("Mülltüte","bitte","die");
        assertEquals("Mülltüte",m.predict("müll","bitte","die").get(0));
        m.undo(event);
        assertTrue(m.predict("müll","bitte","die").isEmpty());
    }
    @Test public void misspelledPrefixGetsLowPriorityFallback() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Tastatur",0);m.addBaseWord("Tastaturfeld",12);
        assertEquals("Tastatur",m.predict("tastatru","","").get(0));
    }
    @Test public void noUnsupportedNextWordGuessWithoutContext() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("aber",0);m.addBaseWord("und",1);
        assertTrue(m.predict("","","").isEmpty());
        m.learnTyped("Morgen","bis","morgen");
        assertEquals("Morgen",m.predict("","bis","morgen").get(0));
    }
    @Test public void germanNounCaseSurvivesLowerCaseLearning() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Haus",24);
        m.learnTyped("haus","im","");
        assertEquals("Haus",m.predict("hau","im","").get(0));
    }
    @Test public void savedContextAndSelectionSurviveModelRecreation() {
        PredictionModel first=new PredictionModel();
        first.addBaseWord("gehen",3);
        PredictionModel.Event e=first.learnSelection("ge","gehen","wir","heute");
        assertEquals("gehen",first.predict("ge","wir","heute").get(0));
        PredictionModel reloaded=new PredictionModel();
        reloaded.addBaseWord("gehen",3);
        reloaded.restoreWord("gehen",1,1);
        reloaded.restorePair(e.previous,e.word,1);
        reloaded.restoreTriple(e.beforePrevious,e.previous,e.word,1);
        reloaded.restoreChoice(e.beforePrevious,e.previous,e.prefix,e.word,1);
        reloaded.restoreChoice("","",e.prefix,e.word,1);
        assertEquals("gehen",reloaded.predict("ge","wir","heute").get(0));
    }

    @Test public void longOrBlankTokensDoNotLearn() {
        PredictionModel m=new PredictionModel();
        assertNull(m.learnTyped("", "a", "b"));
        assertNull(m.learnTyped("a".repeat(61),"a","b"));
    }
}
