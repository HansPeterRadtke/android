package com.hans.android.adaptivekeyboard;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public final class SuggestionMixerTest {
    @Test public void onePersonalWordCanBeCompletedToThreeWithoutLosingPriority() {
        assertEquals(List.of("go","going","good"),SuggestionMixer.merge(
            List.of("go"),List.of("going","go","good","gone"),3,"g"));
    }
    @Test public void twoPersonalWordsOutrankMoreConfidentNeuralSuggestions() {
        assertEquals(List.of("jetzt","morgen","heute"),SuggestionMixer.merge(
            List.of("jetzt","morgen"),List.of("heute","jetzt","so"),3,""));
    }
    @Test public void sameWordWithDifferentCaseCannotTakeTwoSlots() {
        assertEquals(List.of("Now","next","not"),SuggestionMixer.merge(
            List.of("Now"),List.of("now","next","not"),3,"n"));
        assertEquals(List.of("Let's","later","look"),SuggestionMixer.merge(
            List.of("Let's"),List.of("let’s","later","look"),3,"l"));
    }
    @Test public void coldStartOffersCommonWordsWithoutNeuralStartupDelay() {
        assertEquals(3,SuggestionMixer.coldStart("","de",true).size());
        assertEquals(3,SuggestionMixer.coldStart("","en",false).size());
        assertEquals("Ich",SuggestionMixer.coldStart("","de",true).get(0));
        assertEquals("i",SuggestionMixer.coldStart("","en",false).get(0));
        assertTrue(SuggestionMixer.coldStart("zzzzzz","en",false).isEmpty());
    }
    @Test public void oneEditTypoCorrectionIsStillAvailable(){
        assertEquals(List.of("tastatur"),SuggestionMixer.merge(
            List.of(),List.of("tastatur"),3,"tastatru"));
    }
    @Test public void variantsMustAgreeWithWhatUserAlreadyTyped() {
        assertEquals(List.of("größer","grün"),SuggestionMixer.merge(
            List.of("größer"),List.of("grün","ganz"),3,"gr"));
    }
}
