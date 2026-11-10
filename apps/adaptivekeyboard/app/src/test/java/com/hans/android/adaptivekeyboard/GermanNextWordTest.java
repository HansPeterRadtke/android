package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/** Checkpoint verified against the author's NumPy example, independent of Android. */
public final class GermanNextWordTest {
    private static final Path FILE=Path.of("src/main/assets/german_next_word.nwp");
    private GermanNextWord model() throws Exception {return new GermanNextWord(new FileInputStream(FILE.toFile()));}
    private List<String> suggestions(GermanNextWord model,String before,int k) {
        return model.predict(before,k).stream().map(c->c.word).collect(Collectors.toList());
    }
    @Test public void germanCheckpointLoadsAndMatchesReference() throws Exception {
        GermanNextWord model=model();
        assertEquals(23990,model.vocabularySize());
        assertEquals(0.7f,model.blendWeight(),0.0001f);
        assertEquals(List.of("mich","mir","die","das","es","eine","ein","einen"),
            suggestions(model,"ich habe ",8));
        assertEquals(List.of("nicht","ein","sehr","mit","mir","in"),
            suggestions(model,"ich bin ",6));
        assertEquals(List.of("wissen","mal","eine","mit","ein"),
            suggestions(model,"ich würde gerne ",5));
    }
    @Test public void twentyWordContextAndPrefixAreApplied() throws Exception {
        GermanNextWord model=model();
        assertTrue(suggestions(model,"ich ha",10).contains("habe"));
        assertTrue(suggestions(model,"Ich ha",10).contains("habe"));
        for(String candidate:suggestions(model,"ich würde gerne wis",7))assertTrue(candidate.startsWith("wis"));
        assertEquals(suggestions(model,"Ich habe. ich bin ",5),suggestions(model,"ich bin ",5));
    }
    @Test public void handlesGermanUmlautKeyboardAlternativesInContext() throws Exception {
        GermanNextWord model=model();
        assertEquals("würde",model.canonicalGerman("wuerde"));
        assertEquals("möchte",model.canonicalGerman("moechte"));
        assertEquals("für",model.canonicalGerman("fuer"));
        assertEquals("wollen",model.canonicalGerman("wollen"));
        assertEquals(suggestions(model,"ich würde gerne ",6),
                     suggestions(model,"ich wuerde gerne ",6));
        assertEquals(suggestions(model,"ich möchte ",6),
                     suggestions(model,"ich moechte ",6));
    }
    @Test public void corruptedModelIsRejected() throws Exception {
        byte[] source=Files.readAllBytes(FILE);
        source[1000]^=1;
        try {new GermanNextWord(new ByteArrayInputStream(source));fail("corrupt checkpoint was accepted");}
        catch(IOException expected) {assertTrue(expected.getMessage().contains("checksum"));}
    }

    @Test public void predictsFromTheSameWordsAcrossNonFinalPunctuation() throws Exception {
        GermanNextWord model = model();
        List<String> afterRequest = suggestions(model, "ich würde gerne ", 5);
        assertEquals(afterRequest, suggestions(model, "ich würde gerne... ", 5));
        assertEquals(afterRequest, suggestions(model, "ich würde gerne… ", 5));
        assertEquals(suggestions(model, "ich würde gerne wis", 5),
                suggestions(model, "ich würde gerne... wis", 5));
        assertEquals(suggestions(model, "ich frage Dr Müller ", 5),
                suggestions(model, "ich frage Dr. Müller ", 5));
        assertEquals(suggestions(model, "das kostet Euro ", 5),
                suggestions(model, "das kostet 3.50 Euro ", 5));
        assertEquals(suggestions(model, "besuche beispiel de", 5),
                suggestions(model, "besuche beispiel.de", 5));
    }
}
