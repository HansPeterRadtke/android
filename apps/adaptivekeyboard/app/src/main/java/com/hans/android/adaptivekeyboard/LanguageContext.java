package com.hans.android.adaptivekeyboard;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight bilingual routing based on recent real text; default is German QWERTZ. */
public final class LanguageContext {
    private LanguageContext(){}
    private static final Set<String> EN=new HashSet<>(Arrays.asList(
        "i","i'm","i've","you","you're","your","the","is","are","was",
        "have","has","had","do","does","did","don't","what","why","how",
        "where","which","there","this","that","with","would","could","should",
        "we","they","them","hello","hi","please","thank","thanks","going","want","will","can"));
    private static final Set<String> DE=new HashSet<>(Arrays.asList(
        "ich","du","er","sie","wir","ihr","ihnen","mein","meine","dein","deine",
        "der","die","das","den","dem","des","ein","eine","einer","einen",
        "und","oder","nicht","kein","keine","ist","sind","war","haben","hat",
        "habe","wird","werden","wie","wo","wann","warum","welche","welcher",
        "was","dass","aber","mit","für","bei","von","auf","im","zum","zur",
        "heute","morgen","hallo","bitte","danke","möchte","kann","geht","geht's"));
    private static final Pattern WORDS=Pattern.compile("[\\p{L}]+(?:['’][\\p{L}]+)*");

    /** Uses recent words across sentence boundaries, never transmits or stores them. */
    public static String detect(String text) {
        if(text==null || text.isBlank())return "de";
        String s=text.length()>180?text.substring(text.length()-180):text;
        String prefix=ChimeEnglish.activePrefix(s);
        if(!prefix.isEmpty())s=s.substring(0,s.length()-prefix.length());
        double english=0,german=0;
        Matcher m=WORDS.matcher(s);
        java.util.ArrayList<String> words=new java.util.ArrayList<>();
        while(m.find())words.add(m.group().toLowerCase(Locale.GERMAN).replace('’','\''));
        int start=Math.max(0,words.size()-8);
        for(int i=start;i<words.size();i++) {
            String w=words.get(i);
            double weight=i>=words.size()-3?2.0:1.0;
            if(EN.contains(w)&&!DE.contains(w))english+=weight;
            if(DE.contains(w)&&!EN.contains(w))german+=weight;
            if(w.contains("ä")||w.contains("ö")||w.contains("ü")||w.contains("ß"))german+=weight;
        }
        // A distinctive complete English/German word as the active prefix
        // supplies a language signal without reinterpreting its capitalization.
        String last=prefix.toLowerCase(Locale.GERMAN);
        if(last.length()>2) {
            if(EN.contains(last)&&!DE.contains(last))english+=1.5;
            if(DE.contains(last)&&!EN.contains(last))german+=1.5;
        }
        return english>german+0.4?"en":"de";
    }
}
