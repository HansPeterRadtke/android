package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Ranked suggestions: recorded usage first, then model, then safe cold-start defaults. */
public final class SuggestionMixer {
    private SuggestionMixer() {}
    private static final String[] ENGLISH={
        "i","the","we","you","it","that","this","and","to","a","is","in","for",
        "not","have","are","with","was","would","can","will","my","what","when",
        "there","think","now","good","go","going","want","like","about","know"
    };
    private static final String[] GERMAN={
        "ich","das","wir","die","der","und","nicht","es","ein","ist","mit",
        "du","sie","zu","aber","habe","bin","mir","möchte","dass","ja",
        "wenn","dann","noch","so","war","kann","auch","heute","hier","gern","gerne"
    };
    private static String normalized(String value) {
        return value==null?"":value.toLowerCase(Locale.GERMAN).replace('’','\'').trim();
    }
    /** Use no arbitrary guesses if the field has an impossible multi-letter prefix. */
    public static List<String> coldStart(String prefix,String language,boolean atSentenceStart) {
        String check=normalized(prefix);
        String[] items="en".equals(language)?ENGLISH:GERMAN;
        List<String> result=new ArrayList<>(3);
        for(String word:items) {
            if(!word.startsWith(check))continue;
            String value=atSentenceStart?WordCase.display(word,"","",false):word;
            result.add(value);
            if(result.size()==3)break;
        }
        return result;
    }
    public static List<String> merge(List<String> personal,List<String> fallback,
                                     int limit,String typedPrefix) {
        if(limit<=0)return java.util.Collections.emptyList();
        List<String> output=new ArrayList<>(limit);
        Set<String> keys=new HashSet<>(limit*3);
        append(output,keys,personal,limit,typedPrefix);
        append(output,keys,fallback,limit,typedPrefix);
        return output;
    }
    /** Preserve useful one-edit typo corrections rather than filtering them. */
    private static boolean compatible(String token,String typed) {
        if(token.startsWith(typed))return true;
        if(typed.length()<3 || token.isEmpty())return false;
        int n=typed.length();
        for(int length=Math.max(1,n-1);length<=Math.min(token.length(),n+1);length++) {
            if(oneEdit(typed,token.substring(0,length)))return true;
        }
        return false;
    }
    private static boolean oneEdit(String a,String b) {
        if(Math.abs(a.length()-b.length())>1)return false;
        int i=0,j=0,errors=0;
        while(i<a.length() && j<b.length()) {
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(++errors>1)return false;
            if(a.length()>b.length())i++;
            else if(a.length()<b.length())j++;
            else if(i+1<a.length()&&j+1<b.length()&&a.charAt(i)==b.charAt(j+1)
                 &&a.charAt(i+1)==b.charAt(j)) {i+=2;j+=2;}
            else {i++;j++;}
        }
        return errors+(i<a.length()?1:0)+(j<b.length()?1:0)<=1;
    }
    private static void append(List<String> output,Set<String> keys,List<String> values,
                               int limit,String prefix) {
        if(values==null)return;
        String typed=normalized(prefix);
        for(String candidate:values) {
            if(output.size()>=limit)return;
            if(candidate==null || candidate.isBlank())continue;
            String token=normalized(candidate);
            if(!compatible(token,typed))continue;
            if(keys.add(token))output.add(candidate);
        }
    }
}
