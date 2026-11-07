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
    @Test public void oneGestureMovesLeftThenRightWithoutResnapshot() {
        CursorNavigator.Gesture gesture=new CursorNavigator.Gesture("abcdefghijklmnopqrstuvwx",0,24,24);
        assertEquals(16,gesture.advance(-8));
        assertEquals(22,gesture.advance(6));
        assertEquals(24,gesture.advance(5));
        assertEquals(23,gesture.advance(-1));
        assertEquals(9,gesture.leftMoves());
        assertEquals(8,gesture.rightMoves());
        assertTrue(gesture.hasSnapshot());
    }
    @Test public void rightGestureMovesFromInsideWordToEndAndClamps() {
        CursorNavigator.Gesture gesture=new CursorNavigator.Gesture("ichmoechte",100,3,3);
        assertEquals(106,gesture.advance(3));
        assertEquals(110,gesture.advance(20));
        assertEquals(110,gesture.advance(1));
        assertEquals(103,gesture.advance(-7));
        assertEquals(100,gesture.advance(-40));
        assertEquals(110,gesture.advance(40));
    }
    @Test public void invalidOffsetFallsBackInsteadOfSendingNegativeSelection() {
        CursorNavigator.Gesture unknown=new CursorNavigator.Gesture("abcdef",-1,3,3);
        assertFalse(unknown.hasSnapshot());
        assertEquals(-1,unknown.advance(3));
        CursorNavigator.Gesture invalidSelection=new CursorNavigator.Gesture("abcdef",100,500,500);
        assertFalse(invalidSelection.hasSnapshot());
        assertEquals(-1,invalidSelection.position());
    }
    @Test public void snapshottedSelectionCollapsesAtDirectionEnd() {
        CursorNavigator.Gesture left=new CursorNavigator.Gesture("abcdefghij",0,2,8);
        assertEquals(1,left.advance(-1));
        CursorNavigator.Gesture right=new CursorNavigator.Gesture("abcdefghij",0,2,8);
        assertEquals(9,right.advance(1));
    }
    @Test public void touchAccumulatorHandlesDirectionReversalsWithoutBias() {
        SpaceSwipe swipe=new SpaceSwipe(180);
        swipe.activate(180);
        assertEquals(-4,swipe.move(132,12));
        assertEquals(3,swipe.move(168,12));
        assertEquals(1,swipe.move(180,12));
        assertEquals(0,swipe.move(184,12));
        assertEquals(-1,swipe.move(168,12));
    }
    @Test public void fractionalMovementAccumulates() {
        SpaceSwipe drag=new SpaceSwipe(0);drag.activate(0);
        assertEquals(0,drag.move(5,12));
        assertEquals(1,drag.move(12,12));
        assertEquals(0,drag.move(18,12));
        assertEquals(-1,drag.move(0,12));
    }
}
