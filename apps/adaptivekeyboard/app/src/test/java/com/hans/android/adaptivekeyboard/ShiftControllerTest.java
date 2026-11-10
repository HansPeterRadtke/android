package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShiftControllerTest {
    @Test public void shiftMakesEverySuggestedWordStartWithCapital() {
        ShiftController s=new ShiftController();
        s.press(1000);
        assertEquals(ShiftController.Mode.ONCE,s.mode());
        assertEquals("Now",s.applyWord("now"));
        assertEquals("Haus",s.applyWord("haus"));
        assertEquals("Let's",s.applyWord("let's"));
        assertEquals("I Am Ready",s.applyPhrase("i am ready"));
        assertEquals("Ich Möchte Wissen",s.applyPhrase("ich möchte wissen"));
        s.consumeSuggestion();
        assertEquals(ShiftController.Mode.LOWER,s.mode());
        assertEquals("now",s.applyWord("now"));
    }
    @Test public void doubleTapLocksUppercaseUntilShiftTappedAgain() {
        ShiftController s=new ShiftController();
        s.press(1200);s.press(1480);
        assertTrue(s.locked());
        assertEquals("NOW",s.applyWord("now"));
        assertEquals("ICH MÖCHTE",s.applyPhrase("ich möchte"));
        s.consumeLetter();s.consumeSuggestion();
        assertTrue(s.locked());
        s.press(1600);s.automatic(true);
        assertEquals(ShiftController.Mode.MANUAL_LOWER,s.mode());
        assertFalse(s.active());
        assertEquals("now",s.applyWord("now"));
    }
    @Test public void nonDoubleShiftPressTogglesOff() {
        ShiftController s=new ShiftController();
        s.press(1000);s.press(2000);
        assertFalse(s.active());
    }
    @Test public void shiftAtAnAutoCapitalizedStartTitlesSuggestions() {
        ShiftController s=new ShiftController();
        s.reset(true);
        s.press(1000);s.automatic(false);
        assertEquals(ShiftController.Mode.ONCE,s.mode());
        assertTrue(s.active());
        assertEquals("Hello",s.applyWord("hello"));
        assertEquals("Ich Möchte",s.applyPhrase("ich möchte"));
        s.consumeLetter();
        assertFalse(s.active());
    }
    @Test public void slowerSecondTapMakesBothSuggestionsAndKeysLowercase() {
        ShiftController s=new ShiftController();
        s.reset(true);
        s.press(1000);s.press(1600);s.automatic(true);
        assertFalse(s.active());
        assertEquals("hello",s.applyWord("Hello"));
        assertEquals("ich Möchte",s.applyPhrase("Ich Möchte"));
        s.consumeSuggestion();s.automatic(true);
        assertEquals(ShiftController.Mode.AUTO,s.mode());
    }
    @Test public void doubleTapFromAutomaticShiftAlsoLocksCaps() {
        ShiftController s=new ShiftController();
        s.reset(true);
        s.press(1000);
        s.press(1200);
        assertTrue(s.locked());
        assertEquals("LET'S GO",s.applyPhrase("let's go"));
    }
    @Test public void editorResetPreventsStaleShiftAndCapsLock() {
        ShiftController s=new ShiftController();
        s.press(1000);s.press(1200);
        s.reset(false);
        assertEquals(ShiftController.Mode.LOWER,s.mode());
        s.press(1500);
        assertEquals(ShiftController.Mode.ONCE,s.mode());
    }
}
