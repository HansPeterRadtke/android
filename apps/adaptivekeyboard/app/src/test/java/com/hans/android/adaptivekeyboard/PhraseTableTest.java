package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class PhraseTableTest {
    private final PhrasePredictor.Casing plain=(word,prefix,left)->word;
    private PhraseTable table(String language) throws Exception {
        return new PhraseTable(Files.newInputStream(Path.of("src/main/assets/"+language+"_phrase_pairs.ngram")),language);
    }
    @Test public void pronounInversionDependsOnThePrecedingWords() throws Exception {
        PhraseTable en=table("en");
        for(String before:List.of("Ok , we ","Ok, we ","we ")) {
            List<String> phrases=en.complete(before,List.of("are","have","can"),plain,3);
            assertEquals(3,phrases.size());
            assertFalse(phrases.contains("are you"));
            assertTrue(phrases.stream().anyMatch(p->p.startsWith("are ")));
        }
        assertTrue(en.complete("Where ",List.of("are"),plain,3).contains("are you"));
    }
    @Test public void exactPersonalSequenceWinsWithoutSplicingPairs() {
        PredictionModel p=new PredictionModel();
        p.learnTyped("are","we","");p.learnTyped("you","are","");
        TextContext seed=TextContext.fromBeforeCursor("Ok, we are ");
        assertTrue(p.personalPhraseNext(seed,"en").isEmpty());
        p.learnTyped("ready","are","we");
        assertEquals(List.of("ready"),p.personalPhraseNext(seed,"en"));
        assertTrue(p.personalPhraseNext(TextContext.fromBeforeCursor("are "),"en").contains("you"));
    }
    @Test public void longerContextsPrefixesAndQuestionBoundaries() throws Exception {
        PhraseTable en=table("en"),de=table("de");
        assertEquals("en",LanguageContext.detect("Ok , we"));
        assertEquals("de",LanguageContext.detect("Ich we"));
        assertTrue(en.complete("Ok , we",List.of("we"),plain,3).stream().allMatch(s->s.startsWith("we ")));
        assertFalse(en.complete("Ok , we",List.of("we"),plain,3).isEmpty());
        assertTrue(en.complete("as soon as ",List.of(),plain,3).contains("i can"));
        assertTrue(de.complete("Wo ",List.of("bist"),plain,3).contains("bist du"));
        assertFalse(de.complete("Wir ",List.of("sind"),plain,3).contains("sind du"));
        assertTrue(de.complete("Ich freue mich ",List.of("auf"),plain,3).stream().anyMatch(s->s.startsWith("auf ")));
        assertEquals(en.complete("We ",List.of("are"),plain,3),en.complete("Are you ready? We ",List.of("are"),plain,3));
        assertTrue(en.complete("",List.of("we"),plain,3).isEmpty());
    }
}
