package com.hans.android.adaptivekeyboard;

import java.util.Locale;
import java.util.Set;

/** Predict word spelling separately from how the user entered its prefix. */
public final class WordCase {
    private WordCase() { }
    private static final Set<String> ABBREVIATIONS=Set.of(
        "z.b.","u.a.","d.h.","bzw.","usw.","z.t.","ca.","etc.","dr.","prof.",
        "mr.","mrs.","ms.","e.g.","i.e.","vs.","approx.");

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
        String prefix=GrammarTokens.activeWord(text);
        return GrammarTokens.isSentenceStartAt(text, text.length() - prefix.length());
    }

    /** Inserted words obey sentence capitalization; explicit prefix wins. */
    public static String display(String word,String typedPrefix,String beforeCursor,boolean capMode) {
        if(word==null||word.isEmpty())return "";
        if(typedPrefix!=null && !typedPrefix.isEmpty())return matchPrefix(word,typedPrefix);
        String value=word;
        // The English pronoun 'I' is always capitalized, not just sentence-initial.
        if("i".equals(value))return "I";
        if(capMode||sentenceStart(beforeCursor))
            return value.substring(0,1).toUpperCase(Locale.GERMAN)+value.substring(1);
        // The full English dictionary loads in the background. Until then,
        // last-used personal spelling may still be sentence-initial ("Now").
        // The small built-in case prior avoids reusing that capital mid-sentence.
        if("en".equals(LanguageContext.detect(beforeCursor)) &&
            EnglishCommonCase.shouldLowercase(value))
            return value.toLowerCase(Locale.ROOT);
        return value;
    }
}
