package com.hans.android.adaptivekeyboard;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Bounded sequence of confirmed transitions after one preceding word.
 * Newest completions count more, but lifetime occurrence counts also matter.
 * Each transition is an observation, NOT a model prediction or displayed hint.
 */
public final class PersonalNextWords {
    public static final int WINDOW=48;
    private static final double RECENT_ALPHA=0.75;
    private static final double MAX_RECENCY_WEIGHT=0.70;
    private static final String SEP="\u001f";

    private PersonalNextWords() { }

    public static boolean valid(String word) {
        if(word==null || word.isEmpty() || word.length()>60 ||
           !Character.isLetterOrDigit(word.codePointAt(0)))return false;
        for(int i=0;i<word.length();) {
            int cp=word.codePointAt(i);
            i+=Character.charCount(cp);
            if(Character.isLetterOrDigit(cp) || Character.getType(cp)==Character.NON_SPACING_MARK ||
               Character.getType(cp)==Character.COMBINING_SPACING_MARK)continue;
            if((cp=='\'' || cp=='’' || cp=='-') && i<word.length() &&
               Character.isLetterOrDigit(word.codePointAt(i)))continue;
            return false;
        }
        return true;
    }

    public static ArrayDeque<String> decode(String saved) {
        ArrayDeque<String> history=new ArrayDeque<>(WINDOW);
        if(saved==null || saved.isEmpty())return history;
        String[] tokens=saved.split(SEP,-1);
        for(String token:tokens)if(valid(token))append(history,token);
        return history;
    }

    public static String encode(ArrayDeque<String> history) {
        if(history==null || history.isEmpty())return "";
        return String.join(SEP,history);
    }

    /** Returns the word evicted from the oldest end, if the bounded window is full. */
    public static String append(ArrayDeque<String> history,String rawWord) {
        String word=TextContext.normalize(rawWord);
        if(history==null || !valid(word))return null;
        String evicted=history.size()>=WINDOW?history.removeFirst():null;
        history.addLast(word);
        return evicted;
    }

    /** Undo one newest confirmed transition, restoring an evicted oldest item. */
    public static void undoLast(ArrayDeque<String> history,String rawWord) {
        undoLast(history,rawWord,null);
    }
    public static void undoLast(ArrayDeque<String> history,String rawWord,String evicted) {
        if(history==null || history.isEmpty() ||
           !history.peekLast().equals(TextContext.normalize(rawWord)))return;
        history.removeLast();
        if(valid(evicted))history.addFirst(TextContext.normalize(evicted));
        while(history.size()>WINDOW)history.removeLast();
    }

    /** Exponentially weighted distribution over recent actual transitions. */
    public static Map<String,Double> recentShares(ArrayDeque<String> history) {
        if(history==null || history.isEmpty())return Collections.emptyMap();
        Map<String,Double> result=new HashMap<>();
        double weight=1.0,total=0.0;
        java.util.Iterator<String> it=history.descendingIterator();
        while(it.hasNext()) {
            String word=it.next();
            result.merge(word,weight,Double::sum);
            total+=weight;
            weight*=RECENT_ALPHA;
        }
        for(Map.Entry<String,Double> item:result.entrySet())
            item.setValue(item.getValue()/total);
        return result;
    }

    /**
     * After one new observation, older lifetime history still dominates.
     * As the new pattern is repeated, the exponentially weighted fraction
     * takes over quickly instead of requiring more lifetime repetitions.
     */
    public static double recentMix(int recentObservations) {
        return MAX_RECENCY_WEIGHT *
            (1.0-Math.pow(0.7,Math.max(0,recentObservations)));
    }

    public static double score(int count,int totalLifetime,double recentShare,int recentObservations) {
        if(count<=0 || totalLifetime<=0)return 0;
        double mix=recentMix(recentObservations);
        return (1.0-mix)*count/(double)totalLifetime+mix*Math.max(0,recentShare);
    }

    public static String updated(String saved,String word,int delta) {
        return updated(saved,word,delta,null);
    }
    public static String updated(String saved,String word,int delta,String evicted) {
        ArrayDeque<String> list=decode(saved);
        if(delta>0)append(list,word);
        else if(delta<0)undoLast(list,word,evicted);
        return encode(list);
    }
}
