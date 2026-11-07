package com.hans.android.adaptivekeyboard;

import java.util.Locale;

/** Predict word spelling separately from how the user entered its prefix. */
public final class WordCase {
    private WordCase() { }

    /** The part already typed is never re-cased or replaced by predictions. */
    public static String matchPrefix(String dictionaryWord,String typedPrefix) {
        if(dictionaryWord==null||dictionaryWord.isEmpty())return "";
        if(typedPrefix==null||typedPrefix.isEmpty())return dictionaryWord;
        String lowerWord=dictionaryWord.toLowerCase(Locale.GERMAN);
        String lowerPrefix=typedPrefix.toLowerCase(Locale.GERMAN);
        if(!lowerWord.startsWith(lowerPrefix)) {
            // A suggested typo correction is an explicitly selectable candidate;
            // preserve the user's intended lower/upper-case pattern.
            if(typedPrefix.length()>1 && typedPrefix.equals(typedPrefix.toUpperCase(Locale.GERMAN)))
                return dictionaryWord.toUpperCase(Locale.GERMAN);
            if(Character.isUpperCase(typedPrefix.charAt(0)))
                return dictionaryWord.substring(0,1).toUpperCase(Locale.GERMAN)+dictionaryWord.substring(1);
            return dictionaryWord.substring(0,1).toLowerCase(Locale.GERMAN)+dictionaryWord.substring(1);
        }
        String tail=dictionaryWord.substring(Math.min(dictionaryWord.length(),typedPrefix.length()));
        if(typedPrefix.length()>1 && typedPrefix.equals(typedPrefix.toUpperCase(Locale.GERMAN)) &&
            !typedPrefix.equals(typedPrefix.toLowerCase(Locale.GERMAN)))
            tail=tail.toUpperCase(Locale.GERMAN);
        return typedPrefix+tail;
    }

    public static boolean sentenceStart(String beforeCursor) {
        String text=beforeCursor==null?"":beforeCursor;
        String active=ChimeEnglish.activePrefix(text);
        if(!active.isEmpty())text=text.substring(0,text.length()-active.length());
        int end=text.length()-1;
        while(end>=0 && (Character.isWhitespace(text.charAt(end)) ||
              text.charAt(end)=='\"' || text.charAt(end)=='\u201d' || text.charAt(end)=='\u2019' ||
              text.charAt(end)==')'))end--;
        if(end<0)return true;
        char ch=text.charAt(end);
        if(ch=='.'||ch=='!'||ch=='?'||ch=='\u2026')return true;
        return text.substring(0,end+1).lastIndexOf('\n')>=0 &&
            text.substring(text.lastIndexOf('\n')+1,end+1).trim().isEmpty();
    }

    /** Inserted words obey sentence capitalization; explicit prefix wins. */
    public static String display(String word,String typedPrefix,String beforeCursor,boolean capMode) {
        if(word==null||word.isEmpty())return "";
        if(typedPrefix!=null && !typedPrefix.isEmpty())return matchPrefix(word,typedPrefix);
        String value=word;
        // The English pronoun 'I' is always capitalized, not just sentence-initial.
        if("i".equals(value))return "I";
        if(capMode||sentenceStart(beforeCursor))
            value=value.substring(0,1).toUpperCase(Locale.GERMAN)+value.substring(1);
        return value;
    }
}
