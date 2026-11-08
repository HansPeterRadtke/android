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
        assertTrue(m.predictPersonal("","","","de").isEmpty());
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
    @Test public void baselineSuggestsWithinSentenceButNeverInventsMessageOpenings() {
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
        assertEquals("Ich",model.predict("","","").get(0));
    }
    @Test public void zeroPrefixNeverRepeatsTheSameCandidate() {
        PredictionModel m=new PredictionModel();
        BaselineNgrams b=new BaselineNgrams();
        b.add("B","","bin","nicht",50,"de");
        b.add("B","","bin","ein",30,"de");
        b.add("B","","bin","hier",20,"de");
        m.setBaselineNgrams(b);
        java.util.List<String> ranked=m.predict("","bin","ich",java.util.Collections.emptyMap(),"de");
        assertEquals(3,ranked.size());
        assertEquals(3,new java.util.HashSet<>(ranked).size());
    }
    @Test public void neuralCandidatesAreNotScoredTwiceWithoutPrefix() {
        PredictionModel m=new PredictionModel();
        java.util.Map<String,Double> neural=java.util.Map.of("wissen",60.0,"gehen",57.0,"machen",54.0);
        java.util.List<String> ranked=m.predict("","gerne","würde",neural,"de");
        assertEquals(3,new java.util.HashSet<>(ranked).size());
        assertEquals("wissen",ranked.get(0));
    }
    @Test public void shortPrefixCacheDoesNotHideRarePersonalContext() {
        PredictionModel m=new PredictionModel();
        for(int i=0;i<1800;i++)m.addBaseWord("ab"+i,100+i,"de");
        m.addBaseWord("aber",1,"de");
        assertEquals("aber",m.predict("ab","","").get(0));
        m.learnTyped("abgeordnetenhaus","bitte","das");
        assertEquals("abgeordnetenhaus",m.predict("ab","bitte","das").get(0));
        assertEquals("abgeordnetenhaus",m.predict("abg","bitte","das").get(0));
    }
    @Test public void clearingPrivateHistoryDoesNotDropBundledDictionary() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("Haus",2,"de");
        m.learnSelection("hau","Haustier","mein","das");
        m.learnTyped("Geheimwort","das","mein");
        assertEquals("haustier",m.predict("hau","mein","das").get(0));
        m.clearLearningData();
        assertEquals("haus",m.predict("hau","mein","das").get(0));
        assertTrue(m.predict("geheimw","das","mein").isEmpty());
        assertEquals(1,m.size());
    }
    @Test(timeout=15000) public void longNonsensePrefixCannotTriggerUnboundedTypoScan() {
        PredictionModel m=new PredictionModel();
        for(int i=0;i<1600;i++)m.addBaseWord("aaaa"+i,300+i,"de");
        for(int i=0;i<60;i++)m.predict("aaaaaaaaaaaaaaaaaaaaaaa","ich","bin");
    }
    @Test public void knownNextWordCannotBeOverriddenByAnyNeuralOrGenericScore(){
        PredictionModel model=new PredictionModel();
        BaselineNgrams baseline=new BaselineNgrams();
        baseline.add("B","","let's","gehen",500000,"de");
        baseline.add("B","","let's","machen",300000,"de");
        model.setBaselineNgrams(baseline);
        model.learnTyped("go","let's","okay");
        model.learnTyped("go","let's","hello");
        assertEquals(java.util.List.of("go"),model.predictPersonal("","let's","okay","en"));
        assertEquals("go",model.predict("","let's","okay",
            java.util.Map.of("gehen",999999.0,"machen",99999.0),"en").get(0));
        assertEquals(java.util.List.of("go"),model.predictPersonal("g","let's","okay","en"));
        assertEquals("go",model.predict("g","let's","okay",
            java.util.Map.of("gehen",999999.0),"en").get(0));
        assertTrue(model.hasPersonalNext("","let's"));
        assertTrue(model.hasPersonalNext("g","let's"));
        assertFalse(model.hasPersonalNext("x","let's"));
        // No previous personal candidate for this intentionally different
        // prefix; now neural/model completion is allowed.
        assertEquals("newword",model.predict("new","let's","okay",
            java.util.Map.of("newword",1000.0),"en").get(0));
    }
    @Test public void lifetimeFrequencyAndMovingAverageAreBothUsed(){
        PredictionModel model=new PredictionModel();
        model.addBaseWord("gehen",1,"de");
        model.addBaseWord("bleiben",8,"de");
        for(int i=0;i<100;i++)model.learnTyped("gehen","möchte","");
        assertEquals("gehen",model.predict("","möchte","").get(0));
        model.learnTyped("bleiben","möchte","");
        assertEquals("gehen",model.predict("","möchte","").get(0));
        for(int i=0;i<8;i++)model.learnTyped("bleiben","möchte","");
        assertEquals("bleiben",model.predict("","möchte","").get(0));
        assertEquals(2,model.predictPersonal("","möchte","","de").size());
        assertEquals(3,model.predict("","möchte","").size());
    }
    @Test public void restoredHistoryGivesSameOrderingAndUndoWorks(){
        PredictionModel live=new PredictionModel();
        live.restorePair("let's","go",50);
        live.restorePair("let's","stay",12);
        String data="";
        for(int i=0;i<10;i++)data=PersonalNextWords.updated(data,"stay",1);
        live.restoreRecent("let's",data);
        PredictionModel restored=new PredictionModel();
        restored.restorePair("let's","go",50);
        restored.restorePair("let's","stay",12);
        restored.restoreRecent("let's",data);
        assertEquals(live.predict("","let's",""),restored.predict("","let's",""));
        assertEquals("stay",restored.predict("","let's","").get(0));
        PredictionModel.Event temporary=restored.learnTyped("hello","let's","");
        restored.undo(temporary);
        assertFalse(restored.predict("","let's","").contains("hello"));
    }
    @Test public void latestPreviousWordHasPriorityOverGenericEarlierSentence(){
        PredictionModel model=new PredictionModel();
        model.learnTyped("going","let's","we");
        model.learnTyped("start","let's","we");
        model.learnTyped("going","let's","i");
        assertEquals("going",model.predict("","let's","we").get(0));
        assertTrue(model.predictPersonal("","let's","we","en").stream()
            .allMatch(w->w.equals("going")||w.equals("start")));
    }
    @Test public void straightAndCurlyApostrophesShareOnePersonalContext() {
        PredictionModel model=new PredictionModel();
        model.learnTyped("go","let's","");
        assertEquals("go",model.predict("","let’s","").get(0));
        assertEquals("go",model.predict("","Let's","").get(0));
    }
    @Test public void freshEnglishContractionContextCannotSuggestGermanDictionaryWords(){
        PredictionModel m=new PredictionModel();
        m.addBaseWord("gehen",1,"de");
        m.addBaseWord("gemütlich",2,"de");
        m.addBaseWord("go",10000,"en");
        m.addBaseWord("good",2000,"en");
        assertEquals("en",LanguageContext.detect("Okay, let's "));
        java.util.List<String> next=m.predict("g","let's","okay",
            java.util.Collections.emptyMap(),"en");
        assertTrue(next.contains("go"));
        assertFalse(next.contains("gehen"));
        assertFalse(next.contains("gemütlich"));
        assertTrue(m.predict("geh","let's","okay",java.util.Collections.emptyMap(),"en").isEmpty());
    }
    @Test public void phraseContinuationUsesExactPersonalHistoryInsteadOfModelGuess(){
        PredictionModel model=new PredictionModel();
        model.learnTyped("go","let's","okay");
        model.learnTyped("home","go","let's");
        java.util.List<String> first=model.predictPersonal("","let's","okay","en");
        java.util.List<String> phrases=PhrasePredictor.complete("Okay, let's ",first,
            (text,limit) -> {
                TextContext c=TextContext.fromBeforeCursor(text);
                if(model.hasPersonalNext("",c.previous))
                    return model.predictPersonal("",c.previous,c.beforePrevious,"en");
                return java.util.List.of("random");
            },(w,p,c) -> WordCase.display(w,p,c,false),3);
        assertEquals(java.util.List.of("go home"),phrases);
    }
    @Test public void emptyMessageUsesSmallNeuralOrCorpusFallbackWithoutLearningIt() {
        PredictionModel model=new PredictionModel();
        BaselineNgrams corpus=new BaselineNgrams();
        corpus.add("S","","","die",999999,"de");
        corpus.add("S","","","the",999999,"en");
        corpus.add("S","","","ich",999999,"de");
        model.addBaseWord("die",0,"de");model.addBaseWord("the",0,"en");
        model.setBaselineNgrams(corpus);
        assertFalse("zero-prefix editor can use tiny local model",model.predict("","","").isEmpty());
        assertTrue(model.predict("","","",java.util.Map.of("invented",999999.0),"en").contains("invented"));
        assertTrue(model.predict("","","",java.util.Map.of("erfunden",999999.0),"de").contains("erfunden"));
        assertFalse(model.hasPersonalNext("",""));
    }
    @Test public void confirmedSentenceStartIsTheOnlyAvailableOpening() {
        PredictionModel m=new PredictionModel();
        m.addBaseWord("The",0,"en");m.addBaseWord("der",0,"de");
        BaselineNgrams corpus=new BaselineNgrams();
        corpus.add("S","","","der",99999,"de");
        corpus.add("S","","","the",99999,"en");
        m.setBaselineNgrams(corpus);
        assertFalse(m.predict("","","").isEmpty());
        PredictionModel.Event first=m.learnTyped("hello","","");
        assertEquals("<bos>",first.previous);
        assertEquals(java.util.List.of("hello"),m.predictPersonal("","", "","de"));
        assertEquals("hello",m.predict("","", "",
            java.util.Map.of("der",999999.0,"the",999999.0),"de").get(0));
        assertEquals("hello",m.predict("","", "",
            java.util.Map.of("der",999999.0,"the",999999.0),"en").get(0));
        assertTrue(m.hasPersonalNext("",""));
        assertEquals("hello",m.predict("he","","").get(0));
        m.undo(first);
        assertFalse(m.predict("","","").isEmpty());
    }
    @Test public void observedSentenceStartsUseFrequencyAndRecentUsageOnly() {
        PredictionModel m=new PredictionModel();
        for(int i=0;i<10;i++)m.learnTyped("Hallo","","");
        m.learnTyped("Guten","","");
        assertEquals("Hallo",m.predict("","","").get(0));
        for(int i=0;i<8;i++)m.learnTyped("Guten","","");
        assertEquals("Guten",m.predict("","","").get(0));
        assertEquals(2,m.predictPersonal("","","","de").size());
        assertEquals(3,m.predict("","","").size());
    }
    @Test public void actualOpeningPhraseOnlyWhenBothWordsWereObserved() {
        PredictionModel m=new PredictionModel();
        m.learnTyped("Hello","","");
        java.util.List<String> beginnings=m.predict("","","");
        assertEquals(java.util.List.of("Hello"),m.predictPersonal("","","","en"));
        PhrasePredictor.Continuations follow=(seed,limit)->{
            TextContext next=TextContext.fromBeforeCursor(seed);
            if(!m.hasPersonalNext("",next.previous))return java.util.Collections.emptyList();
            return m.predict("",next.previous,next.beforePrevious);
        };
        PhrasePredictor.Casing casing=(word,prefix,before)->
            WordCase.display(word,prefix,before,false);
        assertTrue(PhrasePredictor.complete("",beginnings,follow,casing,3).isEmpty());
        m.learnTyped("there","Hello","");
        assertTrue(PhrasePredictor.complete("",beginnings,follow,casing,3)
            .contains("Hello there"));
    }
    @Test public void observedEnglishIAmCanBeSuggestedAtEmptyGermanDefaultField() {
        PredictionModel model=new PredictionModel();
        model.learnTyped("I","","");
        model.learnTyped("am","I","");
        java.util.List<String> starts=model.predict("","", "");
        assertEquals("I",starts.get(0));
        assertTrue(starts.contains("am"));
        PhrasePredictor.Continuations personalOnly=(seed,limit)->{
            TextContext c=TextContext.fromBeforeCursor(seed);
            return model.hasPersonalNext("",c.previous)?
                model.predictPersonal("",c.previous,c.beforePrevious,"en"):java.util.List.of();
        };
        assertEquals(java.util.List.of("I am"),PhrasePredictor.complete("",starts,
            personalOnly,(word,prefix,before)->WordCase.display(word,prefix,before,false),3));
    }
    @Test public void restoringPersonalOpeningUsesExistingPairsSchema() {
        PredictionModel old=new PredictionModel();
        PredictionModel.Event first=old.learnSelection("he","Hello","","");
        old.learnTyped("there","Hello","");
        PredictionModel restored=new PredictionModel();
        restored.restoreWord("Hello",1,1);
        restored.restorePair(first.previous,first.word,1);
        restored.restoreRecent(first.previous,"hello");
        restored.restorePair("hello","there",1);
        assertEquals(java.util.List.of("Hello"),restored.predictPersonal("","","","en"));
        assertEquals(java.util.List.of("there"),restored.predictPersonal("","Hello","","en"));
    }
    @Test public void oldDatabaseWordPairsProduceActualSuggestionsBeforeFirstLetter() {
        PredictionModel model=new PredictionModel();
        model.restoreWord("Let's",12,0,0);
        model.restoreWord("go",12,0,0);
        model.restoreWord("Hello",7,2,0);
        model.restoreWord("world",7,0,0);
        model.restorePair("let's","go",11);
        model.restorePair("hello","world",7);
        BaselineNgrams corpus=new BaselineNgrams();
        corpus.add("S","","","invented",900000,"en");
        model.setBaselineNgrams(corpus);
        java.util.List<String> starts=model.predict("","", "",
            java.util.Map.of("invented",9999999.0),"en");
        assertFalse(starts.isEmpty());
        assertTrue(starts.contains("Let's"));
        assertTrue(starts.contains("Hello"));
        assertFalse(starts.contains("invented"));
        PhrasePredictor.Continuations observed=(seed,limit)->{
            TextContext c=TextContext.fromBeforeCursor(seed);
            return model.hasPersonalNext("",c.previous)?model.predict("",c.previous,c.beforePrevious):
                java.util.List.of();
        };
        java.util.List<String> phrases=PhrasePredictor.complete("",starts,observed,
            (word,prefix,context)->WordCase.display(word,prefix,context,false),3);
        assertTrue(phrases.contains("Let's go"));
        assertTrue(phrases.contains("Hello world"));
    }
    @Test public void oldDatabaseSingleWordsAlsoDisplayButNeverInventPhrases() {
        PredictionModel model=new PredictionModel();
        model.addBaseWord("Einmal",0,"de");
        model.addBaseWord("invented",1,"en");
        model.restoreWord("Einmal",6,0,0);
        assertEquals(java.util.List.of("Einmal"),model.predictPersonal("","", "","de"));
        model.clearLearningData();
        assertTrue(model.predictPersonal("","","","de").isEmpty());
    }
    @Test public void trueSentenceStartsTakePriorityOverLegacyPairs() {
        PredictionModel model=new PredictionModel();
        model.restoreWord("wuerde",100,0,0);
        model.restoreWord("gerne",100,0,0);
        model.restorePair("wuerde","gerne",200);
        model.learnTyped("Hallo","","");
        java.util.List<String> starts=model.predict("","", "");
        assertEquals("Hallo",starts.get(0));
        assertTrue(starts.contains("wuerde"));
        assertFalse(starts.contains("invented"));
    }
    @Test public void sentenceInitialNowIsLowercaseWhenSuggestedMidSentence() {
        PredictionModel model=new PredictionModel();
        model.addBaseWord("now",71,"en");
        model.learnTyped("Now","let's","");
        java.util.List<String> ranked=model.predict("","let's","",
            java.util.Collections.emptyMap(),"en");
        assertEquals("now",ranked.get(0));
        assertEquals("now",WordCase.display(ranked.get(0),"","I think ",false));
        assertEquals("Now",WordCase.display(ranked.get(0),"","I think. ",false));
        assertEquals("Now",WordCase.display(ranked.get(0),"","",false));
        assertEquals("NOW",model.predict("NOW","let's","",
            java.util.Collections.emptyMap(),"en").get(0));
        assertEquals("Now",model.predict("No","let's","",
            java.util.Collections.emptyMap(),"en").get(0));
    }
    @Test public void casingOfPersonalVocabularyUsesCorrectLanguageLexicon() {
        PredictionModel model=new PredictionModel();
        model.addBaseWord("die",57,"en");
        model.addBaseWord("die",2,"de");
        model.learnTyped("Die","can","");
        assertEquals("die",model.predict("","can","",
            java.util.Collections.emptyMap(),"en").get(0));
        model.addBaseWord("Haus",20,"de");
        model.learnTyped("haus","das","");
        assertEquals("Haus",model.predict("","das","",
            java.util.Collections.emptyMap(),"de").get(0));
        // A name not in the base vocabulary preserves personally chosen case.
        model.learnTyped("Alice","with","I");
        assertEquals("Alice",model.predict("","with","I",
            java.util.Collections.emptyMap(),"en").get(0));
    }
    @Test public void onlyOneRecordedNextWordStillHasThreeUsefulCandidates() {
        PredictionModel model=new PredictionModel();
        model.addBaseWord("I",1,"en");
        model.addBaseWord("think",20,"en");
        model.addBaseWord("go",120,"en");
        model.learnTyped("go","let's","okay");
        BaselineNgrams corpus=new BaselineNgrams();
        corpus.add("B","","let's","going",200,"en");
        corpus.add("B","","let's","good",190,"en");
        model.setBaselineNgrams(corpus);
        java.util.List<String> results=model.predict("","let's","okay",
            java.util.Map.of("going",60.0,"good",55.0),"en");
        assertEquals(3,results.size());
        assertEquals("go",results.get(0));
        assertEquals(3,new java.util.HashSet<>(results).size());
        assertFalse(model.predictPersonal("","let's","okay","en").contains("going"));
    }
    @Test public void modelFillsUnobservedNextWordWithoutUpdatingPersonalHistory() {
        PredictionModel model=new PredictionModel();
        model.learnTyped("jetzt","bin","");
        for(int i=0;i<4;i++) {
            java.util.List<String> results=model.predict("","bin","ich",
                java.util.Map.of("hier",60.0,"nicht",55.0,"gerne",50.0),"de");
            assertEquals(3,results.size());
            assertEquals("jetzt",results.get(0));
            assertEquals(java.util.List.of("jetzt"),model.predictPersonal("","bin","ich","de"));
        }
    }
    @Test public void noPersonalHistoryAndEmptyFieldStillHasThreeFallbackWords() {
        PredictionModel model=new PredictionModel();
        assertEquals(3,model.predict("","","",java.util.Collections.emptyMap(),"de").size());
        assertEquals(3,model.predict("","","",java.util.Collections.emptyMap(),"en").size());
        assertTrue(model.predictPersonal("","","","de").isEmpty());
    }
    @Test public void genericEnglishStarterCannotFabricateQuickPhraseAfterLets() {
        PredictionModel m=new PredictionModel();
        m.learnTyped("go","let's","");
        m.learnTyped("I","","");
        m.learnTyped("am","I","");
        java.util.List<String> all=m.predict("","let's","",
            java.util.Collections.emptyMap(),"en");
        assertEquals(3,all.size());
        assertEquals("go",all.get(0));
        java.util.List<String> actualFirst=m.predictPersonal("","let's","","en");
        assertEquals(java.util.List.of("go"),actualFirst);
        java.util.List<String> quick=PhrasePredictor.complete("Let's ",actualFirst,
            (seed,n)->{
                TextContext c=TextContext.fromBeforeCursor(seed);
                return m.predictPersonal("",c.previous,c.beforePrevious,"en");
            },(word,prefix,context)->WordCase.display(word,prefix,context,false),3);
        assertFalse(quick.contains("I am"));
        assertTrue(quick.isEmpty());
    }
    @Test public void longOrBlankTokensDoNotLearn() {
        PredictionModel m=new PredictionModel();
        assertNull(m.learnTyped("", "a", "b"));
        assertNull(m.learnTyped("a".repeat(61),"a","b"));
    }
}
