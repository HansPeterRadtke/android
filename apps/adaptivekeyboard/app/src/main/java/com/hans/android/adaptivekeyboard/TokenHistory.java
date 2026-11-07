package com.hans.android.adaptivekeyboard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Personal next-TOKEN history (words, punctuation, symbols), never model guesses. */
public final class TokenHistory {
    public static final String START="<start>";
    private static final String SEP="\u001f";
    private static final int WINDOW=48;
    public static final class Event {
        public final String before,next,evicted;
        Event(String before,String next,String evicted) {
            this.before=before;this.next=next;this.evicted=evicted;
        }
    }
    private final Map<String,Map<String,Integer>> counts=new HashMap<>();
    private final Map<String,ArrayDeque<String>> recent=new HashMap<>();

    public static boolean valid(String token) {
        if(token==null||token.isEmpty()||token.length()>60||token.indexOf(SEP)>=0)return false;
        return !token.startsWith("<") || token.equals("<3");
    }
    public static String norm(String token) {return GrammarTokens.normalize(token);}
    public synchronized Event learn(String preceding,String current) {
        String a=norm(preceding),b=norm(current);
        if(a.isEmpty())a=START;
        if((!valid(a)&&!START.equals(a))||!valid(b))return null;
        counts.computeIfAbsent(a,k->new HashMap<>()).merge(b,1,Integer::sum);
        ArrayDeque<String> sequence=recent.computeIfAbsent(a,k->new ArrayDeque<>(WINDOW));
        String evicted=sequence.size()==WINDOW?sequence.removeFirst():null;
        sequence.addLast(b);
        return new Event(a,b,evicted);
    }
    public synchronized void undo(Event event) {
        if(event==null)return;
        Map<String,Integer> next=counts.get(event.before);
        if(next!=null) {
            next.computeIfPresent(event.next,(k,v)->v>1?v-1:null);
            if(next.isEmpty())counts.remove(event.before);
        }
        ArrayDeque<String> sequence=recent.get(event.before);
        if(sequence!=null&&!sequence.isEmpty()&&sequence.peekLast().equals(event.next)) {
            sequence.removeLast();
            if(valid(event.evicted))sequence.addFirst(event.evicted);
        }
    }
    public synchronized void restore(String preceding,String token,int count) {
        if(count<=0||(!valid(preceding)&&!START.equals(preceding))||!valid(token))return;
        counts.computeIfAbsent(preceding,k->new HashMap<>()).put(token,count);
    }
    public synchronized void restoreRecent(String previous,String serialized) {
        if((!valid(previous)&&!START.equals(previous))||serialized==null)return;
        ArrayDeque<String> tokens=decode(serialized);
        if(!tokens.isEmpty())recent.put(previous,tokens);
    }
    public static ArrayDeque<String> decode(String serialized) {
        ArrayDeque<String> tokens=new ArrayDeque<>();
        if(serialized!=null && !serialized.isEmpty())for(String word:serialized.split(SEP,-1)) {
            if(!valid(word))continue;
            if(tokens.size()==WINDOW)tokens.removeFirst();
            tokens.addLast(word);
        }
        return tokens;
    }
    public static String encode(ArrayDeque<String> tokens) {
        return tokens==null?"":String.join(SEP,tokens);
    }
    public synchronized List<String> predict(String preceding,String rawPrefix,int limit) {
        String a=norm(preceding),prefix=norm(rawPrefix);
        if(a.isEmpty())a=START;
        Map<String,Integer> next=counts.get(a);
        if(next==null||limit<=0)return Collections.emptyList();
        Map<String,Double> recents=recentShares(recent.get(a));
        int total=next.values().stream().mapToInt(Integer::intValue).sum();
        int seen=recent.containsKey(a)?recent.get(a).size():0;
        List<String> ranked=new ArrayList<>();
        for(Map.Entry<String,Integer> e:next.entrySet())
            if(e.getKey().startsWith(prefix) && e.getValue()>0)ranked.add(e.getKey());
        ranked.sort((x,y)->{
            double sx=PersonalNextWords.score(next.get(x),total,recents.getOrDefault(x,0.0),seen);
            double sy=PersonalNextWords.score(next.get(y),total,recents.getOrDefault(y,0.0),seen);
            int order=Double.compare(sy,sx);
            return order!=0?order:x.compareTo(y);
        });
        return ranked.subList(0,Math.min(limit,ranked.size()));
    }
    private static Map<String,Double> recentShares(ArrayDeque<String> tokens) {
        if(tokens==null||tokens.isEmpty())return Collections.emptyMap();
        Map<String,Double> scores=new HashMap<>();double weight=1,total=0;
        for(java.util.Iterator<String> it=tokens.descendingIterator();it.hasNext();) {
            scores.merge(it.next(),weight,Double::sum);total+=weight;weight*=0.75;
        }
        for(Map.Entry<String,Double> s:scores.entrySet())s.setValue(s.getValue()/total);
        return scores;
    }
}
