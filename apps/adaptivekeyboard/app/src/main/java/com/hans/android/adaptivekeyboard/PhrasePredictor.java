package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Two-or-three-word local completions, computed only after typing has paused. */
public final class PhrasePredictor {
    public interface Continuations {
        List<String> next(String context, int limit);
    }
    public interface Casing {
        String format(String token, String typedPrefix, String context);
    }
    private PhrasePredictor() { }

    public static List<String> complete(String beforeCursor, List<String> firstWords,
                                        Continuations engine, Casing casing, int maxPhrases) {
        if(beforeCursor==null || firstWords==null || engine==null || casing==null || maxPhrases<1)
            return java.util.Collections.emptyList();
        String typedPrefix=ChimeEnglish.activePrefix(beforeCursor);
        String previousContext=beforeCursor.substring(0,beforeCursor.length()-typedPrefix.length());
        List<String> out=new ArrayList<>(Math.min(3,maxPhrases));
        Set<String> seen=new HashSet<>();
        TextContext context=TextContext.fromBeforeCursor(beforeCursor);
        boolean opening=context.prefix.isEmpty() && context.previous.isEmpty() &&
            context.beforePrevious.isEmpty();
        boolean german=!opening && "de".equals(LanguageContext.detect(beforeCursor));
        List<List<String>> perFirst=new ArrayList<>(firstWords.size());
        for(String word:firstWords) {
            if(!validWord(word) || (german && word.length()==1))continue;
            String first=casing.format(word,typedPrefix,previousContext);
            String seed=previousContext+first+" ";
            List<String> next=engine.next(seed,8);
            if(next==null || next.isEmpty())continue;
            List<String> phrases=new ArrayList<>(3);
            for(String raw:next) {
                if(!validWord(raw))continue;
                String second=casing.format(raw,"",seed);
                if(!differentWord(first,second))continue;
                String phrase=first+" "+second;
                if(phrases.stream().noneMatch(p->p.equalsIgnoreCase(phrase)))phrases.add(phrase);
                if(phrases.size()>=maxPhrases)break;
            }
            if(!phrases.isEmpty())perFirst.add(phrases);
        }
        // Round robin yields one distinct phrase for each first word before
        // producing variations of a single highly likely personal continuation.
        for(int rank=0;rank<maxPhrases && out.size()<maxPhrases;rank++) {
            for(List<String> choices:perFirst) {
                if(rank>=choices.size())continue;
                String phrase=choices.get(rank),key=phrase.toLowerCase(Locale.GERMAN);
                if(seen.add(key))out.add(phrase);
                if(out.size()==maxPhrases)break;
            }
        }
        return out;
    }
    private static boolean differentWord(String a,String b) {
        return !a.equalsIgnoreCase(b) && !b.startsWith("<") && !b.equals("'");
    }
    public static boolean validWord(String token) {
        if(token==null||token.isEmpty()||token.length()>35 ||
            !Character.isLetter(token.codePointAt(0)))return false;
        for(int i=0;i<token.length();) {
            int cp=token.codePointAt(i);
            if(!Character.isLetter(cp)&&cp!='\''&&cp!='’'&&cp!='-')return false;
            i+=Character.charCount(cp);
        }
        return true;
    }
    public static String[] words(String phrase) {
        if(phrase==null)return new String[0];
        String s=phrase.trim();
        if(s.isEmpty())return new String[0];
        String[] parts=s.split("\\s+");
        if(parts.length<2 || parts.length>3)return new String[0];
        for(String part:parts)if(!validWord(part))return new String[0];
        return parts;
    }
}
