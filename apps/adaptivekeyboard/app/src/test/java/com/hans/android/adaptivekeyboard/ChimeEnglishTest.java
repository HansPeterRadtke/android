package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

public class ChimeEnglishTest {
    private ChimeEnglish model() throws Exception {
        return new ChimeEnglish(new FileInputStream("src/main/assets/chime_en.ltc"));
    }
    private static List<String> words(ChimeEnglish model,String prompt) {
        return model.predict(prompt,6).stream().map(c->c.word).collect(Collectors.toList());
    }
    @Test public void referenceModelPredictionsMatchPublisherGolden() throws Exception {
        ChimeEnglish m=model();
        assertEquals(List.of("later","soon","then","tomorrow","at","on"),words(m,"see you "));
        assertEquals(List.of("not","at","in","going","looking","so"),words(m,"I am "));
        assertEquals(List.of("words","world","word","worries","works","workplace"),words(m,"hello wor"));
    }
    @Test public void supportsContractionsAndCachesPrefixOnly() throws Exception {
        ChimeEnglish m=model();
        assertEquals("don't",ChimeEnglish.activePrefix("I don't"));
        assertEquals("don't",ChimeEnglish.activePrefix("you don't"));
        assertEquals("",ChimeEnglish.activePrefix("I am "));
        assertEquals(words(m,"hello wor"),words(m,"hello wor"));
        assertFalse(words(m,"I want to ").isEmpty());
    }
    @Test public void corruptModelIsRejectedNotSilentlyUsed() throws Exception {
        byte[] bytes=Files.readAllBytes(new File("src/main/assets/chime_en.ltc").toPath());
        bytes[1024]++;
        try {new ChimeEnglish(new ByteArrayInputStream(bytes)); fail("corrupt model accepted");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("checksum"));}
    }
    @Test public void offerableWordsExcludeSpecialPunctuation() {
        assertTrue(ChimeEnglish.isOfferable("thank"));
        assertTrue(ChimeEnglish.isOfferable("don't"));
        assertTrue(ChimeEnglish.isOfferable("i"));
        assertFalse(ChimeEnglish.isOfferable("?"));
        assertFalse(ChimeEnglish.isOfferable("<unk>"));
        assertFalse(ChimeEnglish.isOfferable("🔥"));
    }
}
