package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.FileInputStream;

public final class GermanCaseModelTest {
    private GermanCaseModel model() throws Exception {
        return new GermanCaseModel(new FileInputStream("src/main/assets/de_word_case.gcase"));
    }
    @Test public void verbVersusNounDependsOnContextNotDictionarySpelling() throws Exception {
        GermanCaseModel m=model();
        assertTrue(m.wordCount()>20000);
        assertEquals("wissen",m.apply("Wissen","","ich würde gerne ",false));
        assertEquals("Wissen",m.apply("wissen","","mein ",false));
        assertEquals("Haus",m.apply("haus","","ich gehe nach ",false));
        assertEquals("essen",m.apply("Essen","","ich möchte gerne ",false));
        assertEquals("Essen",m.apply("essen","","das ",false));
        assertEquals("Morgen",m.apply("morgen","","guten ",false));
        assertEquals("morgen",m.apply("Morgen","","bis ",false));
    }
    @Test public void typedCapitalizationOverridesCorpusModel() throws Exception {
        GermanCaseModel m=model();
        assertEquals("wIssEN",m.apply("Wissen","wIssEN","ich möchte ",false));
        assertEquals("wissen",m.apply("Wissen","wissen","mein ",false));
        assertEquals("Wissen",m.apply("wissen","","ich ",true));
    }
}
