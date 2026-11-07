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
        assertEquals("hallo",m.predict("hall","ich","").get(0));
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
        assertEquals("mülltüte",m.predict("müll","bitte","die").get(0));
        m.undo(event);
        assertTrue(m.predict("müll","bitte","die").isEmpty());
    }
    @Test public void misspelledPrefixGetsLowPriorityFallback() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Tastatur",0);m.addBaseWord("Tastaturfeld",12);
        assertEquals("tastatur",m.predict("tastatru","","").get(0));
    }
    @Test public void noUnsupportedNextWordGuessWithoutContext() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("aber",0);m.addBaseWord("und",1);
        assertTrue(m.predict("","","").isEmpty());
        m.learnTyped("Morgen","bis","morgen");
        assertEquals("Morgen",m.predict("","bis","morgen").get(0));
    }
    @Test public void lowerCaseInputOverridesLexiconNounCase() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Haus",24);
        m.learnTyped("haus","im","");
        assertEquals("haus",m.predict("hau","im","").get(0));
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

    @Test public void honorLowerUpperAndAllCapsPrefixes() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Haus",7,"de");
        m.addBaseWord("hello",8,"en");
        assertEquals("haus",m.predict("hau","","").get(0));
        assertEquals("Haus",m.predict("Hau","","").get(0));
        assertEquals("HAUS",m.predict("HAU","","").get(0));
        assertEquals("hello",m.predict("hell","","").get(0));
        assertEquals("Hello",m.predict("Hell","","").get(0));
        assertEquals("HELLO",m.predict("HELL","","").get(0));
    }
    @Test public void germanAndEnglishWordsCoexist() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Haus",10,"de");
        m.addBaseWord("house",10,"en");
        m.addBaseWord("hello",7,"en");
        m.addBaseWord("heute",7,"de");
        m.addBaseWord("the",0,"en");
        m.addBaseWord("der",0,"de");
        assertEquals("house",m.predict("hou","the","").get(0));
        assertEquals("haus",m.predict("hau","der","").get(0));
        assertEquals("hello",m.predict("hel","the","").get(0));
        assertEquals("heute",m.predict("heu","der","").get(0));
    }
    @Test public void learningAWordAtLongerPrefixHelpsShorterPrefixToo() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("habe",2,"de");
        m.addBaseWord("hallo",20,"de");
        for(int i=0;i<4;i++)m.learnSelection("hall","hallo","ich","sage");
        assertEquals("hallo",m.predict("ha","ich","sage").get(0));
    }
    @Test public void rejectedSuggestionGetsDowngradedWithoutEverBeingAccepted() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("hallo",5,"de");
        m.addBaseWord("habe",6,"de");
        assertEquals("hallo",m.predict("ha","ich","heute").get(0));
        PredictionModel.Rejection r=m.learnRejection("ha","hallo","ich","heute");
        assertNotNull(r);
        assertEquals("habe",m.predict("ha","ich","heute").get(0));
    }
    @Test public void rejectionsRestoreFromDiskCountAndDoNotOverwriteSelections() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("hallo",5,"de");m.addBaseWord("habe",6,"de");
        m.restoreRejection("heute","ich","ha","hallo",2);
        m.restoreRejection("","","ha","hallo",2);
        assertEquals("habe",m.predict("ha","ich","heute").get(0));
        m.learnSelection("ha","hallo","ich","heute");
        assertEquals("hallo",m.predict("ha","ich","heute").get(0));
    }
    @Test public void englishContractionsRemainWholeWords() {
        TextContext c=TextContext.fromBeforeCursor("I don't know wh");
        assertEquals("wh",c.prefix);
        assertEquals("know",c.previous);
        assertEquals("don't",c.beforePrevious);
        assertEquals("you're",TextContext.fromBeforeCursor("you're").prefix);
        assertEquals("I'm",TextContext.fromBeforeCursor("I'm happy").previous);
    }

    @Test public void choosingHelloOnceOverridesGenericCandidatesAtShorterPrefix() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("help",1,"en");
        m.addBaseWord("hello",200,"en");
        m.addBaseWord("helfen",3,"de");
        m.addBaseWord("I",4,"en");
        m.addBaseWord("am",10,"en");
        // The user has typed a longer prefix, then explicitly selected hello.
        m.learnSelection("hell","hello","am","I");
        assertEquals("hello",m.predict("hel","am","I").get(0));
    }
    @Test public void observedNextWordOutranksAlternateInSameConversationContext() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("get",1,"en");
        m.addBaseWord("go",10,"en");
        m.learnTyped("get","to","want");
        for(int i=0;i<3;i++)m.learnTyped("go","to","want");
        assertEquals("go",m.predict("","to","want").get(0));
        assertEquals("go",m.predict("g","to","want").get(0));
    }
    @Test public void lastSelectedWordBeatsOlderChoiceForTheSamePrefix() throws Exception {
        PredictionModel model=new PredictionModel();
        model.addBaseWord("help",0,"en");
        model.addBaseWord("hello",800,"en");
        model.learnSelection("hel","help","","");
        Thread.sleep(22);
        PredictionModel.Event latest=model.learnSelection("hell","hello","","");
        assertEquals("hello",model.predict("hel","","").get(0));
        PredictionModel restored=new PredictionModel();
        restored.addBaseWord("help",0,"en");restored.addBaseWord("hello",800,"en");
        restored.restoreWord("help",1,1,latest.timestamp-22);
        restored.restoreWord("hello",1,1,latest.timestamp);
        for(String prefix:new String[]{"h","he","hel"}) {
            restored.restoreChoice("","",prefix,"help",1,latest.timestamp-22);
            restored.restoreChoice("","",prefix,"hello",1,latest.timestamp);
        }
        restored.restoreChoice("","","hell","hello",1,latest.timestamp);
        assertEquals("hello",restored.predict("hel","","").get(0));
    }
    @Test public void baselineMakesPredictionsBeforeAnyLetters() {
        PredictionModel m=new PredictionModel();
        BaselineNgrams b=new BaselineNgrams();
        for(String word:new String[]{"nicht","sehr","gerne","hier","jetzt"})m.addBaseWord(word,30,"de");
        b.add("B","","bin","sehr",100,"de");
        b.add("B","","bin","nicht",60,"de");
        b.add("B","","bin","gerne",40,"de");
        b.add("T","ich","bin","hier",120,"de");
        b.add("S","","","ich",60,"de");
        b.add("S","","","wir",30,"de");
        b.add("S","","","ich",20,"de");
        m.setBaselineNgrams(b);
        assertEquals(3,m.predict("","bin","ich").size());
        assertEquals("hier",m.predict("","bin","ich").get(0));
        assertFalse(m.predict("","","").isEmpty());
    }
    @Test public void personalChoiceStillBeatsGenericContext() {
        PredictionModel m=new PredictionModel();
        BaselineNgrams b=new BaselineNgrams();
        b.add("T","ich","bin","nicht",2500,"de");
        b.add("T","ich","bin","hier",100,"de");
        m.addBaseWord("nicht",5,"de");m.addBaseWord("hier",60,"de");
        m.setBaselineNgrams(b);
        assertEquals("nicht",m.predict("","bin","ich").get(0));
        for(int i=0;i<3;i++)m.learnSelection("","hier","bin","ich");
        assertEquals("hier",m.predict("","bin","ich").get(0));
    }
    @Test public void sentencePunctuationResetsPreviousWords() {
        TextContext c=TextContext.fromBeforeCursor("Ich bin hier. ");
        assertEquals("",c.prefix);assertEquals("",c.previous);assertEquals("",c.beforePrevious);
        c=TextContext.fromBeforeCursor("Ich war da? Hello");
        assertEquals("Hello",c.prefix);assertEquals("",c.previous);
    }
    @Test public void neuralBoostRanksEnglishAndRespectsStrongerPersonalLearning() {
        PredictionModel model=new PredictionModel();
        BaselineNgrams baseline=new BaselineNgrams();
        baseline.add("T","i","am","hungry",50,"en");
        baseline.add("T","i","am","going",30,"en");
        model.addBaseWord("hungry",100,"en");model.addBaseWord("going",200,"en");
        model.setBaselineNgrams(baseline);
        assertEquals("hungry",model.predict("","am","i").get(0));
        java.util.Map<String,Double> hints=java.util.Map.of("going",40.0,"hungry",2.0);
        assertEquals("going",model.predict("","am","i",hints).get(0));
        for(int i=0;i<8;i++)model.learnSelection("","hungry","am","i");
        assertEquals("hungry",model.predict("","am","i",hints).get(0));
    }
    @Test public void keepDictionaryNounCaseSeparateFromSentenceUse() {
        PredictionModel model=new PredictionModel();
        model.addBaseWord("Haus",7,"de");
        model.addBaseWord("ich",2,"de");
        model.learnTyped("Ich","","");
        model.learnTyped("haus","im","");
        model.learnTyped("Haus","das","");
        assertEquals("haus",model.predict("hau","im","").get(0));
        assertEquals("Haus",model.predict("","im","").get(0));
        assertEquals("ich",model.predict("","","").isEmpty()?"ich":
            WordCase.display("ich","","wir sagen ",false));
    }
    @Test public void longOrBlankTokensDoNotLearn() {
        PredictionModel m=new PredictionModel();
        assertNull(m.learnTyped("", "a", "b"));
        assertNull(m.learnTyped("a".repeat(61),"a","b"));
    }
}
