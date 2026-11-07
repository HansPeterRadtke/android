package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public final class KeyPriorityTest {
    @Test public void GermanFavoritesAreFirstAndShiftAware(){
        assertEquals("ä",KeyPriority.favorite("a",false));
        assertEquals("ö",KeyPriority.favorite("o",false));
        assertEquals("ü",KeyPriority.favorite("u",false));
        assertEquals("ß",KeyPriority.favorite("s",false));
        assertEquals("Ä",KeyPriority.favorite("a",true));
        assertEquals("Ö",KeyPriority.favorite("o",true));
        assertEquals("Ü",KeyPriority.favorite("u",true));
        assertEquals("ẞ",KeyPriority.favorite("s",true));
        assertNull(KeyPriority.favorite("x",false));
    }
    @Test public void AlternativeMenuRetainsRareAccentsAsSecondaryOptions(){
        assertArrayEquals(new String[]{"ä","á","à","â","ã","å","æ"},KeyPriority.alternatives("a",false));
        assertEquals("Ä",KeyPriority.alternatives("a",true)[0]);
        assertEquals("ẞ",KeyPriority.alternatives("s",true)[0]);
        assertNull(KeyPriority.alternatives("x",false));
    }
    @Test public void HoldingTwoSecondsProducesFavoriteWithoutExtraClicks(){
        assertTrue(KeyPriority.longHold(2000,0,0));
        assertTrue(KeyPriority.longHold(430,0,0));
        assertFalse(KeyPriority.longHold(120,0,0));
        assertTrue(KeyPriority.longHold(90,36,34));
        assertFalse(KeyPriority.longHold(90,36,12));
    }
}
