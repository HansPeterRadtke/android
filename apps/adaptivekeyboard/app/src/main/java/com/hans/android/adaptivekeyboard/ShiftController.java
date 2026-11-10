package com.hans.android.adaptivekeyboard;

import java.util.Locale;

/** Deterministic standard keyboard shift modes independent of prediction timing. */
public final class ShiftController {
    public enum Mode { LOWER, AUTO, MANUAL_LOWER, ONCE, LOCKED }
    private Mode mode=Mode.LOWER;
    private long previousPress=-10000;
    public Mode mode() {return mode;}
    public boolean active() {return mode==Mode.AUTO || mode==Mode.ONCE || mode==Mode.LOCKED;}
    public boolean locked() {return mode==Mode.LOCKED;}
    public boolean manual() {return mode==Mode.ONCE || mode==Mode.LOCKED ||
        mode==Mode.MANUAL_LOWER;}
    public void reset(boolean autoCap) {
        mode=autoCap?Mode.AUTO:Mode.LOWER;
        previousPress=-10000;
    }
    public void press(long uptimeMs) {
        boolean rapid=uptimeMs>=previousPress && uptimeMs-previousPress<=420;
        if(mode==Mode.ONCE && rapid)mode=Mode.LOCKED;
        else if(mode==Mode.ONCE || mode==Mode.LOCKED)mode=Mode.MANUAL_LOWER;
        else mode=Mode.ONCE;
        previousPress=uptimeMs;
    }
    /** Auto capitalization cannot override explicit one-shot Shift or Caps Lock. */
    public void automatic(boolean atStart) {
        if(!manual())mode=atStart?Mode.AUTO:Mode.LOWER;
    }
    public void consumeLetter() {
        if(mode==Mode.ONCE || mode==Mode.AUTO || mode==Mode.MANUAL_LOWER)
            mode=Mode.LOWER;
    }
    public void consumeSuggestion() {consumeLetter();}
    public String applyWord(String candidate) {
        if(candidate==null||candidate.isEmpty())return "";
        if(mode==Mode.LOCKED)return candidate.toUpperCase(Locale.GERMAN);
        if(mode==Mode.ONCE)return title(candidate);
        if(mode==Mode.MANUAL_LOWER)return lowerFirst(candidate);
        return candidate;
    }
    public String applyPhrase(String phrase) {
        if(phrase==null||phrase.isEmpty())return "";
        if(mode==Mode.LOCKED)return phrase.toUpperCase(Locale.GERMAN);
        if(mode==Mode.MANUAL_LOWER) {
            int split=phrase.indexOf(' ');
            return split<0?lowerFirst(phrase):lowerFirst(phrase.substring(0,split))+
                phrase.substring(split);
        }
        if(mode!=Mode.ONCE)return phrase;
        String[] words=phrase.split(" ",-1);
        for(int i=0;i<words.length;i++)words[i]=title(words[i]);
        return String.join(" ",words);
    }
    private static String lowerFirst(String word) {
        if(word.isEmpty())return word;
        int first=word.codePointAt(0);
        return new String(Character.toChars(Character.toLowerCase(first)))+
            word.substring(Character.charCount(first));
    }
    private static String title(String word) {
        if(word.isEmpty())return word;
        int first=word.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first)))+
            word.substring(Character.charCount(first));
    }
}
