package com.hans.android.adaptivekeyboard;

/** Tracks the single word being authored by this IME between editor callbacks. */
final class TypedWordDraft {
    private String before="";
    private String suffix="";
    private boolean insideWord;

    void reset() {
        before=""; suffix=""; insideWord=false;
    }

    void observe(String left,String right,boolean selection) {
        boolean middle=!right.isEmpty() && Character.isLetterOrDigit(right.codePointAt(0));
        if(selection || middle || !left.endsWith(suffix))suffix="";
        before=tail(left);
        insideWord=middle;
    }

    void resume(String left,TextContext word) {
        before=tail(left);suffix=word.prefix;insideWord=false;
    }

    void append(String letters) {
        before=tail(before+letters);
        suffix=tail(suffix+letters);
    }

    void backspace(boolean selection) {
        if(selection || before.isEmpty()) {reset();return;}
        before=before.substring(0,before.offsetByCodePoints(before.length(),-1));
        if(!suffix.isEmpty())suffix=suffix.substring(0,suffix.offsetByCodePoints(suffix.length(),-1));
    }

    boolean hasInput() {return !suffix.isEmpty();}

    TextContext finish() {
        if(!hasInput())return null;
        TextContext ctx=TextContext.fromBeforeCursor(before);
        boolean valid=!insideWord && ctx.prefix.endsWith(suffix) &&
            PersonalNextWords.valid(ctx.prefix) && Character.isLetter(ctx.prefix.codePointAt(0));
        suffix="";
        return valid?ctx:null;
    }

    void separator(CharSequence actual,String delimiter) {
        before=tail((actual==null?before:actual.toString())+delimiter);
        suffix="";insideWord=false;
    }

    static boolean same(TextContext a,TextContext b) {
        return a!=null && b!=null && a.prefix.equals(b.prefix) &&
            a.previous.equals(b.previous) && a.beforePrevious.equals(b.beforePrevious);
    }

    private static String tail(String value) {
        return value.length()<=350?value:value.substring(value.length()-350);
    }
}
