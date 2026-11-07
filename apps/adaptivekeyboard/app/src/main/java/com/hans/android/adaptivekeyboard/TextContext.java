package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Word-boundary logic shared by the IME and deterministic tests. */
public final class TextContext {
    public final String prefix;
    public final String previous;
    public final String beforePrevious;

    private TextContext(String prefix, String previous, String beforePrevious) {
        this.prefix = prefix;
        this.previous = previous;
        this.beforePrevious = beforePrevious;
    }

    public static String normalize(String word) {
        return word == null ? "" : word.toLowerCase(Locale.GERMAN).trim();
    }

    private static boolean wordChar(String text,int index) {
        char c=text.charAt(index);
        if (Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK) return true;
        // Contractions such as don't and I'm are one word, standalone quotes aren't.
        return (c=='\'' || c=='’') && index>0 && index+1<text.length() &&
            Character.isLetter(text.charAt(index-1)) && Character.isLetter(text.charAt(index+1));
    }

    /** Keeps only real letters (including German umlauts and accents). */
    public static TextContext fromBeforeCursor(CharSequence before) {
        String s = before == null ? "" : before.toString();
        // A full stop, question/exclamation mark or newline starts a new
        // sentence. Do not carry context from an unrelated prior sentence.
        int sentenceStart=0;
        for(int pos=s.length()-1;pos>=0;pos--) {
            char ch=s.charAt(pos);
            if(ch=='.' || ch=='!' || ch=='?' || ch=='\n') {
                sentenceStart=pos+1;
                break;
            }
        }
        if(sentenceStart>0)s=s.substring(sentenceStart);
        boolean endsInWord = !s.isEmpty() && wordChar(s,s.length() - 1);
        List<String> found = new ArrayList<>(3);
        int i = s.length() - 1;
        while (i >= 0 && found.size() < 3) {
            while (i >= 0 && !wordChar(s,i)) i--;
            if (i < 0) break;
            int end = i + 1;
            while (i >= 0 && wordChar(s,i)) i--;
            found.add(s.substring(i + 1, end));
        }
        String prefix = endsInWord && !found.isEmpty() ? found.remove(0) : "";
        return new TextContext(prefix,
            found.isEmpty() ? "" : found.get(0),
            found.size() < 2 ? "" : found.get(1));
    }

    /** Before a word is committed, current prefix becomes the confirmed word. */
    public String confirmedWord() { return prefix; }
}
