package com.hans.android.adaptivekeyboard;

import android.os.Build;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

/** Editable cursor movement via InputConnection, respecting surrogate pairs. */
public final class CursorNavigator {
    private CursorNavigator() { }

    public static int step(String content,int index,int direction) {
        int position=Math.max(0,Math.min(content.length(),index));
        if(direction==0)return position;
        if(direction>0) {
            if(position>=content.length())return content.length();
            position=content.offsetByCodePoints(position,1);
            // Skip combining marks and variation selectors attached to the previous glyph.
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

    /** Move in character increments, never move the editor's text itself. */
    public static boolean move(InputConnection ic,int byCharacters) {
        if(ic==null || byCharacters==0)return false;
        int steps=Math.min(40,Math.abs(byCharacters));
        int sign=Integer.signum(byCharacters);
        if(Build.VERSION.SDK_INT>=31) {
            SurroundingText around=ic.getSurroundingText(3000,3000,0);
            if(around!=null && around.getText()!=null) {
                String content=around.getText().toString();
                int relative=sign<0?Math.min(around.getSelectionStart(),around.getSelectionEnd()):
                    Math.max(around.getSelectionStart(),around.getSelectionEnd());
                relative=Math.max(0,Math.min(content.length(),relative));
                for(int i=0;i<steps;i++)relative=step(content,relative,sign);
                int absolute=around.getOffset()+relative;
                if(ic.setSelection(absolute,absolute))return true;
            }
        }
        ExtractedText extracted=ic.getExtractedText(new ExtractedTextRequest(),0);
        if(extracted!=null && extracted.text!=null) {
            String content=extracted.text.toString();
            // ExtractedText.selectionStart/End are absolute offsets in the editor.
            int absolute=sign<0?Math.min(extracted.selectionStart,extracted.selectionEnd):
                Math.max(extracted.selectionStart,extracted.selectionEnd);
            int relative=Math.max(0,Math.min(content.length(),absolute-extracted.startOffset));
            for(int i=0;i<steps;i++)relative=step(content,relative,sign);
            int target=extracted.startOffset+relative;
            if(ic.setSelection(target,target))return true;
        }
        // Some nonstandard editors do not implement selection APIs. Fall back
        // to proper DPAD down/up pairs rather than changing any text.
        int key=sign<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;
        for(int i=0;i<steps;i++) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));
        }
        return true;
    }

    public static boolean collapseToEnd(InputConnection ic) {
        if(ic==null)return false;
        if(Build.VERSION.SDK_INT>=31) {
            SurroundingText around=ic.getSurroundingText(1000,1000,0);
            if(around!=null) {
                int position=around.getOffset()+Math.max(around.getSelectionStart(),around.getSelectionEnd());
                return ic.setSelection(position,position);
            }
        }
        ExtractedText e=ic.getExtractedText(new ExtractedTextRequest(),0);
        return e!=null && ic.setSelection(Math.max(e.selectionStart,e.selectionEnd),
            Math.max(e.selectionStart,e.selectionEnd));
    }
}
