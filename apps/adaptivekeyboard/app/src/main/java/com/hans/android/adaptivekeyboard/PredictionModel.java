package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-device, deterministic, context-sensitive word completion.
 * Pure Java: the same ranking runs in tests and on the phone.
 * Only a delimiter or an explicit tap teaches the model; a displayed suggestion
 * is NEVER interpreted as accepted when the user simply continues typing.
 */
public final class PredictionModel {
    private static final class Word {
        String surface;
        int baseRank;
        int typed;
        int selected;
        Word(String surface, int rank) { this.surface = surface; baseRank = rank; }
    }

    public static final class Event {
        public final String word, previous, beforePrevious, prefix;
        public final boolean selected;
        private Event(String word, String prev, String prev2, String pref, boolean chosen) {
            this.word = word; previous = prev; beforePrevious = prev2; prefix = pref; selected = chosen;
        }
    }

    private static final class Scored {
        final String output;
        final double score;
        Scored(String s, double n) { output = s; score = n; }
    }

    private final Map<String,Word> words = new HashMap<>();
    private final Map<String,Integer> pairs = new HashMap<>();
    private final Map<String,Integer> triples = new HashMap<>();
    private final Map<String,Integer> choices = new HashMap<>();

    public synchronized void addBaseWord(String value, int rank) {
        String key = TextContext.normalize(value);
        if (key.isEmpty()) return;
        Word w = words.get(key);
        if (w == null) words.put(key, new Word(value, Math.max(0,rank)));
        else if (rank < w.baseRank) { w.baseRank = Math.max(0,rank); w.surface = value; }
    }

    private static String key(String... parts) { return String.join("\u0001", parts); }
    private static int get(Map<String,Integer> table, String k) { return table.getOrDefault(k, 0); }
    private static void change(Map<String,Integer> table, String k, int delta) {
        int n = Math.max(0, get(table,k) + delta);
        if (n == 0) table.remove(k); else table.put(k,n);
    }

    public synchronized Event learnTyped(String raw, String prev, String prev2) {
        return learn(raw, prev, prev2, "", false);
    }
    public synchronized Event learnSelection(String typedPrefix, String chosen, String prev, String prev2) {
        return learn(chosen, prev, prev2, typedPrefix, true);
    }

    private Event learn(String raw, String prev, String prev2, String pref, boolean selected) {
        String word = TextContext.normalize(raw);
        String a = TextContext.normalize(prev), b = TextContext.normalize(prev2);
        String prefix = TextContext.normalize(pref);
        if (word.isEmpty() || word.length() > 60) return null;
        Word w = words.computeIfAbsent(word, s -> new Word(raw, 999999));
        // Preserve the lexicon's proper noun capitalization when a lower-case token
        // is merely typed. A deliberate suggestion tap may choose another surface.
        if (selected || w.baseRank == 999999 ||
            Character.isUpperCase(raw.charAt(0)) ||
            !Character.isUpperCase(w.surface.charAt(0))) w.surface = raw;
        w.typed++;
        if (selected) w.selected++;
        if (!a.isEmpty()) change(pairs, key(a,word), 1);
        if (!b.isEmpty() && !a.isEmpty()) change(triples,key(b,a,word),1);
        if (selected && !prefix.isEmpty()) {
            if (!a.isEmpty() || !b.isEmpty())
                change(choices,key(b,a,prefix,word),1);
            change(choices,key("","",prefix,word),1);
        }
        return new Event(word,a,b,prefix,selected);
    }

    /** Retracts a recently confirmed token if the user immediately deletes it. */
    public synchronized void undo(Event e) {
        if (e == null) return;
        Word w = words.get(e.word);
        if (w != null) {
            w.typed = Math.max(0,w.typed - 1);
            if (e.selected) w.selected = Math.max(0,w.selected - 1);
            if (w.typed == 0 && w.baseRank == 999999) words.remove(e.word);
        }
        if (!e.previous.isEmpty()) change(pairs,key(e.previous,e.word),-1);
        if (!e.beforePrevious.isEmpty() && !e.previous.isEmpty())
            change(triples,key(e.beforePrevious,e.previous,e.word),-1);
        if (e.selected && !e.prefix.isEmpty()) {
            if (!e.previous.isEmpty() || !e.beforePrevious.isEmpty())
                change(choices,key(e.beforePrevious,e.previous,e.prefix,e.word),-1);
            change(choices,key("","",e.prefix,e.word),-1);
        }
    }

    public synchronized void restoreWord(String surface, int typed, int selected) {
        String k = TextContext.normalize(surface);
        if (k.isEmpty()) return;
        Word w = words.computeIfAbsent(k, s -> new Word(surface, 999999));
        w.surface = surface; w.typed = Math.max(0,typed); w.selected = Math.max(0,selected);
    }
    public synchronized void restorePair(String prev, String word, int n) {
        if (n > 0) pairs.put(key(prev,word),n);
    }
    public synchronized void restoreTriple(String prev2, String prev, String word, int n) {
        if (n > 0) triples.put(key(prev2,prev,word),n);
    }
    public synchronized void restoreChoice(String prev2, String prev, String prefix, String word, int n) {
        if (n > 0) choices.put(key(prev2,prev,prefix,word),n);
    }

    /** Results sorted by likelihood; caller places index zero in the middle button. */
    public synchronized List<String> predict(String rawPrefix, String prev, String prev2) {
        String prefix = TextContext.normalize(rawPrefix);
        String a = TextContext.normalize(prev), b = TextContext.normalize(prev2);
        if (prefix.isEmpty() && a.isEmpty()) return Collections.emptyList();
        Scored[] best = new Scored[3];
        int matching = 0;
        for (Map.Entry<String,Word> ent : words.entrySet()) {
            String form = ent.getKey();
            if (!form.startsWith(prefix)) continue;
            matching++;
            offer(best, form, ent.getValue(), a,b,prefix,0);
        }
        // When there are fewer than three exact completions, tolerate one typo
        // in a prefix with three or more letters, without disrupting exact matches.
        if (matching < 3 && prefix.length() >= 3) {
            for (Map.Entry<String,Word> ent : words.entrySet()) {
                String form = ent.getKey();
                if (form.startsWith(prefix) || form.length() < prefix.length()-1) continue;
                if (oneEditFromPrefix(prefix, form))
                    offer(best, form, ent.getValue(), a,b,prefix,-11);
            }
        }
        List<String> result = new ArrayList<>(3);
        for (Scored s : best) if (s != null) result.add(s.output);
        return result;
    }

    private void offer(Scored[] best, String form, Word w, String a, String b, String prefix, double penalty) {
        if (prefix.isEmpty() && get(pairs,key(a,form)) == 0 &&
            get(triples,key(b,a,form)) == 0) return;
        double base = w.baseRank == 999999 ? 0 : Math.max(0, 11 - 1.35*Math.log1p(w.baseRank));
        double score = base + 5*Math.log1p(w.typed) + 4*Math.log1p(w.selected)
            + 8*Math.log1p(get(pairs,key(a,form)))
            + 13*Math.log1p(get(triples,key(b,a,form)))
            + 18*Math.log1p(get(choices,key(b,a,prefix,form)))
            + 4*Math.log1p(get(choices,key("","",prefix,form)))
            + (form.equals(prefix) ? 1 : 0) + penalty;
        String surface = w.surface;
        Scored candidate = new Scored(surface,score);
        for (int i=0;i<3;i++) {
            if (best[i] == null || score > best[i].score ||
                (score == best[i].score && surface.compareToIgnoreCase(best[i].output) < 0)) {
                for (int j=2;j>i;j--) best[j] = best[j-1];
                best[i] = candidate; break;
            }
        }
    }

    private static boolean oneEditFromPrefix(String prefix, String word) {
        int n = prefix.length();
        for (int targetLen=Math.max(1,n-1);targetLen<=Math.min(word.length(),n+1);targetLen++) {
            String b = word.substring(0,targetLen);
            if (editDistanceWithinOne(prefix,b)) return true;
        }
        return false;
    }
    private static boolean editDistanceWithinOne(String a, String b) {
        if (Math.abs(a.length()-b.length()) > 1) return false;
        int i=0,j=0,mistakes=0;
        while (i<a.length() && j<b.length()) {
            if (a.charAt(i)==b.charAt(j)) {i++;j++;continue;}
            if (++mistakes>1) return false;
            if (a.length()>b.length()) i++;
            else if (a.length()<b.length()) j++;
            else if (i+1<a.length() && j+1<b.length() &&
                a.charAt(i)==b.charAt(j+1) && a.charAt(i+1)==b.charAt(j)) {i+=2;j+=2;}
            else {i++;j++;}
        }
        return mistakes + (i<a.length() ? 1 : 0) + (j<b.length() ? 1 : 0) <= 1;
    }
}
