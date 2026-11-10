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
        "am","be","my","me","us","let's","lets","let","let's","i'll","i'd",
        "we're","we'll","it's","that's","there's","isn't","aren't","can't",
        "won't","didn't","doesn't","wasn't","wouldn't","shouldn't","couldn't",
        "they're","you'll","he's","she's","we've","you've","i've",
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

    private static double signal(String word,PredictionModel model) {
        return model==null?CommonLexiconCase.languageSignal(word):model.signal(word);
    }

    /** Uses recent words across sentence boundaries, never transmits or stores them. */
    public static String detect(String text) {
        return detect(text,null);
    }
    public static String detect(String text,PredictionModel lexicon) {
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
            // English contractions are strong evidence even after earlier
            // German text; 'okay' is language-neutral, but 'let's' is not.
            if(w.contains("'") && EN.contains(w) && !DE.contains(w))english+=3.0*weight;
            if(DE.contains(w)&&!EN.contains(w))german+=weight;
            if(w.contains("ä")||w.contains("ö")||w.contains("ü")||w.contains("ß"))german+=weight;
        }
        // A distinctive complete English/German word as the active prefix
        // supplies a language signal without reinterpreting its capitalization.
        String last=prefix.toLowerCase(Locale.GERMAN);
        if(last.length()>2 || (last.length()==2 && english==german)) {
            if(EN.contains(last)&&!DE.contains(last))english+=last.contains("'")?5.0:1.5;
            if(DE.contains(last)&&!EN.contains(last))german+=1.5;
        }
        if(Math.abs(english-german)<0.5) {
            double evidence=0;
            for(int i=start;i<words.size();i++)evidence+=signal(words.get(i),lexicon);
            if(last.length()>2)evidence+=signal(last,lexicon);
            if(Math.abs(evidence)>0.35)return evidence>0?"en":"de";
        }
        return english>german+0.4?"en":"de";
    }
}
