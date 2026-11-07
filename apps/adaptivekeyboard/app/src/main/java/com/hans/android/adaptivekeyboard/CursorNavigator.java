package com.hans.android.adaptivekeyboard;

import android.os.Build;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

/**
 * Bidirectional cursor movement. One editor snapshot per continuous gesture,
 * not a slow getSurroundingText round-trip for every touch movement.
 */
public final class CursorNavigator {
    private CursorNavigator() { }

    public static int step(String content,int index,int direction) {
        int position=Math.max(0,Math.min(content.length(),index));
        if(direction==0)return position;
        if(direction>0) {
            if(position>=content.length())return content.length();
            position=content.offsetByCodePoints(position,1);
            while(position<content.length()) {
                int cp=content.codePointAt(position);
                if(Character.getType(cp)!=Character.NON_SPACING_MARK && cp!=0xFE0F)break;
                position=content.offsetByCodePoints(position,1);
            }
        } else {
            if(position<=0)return 0;
            position=content.offsetByCodePoints(position,-1);
            while(position>0) {
                int cp=content.codePointAt(position);
                if(Character.getType(cp)!=Character.NON_SPACING_MARK && cp!=0xFE0F)break;
                position=content.offsetByCodePoints(position,-1);
            }
        }
        return position;
    }

    /**
     * Snapshot cursor, using offsets relative to text and clamping both ends.
     * A null session text means to use DPAD as a compatibility fallback.
     */
    public static final class Gesture {
        private final String text;
        private final int offset;
        private final int start, end;
        private int relative;
        private boolean started, fallback;
        private int movedLeft,movedRight;

        public Gesture(String text,int offset,int selectionStart,int selectionEnd) {
            boolean valid=text!=null && offset>=0 && selectionStart>=0 && selectionEnd>=0 &&
                selectionStart<=text.length() && selectionEnd<=text.length();
            this.text=valid?text:null;
            this.offset=valid?offset:-1;
            this.start=valid?Math.min(selectionStart,selectionEnd):0;
            this.end=valid?Math.max(selectionStart,selectionEnd):0;
            relative=this.end;
            fallback=!valid;
        }
        public boolean hasSnapshot() {return !fallback && text!=null;}
        public int position() {return hasSnapshot()?offset+relative:-1;}
        public int leftMoves(){return movedLeft;}
        public int rightMoves(){return movedRight;}
        public boolean usedFallback(){return fallback;}
        private void count(int delta) {
            if(delta<0)movedLeft+=-delta;
            else if(delta>0)movedRight+=delta;
        }
        private void fallback() {fallback=true;}
        /** Pure operation; useful in unit tests independent of Android views. */
        public int advance(int byCharacters) {
            if(!hasSnapshot() || byCharacters==0)return position();
            if(!started && start!=end)relative=byCharacters<0?start:end;
            started=true;
            int old=relative;
            int sign=Integer.signum(byCharacters);
            for(int i=0;i<Math.min(40,Math.abs(byCharacters));i++)
                relative=step(text,relative,sign);
            count((sign>0?1:-1)*Math.abs(relative-old));
            return position();
        }
        private boolean mayHaveMore(int sign) {
            if(!hasSnapshot())return false;
            // An actual editor edge is not a movement failure. Only a cropped
            // snapshot needs a fallback beyond its captured boundary.
            return sign<0?relative==0 && offset>0:
                relative==text.length() && (text.length()-end)>=512;
        }
    }

    public static Gesture begin(InputConnection ic) {
        if(ic==null)return new Gesture(null,-1,0,0);
        if(Build.VERSION.SDK_INT>=31) {
            try {
                SurroundingText around=ic.getSurroundingText(512,512,0);
                if(around!=null && around.getText()!=null && around.getOffset()>=0) {
                    Gesture g=new Gesture(around.getText().toString(),around.getOffset(),
                        around.getSelectionStart(),around.getSelectionEnd());
                    if(g.hasSnapshot())return g;
                }
            } catch(Exception ignored) { /* Nonstandard editor: try extracted text. */ }
        }
        try {
            ExtractedText e=ic.getExtractedText(new ExtractedTextRequest(),0);
            if(e!=null && e.text!=null && e.startOffset>=0 && e.selectionStart>=0 &&
                e.selectionEnd>=0) {
                Gesture g=new Gesture(e.text.toString(),e.startOffset,
                    e.selectionStart-e.startOffset,e.selectionEnd-e.startOffset);
                if(g.hasSnapshot())return g;
            }
        } catch(Exception ignored) { /* Some editors expose no text. */ }
        return new Gesture(null,-1,0,0);
    }

    private static boolean sendArrow(InputConnection ic,int delta) {
        if(ic==null||delta==0)return false;
        int key=delta<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean sent=false;
        for(int i=0;i<Math.min(40,Math.abs(delta));i++) {
            try {
                boolean down=ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));
                boolean up=ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));
                sent|=down||up;
            } catch(Exception invalid) {return sent;}
        }
        return sent;
    }

    /** Handle the current motion; right and left are truly symmetrical. */
    public static boolean move(InputConnection ic,Gesture gesture,int byCharacters) {
        if(ic==null||byCharacters==0)return false;
        if(gesture==null || !gesture.hasSnapshot())return sendArrow(ic,byCharacters);
        int before=gesture.position();
        int target=gesture.advance(byCharacters);
        if(target!=before) {
            try {if(ic.setSelection(target,target))return true;}
            catch(Exception ignored) { /* Use key events for unsupported editors. */ }
            gesture.fallback();
            return sendArrow(ic,byCharacters);
        }
        if(gesture.mayHaveMore(Integer.signum(byCharacters))) {
            gesture.fallback();
            return sendArrow(ic,byCharacters);
        }
        return false; // Already at start or end of the real editor text.
    }

    public static boolean move(InputConnection ic,int byCharacters) {
        return move(ic,begin(ic),byCharacters);
    }

    public static boolean collapseToEnd(InputConnection ic) {
        if(ic==null)return false;
        if(Build.VERSION.SDK_INT>=31) {
            try {
                SurroundingText around=ic.getSurroundingText(256,256,0);
                if(around!=null && around.getOffset()>=0) {
                    int target=around.getOffset()+Math.max(around.getSelectionStart(),around.getSelectionEnd());
                    if(ic.setSelection(target,target))return true;
                }
            } catch(Exception ignored) { /* Try extracted cursor position. */ }
        }
        try {
            ExtractedText e=ic.getExtractedText(new ExtractedTextRequest(),0);
            return e!=null && e.selectionStart>=0 && e.selectionEnd>=0 &&
                ic.setSelection(Math.max(e.selectionStart,e.selectionEnd),
                    Math.max(e.selectionStart,e.selectionEnd));
        } catch(Exception ignored) {return false;}
    }
}
