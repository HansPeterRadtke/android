package com.hans.android.adaptivekeyboard;
import org.junit.Test;
import static org.junit.Assert.*;
public final class CursorNavigationTest {
    @Test public void moveWithinJoinedWordsAndClampAtEdges() {
        String s="ichmöchte";
        assertEquals(3,CursorNavigator.step(s,4,-1));
        assertEquals(5,CursorNavigator.step(s,4,1));
        assertEquals(0,CursorNavigator.step(s,0,-1));
        assertEquals(s.length(),CursorNavigator.step(s,s.length(),1));
    }
    @Test public void surrogateEmojiNotSplit() {
        String s="ab😀cd";
        assertEquals(4,CursorNavigator.step(s,2,1));
        assertEquals(2,CursorNavigator.step(s,4,-1));
    }
    @Test public void combiningAccentIsSingleCharacterWhenMoving() {
        String s="cafe\u0301!";
        assertEquals(5,CursorNavigator.step(s,3,1));
        assertEquals(3,CursorNavigator.step(s,5,-1));
    }
    @Test public void holdingSpaceAndMovingInBothDirectionsDoesNotTypeSpaces() {
        SpaceSwipe drag=new SpaceSwipe(150);
        assertEquals(0,drag.move(180,12));
        drag.activate(180);
        assertEquals(2,drag.move(209,12));
        assertEquals(-1,drag.move(190,12));
        assertEquals(-4,drag.move(142,12));
        assertTrue(drag.active());
    }
    @Test public void fractionalMovementAccumulates() {
        SpaceSwipe drag=new SpaceSwipe(0);drag.activate(0);
        assertEquals(0,drag.move(5,12));
        assertEquals(1,drag.move(12,12));
        assertEquals(0,drag.move(18,12));
        assertEquals(-1,drag.move(0,12));
    }
}
