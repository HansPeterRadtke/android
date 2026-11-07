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
        boolean german="de".equals(LanguageContext.detect(beforeCursor));
        for(String word:firstWords) {
            if(out.size()>=maxPhrases)break;
            if(!validWord(word))continue;
            // German prediction should not mix in a single English article "a".
            if(german && word.length()==1)continue;
            String first=casing.format(word,typedPrefix,previousContext);
            String seed=previousContext+first+" ";
            List<String> next=engine.next(seed,5);
            if(next==null)continue;
            String second=null;
            for(String raw:next) {
                if(!validWord(raw))continue;
                String candidate=casing.format(raw,"",seed);
                if(!differentWord(first,candidate))continue;
                second=candidate;
                break;
            }
            if(second==null)continue;
            String result=first+" "+second;
            String key=result.toLowerCase(Locale.GERMAN);
            if(seen.add(key))out.add(result);
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
