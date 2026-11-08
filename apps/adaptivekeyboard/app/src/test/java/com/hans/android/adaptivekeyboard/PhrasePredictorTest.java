package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class PhrasePredictorTest {
    private static PhrasePredictor.Casing caps= (word,prefix,before) ->
        WordCase.display(word,prefix,before,false);

    @Test public void multiwordSuggestionsReplaceHintAndFollowSentenceContext() {
        List<String> result=PhrasePredictor.complete("ich möchte ",
            List.of("wissen","einen","gerne"),
            (text,limit) -> text.endsWith("wissen ")?List.of("was","es"):
                text.endsWith("einen ")?List.of("Termin","treffen"):
                List.of("nach","mehr"),caps,3);
        assertEquals(List.of("wissen was","einen Termin","gerne nach"),result);
        assertArrayEquals(new String[]{"wissen","was"},PhrasePredictor.words(result.get(0)));
    }
    @Test public void preserveExactTypedCaseEvenInsideAJoinedWord() {
        List<String> result=PhrasePredictor.complete("ich ha",
            List.of("habe","Haus"),
            (text,limit) -> List.of("heute","viel"),caps,3);
        assertEquals("habe heute",result.get(0));
        assertEquals("haus heute",result.get(1));
        assertEquals("HAUS heute",PhrasePredictor.complete("ich HA",
            List.of("Haus"),(text,limit)->List.of("heute"),caps,1).get(0));
    }
    @Test public void noRepeatedOrInvalidCompletion() {
        List<String> result=PhrasePredictor.complete("ich will ",
            List.of("gehen","gehen","<unk>","a"),
            (text,limit)->List.of("gehen","<sym>","jetzt"),caps,3);
        assertEquals(1,result.size());
        assertEquals("gehen jetzt",result.get(0));
        assertArrayEquals(new String[0],PhrasePredictor.words("one"));
        assertFalse(PhrasePredictor.validWord("<unk>"));
    }
    @Test public void onePersonalFirstWordCanProduceThreeDistinctPhrases(){
        List<String> phrases=PhrasePredictor.complete("I am ",List.of("going"),
            (ctx,limit)->List.of("to","home","there","to"),caps,3);
        assertEquals(List.of("going to","going home","going there"),phrases);
    }
    @Test public void twoFirstWordsAreMixedBeforeExtraVariants(){
        List<String> phrases=PhrasePredictor.complete("Ich möchte ",
            List.of("gerne","heute"),(ctx,limit)->
                ctx.endsWith("gerne ")?List.of("wissen","fahren","lernen"):
                List.of("gehen","essen","kommen"),caps,3);
        assertEquals(List.of("gerne wissen","heute gehen","gerne fahren"),phrases);
    }
    @Test public void doesNotInferFromInvalidInput() {
        assertTrue(PhrasePredictor.complete("",null,(s,k)->List.of("x"),caps,3).isEmpty());
        assertTrue(PhrasePredictor.complete("",List.of("Haus"),(s,k)->List.of(),caps,3).isEmpty());
        assertArrayEquals(new String[0],PhrasePredictor.words("word 123"));
    }
}
