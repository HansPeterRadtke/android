package com.hans.android.adaptivekeyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Private on-device learning model: user's selections override German/English defaults. */
public final class PredictionModel {
    private static final int UNKNOWN_RANK=999999;
    // Not a real word; cannot be returned by TextContext.fromBeforeCursor().
    // This represents only CONFIRMED words typed at the start of a sentence.
    private static final String SENTENCE_START="<bos>";
    private static final class Word {
        String surface;
        String lexicalSurface;
        String englishSurface;
        String germanSurface;
        int baseRank=UNKNOWN_RANK;
        int deRank=UNKNOWN_RANK;
        int enRank=UNKNOWN_RANK;
        int typed;
        int selected;
        long lastUsedMs;
        Word(String text) {surface=text;}
    }
    public static final class Event {
        public final String word, previous, beforePrevious, prefix;
        public final boolean selected;
        public final long timestamp;
        public final String oldestEvicted;
        private Event(String word,String previous,String beforePrevious,String prefix,boolean selected,
                      long timestamp,String oldestEvicted) {
            this.word=word;this.previous=previous;this.beforePrevious=beforePrevious;this.prefix=prefix;
            this.selected=selected;this.timestamp=timestamp;this.oldestEvicted=oldestEvicted;
        }
    }
    public static final class Rejection {
        public final String word,previous,beforePrevious,prefix;
        private Rejection(String word,String prev,String prev2,String prefix) {
            this.word=word;previous=prev;beforePrevious=prev2;this.prefix=prefix;
        }
    }
    private static final class Scored {
        final String output;
        final double score;
        Scored(String value,double score) {this.output=value;this.score=score;}
    }
    // Build one sorted prefix index lazily; HashMap makes startup substantially faster.
    private final Map<String,Word> words = new HashMap<>();
    private final java.util.NavigableSet<String> learnedKeys=new java.util.TreeSet<>();
    private List<String> sortedKeys=Collections.emptyList();
    private boolean indexDirty=true;
    // Prefixes of one or two characters can match thousands of words. Only
    // a bounded, relevance-ranked shortlist is needed for three suggestions.
    private final Map<String,List<String>> commonPrefixCache=new HashMap<>();
    private static final int SHORTLIST_SIZE=160;
    private final Map<String,Set<String>> nextByPrevious=new HashMap<>();
    private final Map<String,Set<String>> nextByTwoPrevious=new HashMap<>();
    private final Map<String,Integer> pairs=new HashMap<>();
    // Explicitly confirmed next words, newest observations last, per context.
    // This history never contains mere displayed model guesses.
    private final Map<String,java.util.ArrayDeque<String>> recentByPrevious=new HashMap<>();
    private List<String> cachedLegacyOpenings=null;
    private final Map<String,Integer> triples=new HashMap<>();
    // Total observations of each pair for which a preceding word is known.
    private final Map<String,Integer> pairWithContext=new HashMap<>();
    private final Map<String,Integer> choices=new HashMap<>();
    private final Map<String,Long> choiceTimes=new HashMap<>();
    private final Map<String,Long> latestChoiceByPrefix=new HashMap<>();
    private final Map<String,Integer> rejections=new HashMap<>();
    private BaselineNgrams baseline;
    public synchronized int size() {return words.size();}
    public synchronized void setBaselineNgrams(BaselineNgrams sharedBaseline) {
        this.baseline=sharedBaseline;
    }
    private static String norm(String w) {return TextContext.normalize(w);}
    private static String key(String...parts) {return String.join("\u0001",parts);}
    private static int get(Map<String,Integer> table,String k) {return table.getOrDefault(k,0);}
    private static void adjust(Map<String,Integer> m,String k,int delta) {
        int value=Math.max(0,get(m,k)+delta);
        if(value==0)m.remove(k);else m.put(k,value);
    }
    public synchronized void addBaseWord(String value,int rank) {addBaseWord(value,rank,"de");}
    public synchronized void addBaseWord(String value,int rank,String language) {
        String k=norm(value);
        if(k.isEmpty())return;
        Word w=words.get(k);
        if(w==null){w=new Word(value);words.put(k,w);indexDirty=true;
            commonPrefixCache.clear();}
        int r=Math.max(0,rank);
        if("en".equals(language)) {
            if(r<w.enRank)w.englishSurface=value;
            w.enRank=Math.min(w.enRank,r);
        } else {
            if(r<w.deRank)w.germanSurface=value;
            w.deRank=Math.min(w.deRank,r);
        }
        if(r<w.baseRank){
            commonPrefixCache.clear();
            w.baseRank=r;
            w.lexicalSurface=value; // canonical noun/name case, not last sentence case
            if(w.typed==0)w.surface=value;
        }
    }
    public synchronized Event learnTyped(String value,String prev,String prev2) {
        return learn(value,prev,prev2,"",false);
    }
    public synchronized Event learnSelection(String typedPrefix,String chosen,String prev,String prev2) {
        return learn(chosen,prev,prev2,typedPrefix,true);
    }
    private Event learn(String raw,String prev,String prev2,String pref,boolean selected) {
        cachedLegacyOpenings=null;
        String word=norm(raw),a=norm(prev),b=norm(prev2),prefix=norm(pref);
        if(word.isEmpty()||word.length()>60)return null;
        // At a real sentence start no previous word is present. Record the
        // confirmed first word explicitly; never infer it from a dictionary.
        if(a.isEmpty()&&b.isEmpty())a=SENTENCE_START;
        Word w=words.get(word);
        if(w==null){w=new Word(raw);words.put(word,w);indexDirty=true;}
        // Explicit user confirmation wins over dictionary spelling and noun casing.
        w.surface=raw;
        commonPrefixCache.clear();
        long now=System.currentTimeMillis();
        w.lastUsedMs=now;
        w.typed++;learnedKeys.add(word);
        if(selected)w.selected++;
        String evicted=null;
        if(!a.isEmpty()) {
            adjust(pairs,key(a,word),1);
            rememberNext(a,b,word);
            evicted=PersonalNextWords.append(recentByPrevious.computeIfAbsent(a,
                k->new java.util.ArrayDeque<>(PersonalNextWords.WINDOW)),word);
        }
        if(!b.isEmpty()&&!a.isEmpty()) {
            adjust(triples,key(b,a,word),1);
            adjust(pairWithContext,key(a,word),1);
        }
        if(selected&&!prefix.isEmpty())updateChoices(b,a,prefix,word,1,now);
        return new Event(word,a,b,prefix,selected,now,evicted);
    }
    private void rememberNext(String a,String b,String word) {
        if(a.isEmpty())return;
        nextByPrevious.computeIfAbsent(a,k->new HashSet<>()).add(word);
        if(!b.isEmpty())nextByTwoPrevious.computeIfAbsent(key(b,a),k->new HashSet<>()).add(word);
    }
    private void updateChoices(String b,String a,String prefix,String word,int delta,long timestamp) {
        for(int len=1;len<=prefix.length();len++) {
            String part=prefix.substring(0,len);
            if(!a.isEmpty()||!b.isEmpty())updateChoice(b,a,part,word,delta,timestamp);
            updateChoice("","",part,word,delta,timestamp);
        }
    }
    private void updateChoice(String b,String a,String prefix,String word,int delta,long timestamp) {
        String k=key(b,a,prefix,word);
        adjust(choices,k,delta);
        if(delta>0) {
            choiceTimes.put(k,timestamp);
            latestChoiceByPrefix.merge(key(b,a,prefix),timestamp,Math::max);
        } else choiceTimes.remove(k); // Undo clears the latest token's recent preference.
    }
    public synchronized Rejection learnRejection(String shownPrefix,String candidate,String prev,String prev2) {
        String prefix=norm(shownPrefix),word=norm(candidate),a=norm(prev),b=norm(prev2);
        if(prefix.length()<2 || !word.startsWith(prefix) || word.length()==prefix.length())return null;
        if(!a.isEmpty()||!b.isEmpty())adjust(rejections,key(b,a,prefix,word),1);
        adjust(rejections,key("","",prefix,word),1);
        return new Rejection(word,a,b,prefix);
    }
    public synchronized void undo(Event e) {
        if(e==null)return;
        cachedLegacyOpenings=null;
        Word w=words.get(e.word);
        commonPrefixCache.clear();
        if(w!=null){
            w.typed=Math.max(0,w.typed-1);
            if(e.selected)w.selected=Math.max(0,w.selected-1);
            w.lastUsedMs=0;
            if(w.typed==0)learnedKeys.remove(e.word);
            if(w.typed==0 && w.baseRank==UNKNOWN_RANK){words.remove(e.word);indexDirty=true;}
        }
        if(!e.previous.isEmpty()) {
            PersonalNextWords.undoLast(recentByPrevious.get(e.previous),e.word,e.oldestEvicted);
            adjust(pairs,key(e.previous,e.word),-1);
            if(get(pairs,key(e.previous,e.word))==0) {
                Set<String> after=nextByPrevious.get(e.previous);
                if(after!=null)after.remove(e.word);
            }
        }
        if(!e.beforePrevious.isEmpty()&&!e.previous.isEmpty()) {
            adjust(triples,key(e.beforePrevious,e.previous,e.word),-1);
            adjust(pairWithContext,key(e.previous,e.word),-1);
            if(get(triples,key(e.beforePrevious,e.previous,e.word))==0) {
                Set<String> after=nextByTwoPrevious.get(key(e.beforePrevious,e.previous));
                if(after!=null)after.remove(e.word);
            }
        }
        if(e.selected&&!e.prefix.isEmpty())updateChoices(e.beforePrevious,e.previous,e.prefix,e.word,-1,0);
    }
    public synchronized void restoreWord(String surface,int typed,int selected) {
        restoreWord(surface,typed,selected,0);
    }
    public synchronized void restoreWord(String surface,int typed,int selected,long lastUsedMs) {
        cachedLegacyOpenings=null;
        String k=norm(surface);if(k.isEmpty())return;
        Word w=words.get(k);
        if(w==null){w=new Word(surface);words.put(k,w);indexDirty=true;}
        commonPrefixCache.clear();
        w.surface=surface;w.typed=Math.max(0,typed);w.selected=Math.max(0,selected);
        w.lastUsedMs=Math.max(0,lastUsedMs);
        if(w.typed>0)learnedKeys.add(k);else learnedKeys.remove(k);
    }
    public synchronized void restorePair(String a,String word,int count) {
        cachedLegacyOpenings=null;
        if(count>0){pairs.put(key(a,word),count);rememberNext(a,"",word);}
    }
    public synchronized void restoreRecent(String previous,String serialized) {
        cachedLegacyOpenings=null;
        if(previous==null||previous.isEmpty())return;
        java.util.ArrayDeque<String> tokens=PersonalNextWords.decode(serialized);
        if(!tokens.isEmpty())recentByPrevious.put(norm(previous),tokens);
    }
    public synchronized void restoreTriple(String b,String a,String word,int count) {
        if(count<=0)return;
        Integer old=triples.put(key(b,a,word),count);
        adjust(pairWithContext,key(a,word),count-(old==null?0:old));
        rememberNext(a,b,word);
    }
    public synchronized void restoreChoice(String b,String a,String prefix,String word,int count) {
        restoreChoice(b,a,prefix,word,count,0);
    }
    public synchronized void restoreChoice(String b,String a,String prefix,String word,int count,long lastSeenMs) {
        if(count>0) {
            String k=key(b,a,prefix,word);
            choices.put(k,count);
            if(lastSeenMs>0) {
                choiceTimes.put(k,lastSeenMs);
                latestChoiceByPrefix.merge(key(b,a,prefix),lastSeenMs,Math::max);
            }
        }
    }
    public synchronized void restoreRejection(String b,String a,String prefix,String word,int count) {
        if(count>0)rejections.put(key(b,a,prefix,word),count);
    }
    private void ensureIndex() {
        if(!indexDirty)return;
        sortedKeys=new ArrayList<>(words.keySet());
        Collections.sort(sortedKeys);
        commonPrefixCache.clear();
        indexDirty=false;
    }
    /** Build expensive dictionary and short-prefix indexes off the UI thread. */
    public synchronized void prepareForUse() {
        ensureIndex();
        for(String common:new String[]{"a","i","h","w","s","d","t","e","m","g","b",
            "sch","ha","ich","wo","ge","he","un"})shortPrefixCandidates(common);
    }

    /** Reset private learning without dropping the bundled base dictionary. */
    public synchronized void clearLearningData() {
        cachedLegacyOpenings=null;
        java.util.Iterator<Map.Entry<String,Word>> iter=words.entrySet().iterator();
        while(iter.hasNext()) {
            Word w=iter.next().getValue();
            if(w.baseRank==UNKNOWN_RANK)iter.remove();
            else {w.typed=0;w.selected=0;w.lastUsedMs=0;
                if(w.lexicalSurface!=null)w.surface=w.lexicalSurface;}
        }
        learnedKeys.clear();pairs.clear();triples.clear();pairWithContext.clear();recentByPrevious.clear();choices.clear();choiceTimes.clear();
        latestChoiceByPrefix.clear();rejections.clear();nextByPrevious.clear();
        nextByTwoPrevious.clear();commonPrefixCache.clear();indexDirty=true;
    }
    private int indexAtOrAfter(String word) {
        int position=Collections.binarySearch(sortedKeys,word);
        return position>=0?position:-position-1;
    }
    private double shortcutPriority(String word) {
        Word w=words.get(word);
        if(w==null)return -1000;
        double base=w.baseRank==UNKNOWN_RANK?-10:18-1.9*Math.log1p(w.baseRank);
        return base+7*Math.log1p(w.typed)+9*Math.log1p(w.selected);
    }
    private List<String> shortPrefixCandidates(String prefix) {
        List<String> cached=commonPrefixCache.get(prefix);
        if(cached!=null)return cached;
        PriorityQueue<String> heap=new PriorityQueue<>(SHORTLIST_SIZE+1,
            (a,b)->{
                int c=Double.compare(shortcutPriority(a),shortcutPriority(b));
                return c!=0?c:b.compareTo(a);
            });
        for(int i=indexAtOrAfter(prefix);i<sortedKeys.size();i++) {
            String word=sortedKeys.get(i);
            if(!word.startsWith(prefix))break;
            if(heap.size()<SHORTLIST_SIZE)heap.add(word);
            else {
                String weakest=heap.peek();
                if(shortcutPriority(word)>shortcutPriority(weakest)){
                    heap.poll();heap.add(word);
                }
            }
        }
        List<String> best=new ArrayList<>(heap);
        best.sort((a,b)->{
            int c=Double.compare(shortcutPriority(b),shortcutPriority(a));
            return c!=0?c:a.compareTo(b);
        });
        if(commonPrefixCache.size()>=128)commonPrefixCache.clear();
        commonPrefixCache.put(prefix,best);
        return best;
    }

    // Frequent words such as "to" join many unrelated constructions. A word
    // observed exclusively in a different construction needs fresh evidence.
    private boolean onlyOtherContext(String a,String b,String word,int pair,int triple) {
        Word previous=words.get(a);
        return !b.isEmpty() && triple==0 && pair>0 && previous!=null &&
            previous.baseRank<120 && get(pairWithContext,key(a,word))>=pair;
    }

    /**
     * Strict personal-history priority. If the previous word has confirmed
     * continuations matching the user's typed prefix, use ONLY those words.
     * Generic corpora and neural output are not allowed to overrule them.
     */
    private List<String> personalNext(String rawPrefix,String previous,
                                      String beforePrevious,String language) {
        String a=norm(previous),b=norm(beforePrevious),prefix=norm(rawPrefix);
        if(a.isEmpty()&&b.isEmpty())a=SENTENCE_START;
        if(a.isEmpty())return Collections.emptyList();
        Set<String> successors=nextByPrevious.get(a);
        if(successors==null||successors.isEmpty())return Collections.emptyList();
        int countTotal=0;
        for(String word:successors)countTotal+=get(pairs,key(a,word));
        if(countTotal<=0)return Collections.emptyList();
        java.util.ArrayDeque<String> recent=recentByPrevious.get(a);
        Map<String,Double> recentShares=PersonalNextWords.recentShares(recent);
        int observations=recent==null?0:recent.size();
        class Ranked {
            final String word;
            final double score;
            Ranked(String word,double score){this.word=word;this.score=score;}
        }
        List<Ranked> results=new ArrayList<>(successors.size());
        for(String word:successors) {
            if(!word.startsWith(prefix))continue;
            int count=get(pairs,key(a,word));
            int match=get(triples,key(b,a,word));
            if(count<=0 || onlyOtherContext(a,b,word,count,match))continue;
            double score=PersonalNextWords.score(count,countTotal,
                recentShares.getOrDefault(word,0.0),observations);
            if(!b.isEmpty()) {
                // An observed longer context is more specific than a frequent
                // continuation of the last word in a different sentence.
                if(match>0)score+=1.0+Math.log1p(match);
            }
            results.add(new Ranked(word,score));
        }
        if(results.isEmpty())return Collections.emptyList();
        final String contextKey=a;
        results.sort((x,y)->{
            int order=Double.compare(y.score,x.score);
            if(order!=0)return order;
            int lifetime=Integer.compare(get(pairs,key(contextKey,y.word)),get(pairs,key(contextKey,x.word)));
            return lifetime!=0?lifetime:x.word.compareTo(y.word);
        });
        List<String> out=new ArrayList<>(Math.min(3,results.size()));
        for(int i=0;i<results.size() && out.size()<3;i++) {
            String token=results.get(i).word;
            Word w=words.get(token);
            // Store the exact form the user typed for personal history, but
            // never reuse sentence-initial case as the spelling of a common
            // word in the middle of an unrelated sentence. The language's
            // baseline lexicon supplies canonical case for known vocabulary.
            String spelling=token;
            if(w!=null) {
                spelling=w.surface==null?token:w.surface;
                if("en".equals(language) && w.englishSurface!=null)
                    spelling=w.englishSurface;
                else if("de".equals(language) && w.germanSurface!=null)
                    spelling=w.germanSurface;
            }
            out.add(caseMatched(spelling,rawPrefix));
        }
        return out;
    }

    /**
     * Earlier app versions recorded personal words and adjacent word pairs,
     * but never marked which ones began a sentence. Reuse that REAL historical
     * material at an untouched field rather than generating corpus phrases or
     * leaving the suggestion bar empty. Each candidate must have been typed,
     * selected, or observed as the first word of an actual adjacent pair.
     */
    private List<String> personalOpeningBackfill(List<String> confirmedStarts) {
        // Explicitly observed starts rank first. Earlier recorded words and
        // adjacent pairs can fill remaining slots, but can never outrank a
        // confirmed sentence beginning or fabricate an unobserved phrase.
        if(confirmedStarts.size()>=3)return confirmedStarts;
        if(cachedLegacyOpenings!=null)return cachedLegacyOpenings;
        Map<String,Integer> outgoing=new HashMap<>();
        for(Map.Entry<String,Integer> item:pairs.entrySet()) {
            String path=item.getKey();
            int divider=path.indexOf('\u0001');
            if(divider<=0 || item.getValue()<=0)continue;
            String preceding=path.substring(0,divider);
            if(preceding.equals(SENTENCE_START) || !PersonalNextWords.valid(preceding))continue;
            outgoing.merge(preceding,item.getValue(),Integer::sum);
        }
        class PersonalCandidate {
            final String normalized,display;
            final double score;
            PersonalCandidate(String normalized,String display,double score) {
                this.normalized=normalized;this.display=display;this.score=score;
            }
        }
        java.util.List<PersonalCandidate> choices=new ArrayList<>();
        long now=System.currentTimeMillis();
        for(Map.Entry<String,Word> item:words.entrySet()) {
            String token=item.getKey();
            Word record=item.getValue();
            int transitions=outgoing.getOrDefault(token,0);
            if(!PersonalNextWords.valid(token))continue;
            if(record.typed<=0 && record.selected<=0 && transitions<=0)continue;
            String surface=record.surface==null?token:record.surface;
            double frequency=record.typed+2.0*record.selected;
            // Personal observations, NOT dictionary rank or neural scores.
            double score=10*Math.log1p(frequency)+7*Math.log1p(transitions);
            if(record.lastUsedMs>0 && record.lastUsedMs<=now) {
                long elapsed=now-record.lastUsedMs;
                score+=8.0*Math.exp(-elapsed/(12.0*3600.0*1000.0));
            }
            choices.add(new PersonalCandidate(token,surface,score));
        }
        // A legacy pair is itself a confirmed observation even if the old
        // word table has no entry for the preceding word.
        for(Map.Entry<String,Integer> entry:outgoing.entrySet()) {
            if(words.containsKey(entry.getKey()))continue;
            String token=entry.getKey();
            choices.add(new PersonalCandidate(token,token,7*Math.log1p(entry.getValue())));
        }
        choices.sort((left,right) -> {
            int order=Double.compare(right.score,left.score);
            return order!=0?order:left.normalized.compareTo(right.normalized);
        });
        java.util.List<String> result=new ArrayList<>(3);
        java.util.Set<String> seen=new HashSet<>();
        for(String entry:confirmedStarts) {
            if(seen.add(norm(entry)))result.add(entry);
        }
        for(PersonalCandidate entry:choices) {
            if(result.size()>=3)break;
            if(seen.add(entry.normalized))result.add(entry.display);
        }
        cachedLegacyOpenings=Collections.unmodifiableList(result);
        return cachedLegacyOpenings;
    }

    /** Whether the owner has used a matching continuation after this word. */
    public synchronized boolean hasPersonalNext(String rawPrefix,String previous) {
        String a=norm(previous),prefix=norm(rawPrefix);
        if(a.isEmpty())a=SENTENCE_START;
        Set<String> successors=nextByPrevious.get(a);
        if(successors==null)return false;
        for(String word:successors)
            if(word.startsWith(prefix) && get(pairs,key(a,word))>0)return true;
        return false;
    }

    /** Precisely observed personal ranking, without model or generic filler. */
    public synchronized List<String> predictPersonal(String rawPrefix,String prev,String prev2,
                                                     String language) {
        List<String> personal=personalNext(rawPrefix,prev,prev2,language);
        if(norm(rawPrefix).isEmpty() && norm(prev).isEmpty() && norm(prev2).isEmpty())
            return personalOpeningBackfill(personal);
        return personal;
    }
    public synchronized List<String> predict(String rawPrefix,String prev,String prev2) {
        return predict(rawPrefix,prev,prev2,Collections.emptyMap());
    }

    /** Neural scores are optional, async-only and never stored in personal history. */
    public synchronized List<String> predict(String rawPrefix,String prev,String prev2,
                                             Map<String,Double> neuralScores) {
        return predict(rawPrefix,prev,prev2,neuralScores,"");
    }
    public synchronized List<String> predict(String rawPrefix,String prev,String prev2,
                                             Map<String,Double> neuralScores,String language) {
        String prefix=norm(rawPrefix),a=norm(prev),b=norm(prev2);
        // This is the ordering rule, not another additive feature. No generic
        // model may reorder or replace previously confirmed next-word choices.
        List<String> personal=predictPersonal(rawPrefix,a,b,language);
        // Real user history always occupies the earliest positions, but does
        // not force the other two buttons to remain blank. Contextual model
        // candidates fill those gaps; they are NOT learned unless selected.
        if(personal.size()>=3)return personal;
        Map<String,Double> generic=new HashMap<>();
        if(baseline!=null)generic.putAll(baseline.candidates(prefix,a,b,language));
        if(neuralScores!=null)for(Map.Entry<String,Double> e:neuralScores.entrySet()) {
            String word=norm(e.getKey());
            if(word.startsWith(prefix) && e.getValue()!=null && e.getValue()>0)
                generic.merge(word,e.getValue(),Double::sum);
        }
        if(prefix.isEmpty()&&a.isEmpty()&&generic.isEmpty())
            return SuggestionMixer.merge(personal,
                SuggestionMixer.coldStart(rawPrefix,language,true),3,rawPrefix);
        Scored[] best=new Scored[3];
        double englishBias=languageBias(a,b);
        int exact=0;
        if(prefix.isEmpty()) {
            // Only words actually observed after this context are candidates.
            Set<String> next=new HashSet<>(nextByPrevious.getOrDefault(a,Collections.emptySet()));
            next.addAll(nextByTwoPrevious.getOrDefault(key(b,a),Collections.emptySet()));
            next.addAll(generic.keySet());
            for(String form:next) {
                Word word=words.get(form);
                if(word==null)word=new Word(form);
                if(!languageCompatible(form,word,language))continue;
                offer(best,form,word,a,b,prefix,rawPrefix,englishBias,0,
                    generic.getOrDefault(form,0.0));
            }
        } else {
            ensureIndex();
            Set<String> visited=new HashSet<>();
            if(prefix.length()<=3) {
                for(String word:shortPrefixCandidates(prefix)) {
                    Word value=words.get(word);
                    if(!languageCompatible(word,value,language))continue;
                    exact++;visited.add(word);
                    offer(best,word,value,a,b,prefix,rawPrefix,englishBias,0,
                        generic.getOrDefault(word,0.0));
                }
            } else {
                for(int i=indexAtOrAfter(prefix);i<sortedKeys.size();i++) {
                    String word=sortedKeys.get(i);
                    if(!word.startsWith(prefix))break;
                    Word value=words.get(word);
                    if(!languageCompatible(word,value,language))continue;
                    exact++;visited.add(word);
                    offer(best,word,value,a,b,prefix,rawPrefix,englishBias,0,
                        generic.getOrDefault(word,0.0));
                }
            }
            // Always include words used in this personal context even if they
            // are too rare to reach the global short-prefix shortlist.
            Set<String> contextual=nextByPrevious.getOrDefault(a,Collections.emptySet());
            for(String word:contextual)if(word.startsWith(prefix)&&visited.add(word)) {
                Word learned=words.get(word);
                if(learned!=null && languageCompatible(word,learned,language))
                    offer(best,word,learned,a,b,prefix,rawPrefix,englishBias,0,
                    generic.getOrDefault(word,0.0));
            }
            for(String word:nextByTwoPrevious.getOrDefault(key(b,a),Collections.emptySet()))
                if(word.startsWith(prefix)&&visited.add(word)) {
                    Word learned=words.get(word);
                    if(learned!=null && languageCompatible(word,learned,language))
                    offer(best,word,learned,a,b,prefix,rawPrefix,englishBias,0,
                        generic.getOrDefault(word,0.0));
                }
            for(Map.Entry<String,Double> entry:generic.entrySet()) {
                String word=entry.getKey();
                if(word.startsWith(prefix)&&visited.add(word)) {
                    Word w=words.get(word);
                    if(!languageCompatible(word,w,language))continue;
                    offer(best,word,w==null?new Word(word):w,a,b,prefix,rawPrefix,
                        englishBias,0,entry.getValue());
                }
            }
        }
        // Typo recovery uses a nearby common prefix and a hard candidate cap.
        // Long gibberish prefixes must not cause unbounded allocations or GC.
        if(exact<3 && prefix.length()>=3 && prefix.length()<=13) {
            int common=prefix.length()>=5?prefix.length()-3:Math.min(2,prefix.length()-1);
            String shared=prefix.substring(0,common);
            int scanned=0,offered=0;
            for(int i=indexAtOrAfter(shared);i<sortedKeys.size() && scanned<500;i++) {
                String form=sortedKeys.get(i);
                if(!form.startsWith(shared))break;
                scanned++;
                if(form.startsWith(prefix)||form.length()<prefix.length()-1)continue;
                if(!languageCompatible(form,words.get(form),language))continue;
                if(oneEditFromPrefix(prefix,form)) {
                    offer(best,form,words.get(form),a,b,prefix,rawPrefix,englishBias,-14,
                        generic.getOrDefault(form,0.0));
                    if(++offered>=12)break;
                }
            }
        }
        List<String> out=new ArrayList<>(3);
        for(Scored item:best)if(item!=null)out.add(item.output);
        boolean sentenceStart=prefix.isEmpty()&&a.isEmpty()&&b.isEmpty();
        // Generic words are a last resort while models are loading, or when
        // the context provides too few distinct candidates for three slots.
        List<String> supplement=SuggestionMixer.merge(out,
            SuggestionMixer.coldStart(rawPrefix,language,sentenceStart),3,rawPrefix);
        List<String> ranked=SuggestionMixer.merge(personal,supplement,3,rawPrefix);
        if(prefix.length()>=2 && personal.size()<3)
            includeCustomCompletion(ranked,rawPrefix,prefix);
        return ranked;
    }

    /** Keep an actual user-coined spelling reachable even in a different sentence. */
    private void includeCustomCompletion(List<String> ranked,String rawPrefix,String prefix) {
        for(String shown:ranked) {
            Word w=words.get(norm(shown));
            if(w!=null && w.baseRank==UNKNOWN_RANK && w.typed>0 &&
                norm(shown).startsWith(prefix))return;
        }
        Word best=null;
        for(String key:learnedKeys.tailSet(prefix,true)) {
            if(!key.startsWith(prefix))break;
            Word w=words.get(key);
            if(w.baseRank!=UNKNOWN_RANK)continue;
            int uses=w.typed+w.selected;
            if(best==null || uses>best.typed+best.selected ||
                (uses==best.typed+best.selected && w.lastUsedMs>best.lastUsedMs))best=w;
        }
        if(best==null)return;
        String value=caseMatched(best.surface,rawPrefix);
        if(ranked.size()<3)ranked.add(value);else ranked.set(2,value);
    }
    private static double recencyBonus(long lastSeenMs,double strength) {
        if(lastSeenMs<=0)return 0;
        long age=Math.max(0,System.currentTimeMillis()-lastSeenMs);
        // A new confirmed choice matters immediately; old preferences remain
        // represented in their exact occurrence counts instead of vanishing.
        double days=age/86_400_000.0;
        return strength/(1.0+days);
    }
    private double languageBias(String a,String b) {
        return signal(a)*1.2 + signal(b)*0.8;
    }
    /** Never offer dictionary-only German words in an English fallback. */
    private static final java.util.Set<String> INTERNATIONAL=
        java.util.Set.of("okay","ok","cool","internet","email","computer","app",
            "online","wifi","video","smartphone","software","chat","google","youtube");
    private boolean languageCompatible(String token,Word word,String language) {
        if(word==null || word.typed>0 || INTERNATIONAL.contains(token))return true;
        if("en".equals(language))
            return word.enRank!=UNKNOWN_RANK || word.deRank==UNKNOWN_RANK;
        if("de".equals(language))
            return word.deRank!=UNKNOWN_RANK || word.enRank==UNKNOWN_RANK;
        return true;
    }
    public synchronized boolean likelyEnglish(String rawPrefix,String previous,String beforePrevious) {
        String a=norm(previous),b=norm(beforePrevious);
        double bias=languageBias(a,b);
        if(bias < -0.35)return false;
        if(bias > 0.35)return true;
        if(rawPrefix!=null && rawPrefix.length()>=3) {
            String prefix=norm(rawPrefix);
            // Distinctive vocabulary is a useful signal in code-switched text.
            if(prefix.startsWith("sch")||prefix.startsWith("über")||
                prefix.startsWith("mö")||prefix.startsWith("dür"))return false;
        }
        return true;
    }
    /** Positive=English, negative=German; ambiguous shared words are neutral. */
    synchronized double signal(String token) {
        Word w=words.get(token);
        if(w==null || (w.enRank==UNKNOWN_RANK && w.deRank==UNKNOWN_RANK))return 0;
        if(w.enRank==UNKNOWN_RANK)return -1;
        if(w.deRank==UNKNOWN_RANK)return 1;
        // Shared vocabulary can have very different frequencies in each language.
        if((w.enRank+20L)*5 < w.deRank+20L)return 0.6;
        if((w.deRank+20L)*5 < w.enRank+20L)return -0.6;
        return 0;
    }
    private void offer(Scored[] best,String form,Word w,String a,String b,String prefix,String rawPrefix,double languageBias,double penalty,double genericScore) {
        int pair=get(pairs,key(a,form)),triple=get(triples,key(b,a,form));
        if(onlyOtherContext(a,b,form,pair,triple))pair=0;
        if(prefix.isEmpty() && pair==0 && triple==0 && genericScore<=0)return;
        double base=w.baseRank==UNKNOWN_RANK?0:Math.max(0,13-1.2*Math.log1p(w.baseRank));
        double preference=0;
        if(w.enRank!=UNKNOWN_RANK && w.deRank==UNKNOWN_RANK)preference=1;
        else if(w.deRank!=UNKNOWN_RANK && w.enRank==UNKNOWN_RANK)preference=-1;
        else if(w.enRank!=UNKNOWN_RANK && w.deRank!=UNKNOWN_RANK) {
            // "help" can appear in both corpora but is chiefly English; a
            // binary language-membership check incorrectly neutralizes it.
            preference=Math.max(-1,Math.min(1,
                Math.log((w.deRank+10.0)/(w.enRank+10.0))/2.5));
        }
        double contextLanguage=5*languageBias*preference*(0.2+0.8*Math.min(1,base/13));
        int contextualChoice=get(choices,key(b,a,prefix,form));
        if(a.isEmpty()&&b.isEmpty())contextualChoice=0; // Global choice counted separately.
        int contextualReject=get(rejections,key(b,a,prefix,form));
        if(a.isEmpty()&&b.isEmpty())contextualReject=0;
        String globalPrefix=key("","",prefix);
        String currentPrefix=key(b,a,prefix);
        long globalLast=choiceTimes.getOrDefault(key("","",prefix,form),0L);
        long contextualLast=choiceTimes.getOrDefault(key(b,a,prefix,form),0L);
        double latestPreference=(globalLast>0 && globalLast==latestChoiceByPrefix.getOrDefault(globalPrefix,0L))
            ?recencyBonus(globalLast,14):0;
        if(!a.isEmpty()||!b.isEmpty()) {
            if(contextualLast>0 && contextualLast==latestChoiceByPrefix.getOrDefault(currentPrefix,0L))
                latestPreference+=recencyBonus(contextualLast,9);
        }
        // Global popularity helps complete a typed prefix. For the next word,
        // the sentence supplies much stronger evidence than dictionary rank.
        double globalWeight=prefix.isEmpty() && !a.isEmpty()?0.15:1.0;
        double score=genericScore+globalWeight*(base+contextLanguage+6*Math.log1p(w.typed)
            +6*Math.log1p(w.selected)+recencyBonus(w.lastUsedMs,5))
            +latestPreference
            +12*Math.log1p(pair)+18*Math.log1p(triple)
            +19*Math.log1p(contextualChoice)
            +9*Math.log1p(get(choices,key("","",prefix,form)))
            +recencyBonus(choiceTimes.getOrDefault(key("","",prefix,form),0L),14)
            +(a.isEmpty()&&b.isEmpty()?0:recencyBonus(choiceTimes.getOrDefault(key(b,a,prefix,form),0L),8))
            -10*Math.log1p(contextualReject)
            -5*Math.log1p(get(rejections,key("","",prefix,form)))
            +(form.equals(prefix)?2:0)+penalty;
        String canonical=w.lexicalSurface!=null ? w.lexicalSurface : w.surface;
        // Custom names with internal capitals remain intact after repeated use.
        if(w.typed>2 && w.surface!=null && w.surface.length()>1 &&
            !w.surface.equals(w.surface.toLowerCase(Locale.GERMAN)) &&
            !w.surface.equals(w.surface.substring(0,1).toUpperCase(Locale.GERMAN)+
                w.surface.substring(1).toLowerCase(Locale.GERMAN))) canonical=w.surface;
        String output=caseMatched(canonical,rawPrefix);
        Scored candidate=new Scored(output,score);
        for(int i=0;i<best.length;i++) {
            if(best[i]==null || candidate.score>best[i].score ||
                (candidate.score==best[i].score && candidate.output.compareToIgnoreCase(best[i].output)<0)) {
                for(int j=best.length-1;j>i;j--)best[j]=best[j-1];
                best[i]=candidate;return;
            }
        }
    }
    static String caseMatched(String surface,String rawPrefix) {
        return WordCase.matchPrefix(surface,rawPrefix);
    }
    private static boolean oneEditFromPrefix(String prefix,String word) {
        int n=prefix.length();
        for(int length=Math.max(1,n-1);length<=Math.min(word.length(),n+1);length++) {
            if(editDistanceWithinOne(prefix,word.substring(0,length)))return true;
        }
        return false;
    }
    private static boolean editDistanceWithinOne(String a,String b) {
        if(Math.abs(a.length()-b.length())>1)return false;
        int i=0,j=0,errors=0;
        while(i<a.length()&&j<b.length()) {
            if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}
            if(++errors>1)return false;
            if(a.length()>b.length())i++;
            else if(a.length()<b.length())j++;
            else if(i+1<a.length()&&j+1<b.length()&&a.charAt(i)==b.charAt(j+1)&&a.charAt(i+1)==b.charAt(j)) {i+=2;j+=2;}
            else {i++;j++;}
        }
        return errors+(i<a.length()?1:0)+(j<b.length()?1:0)<=1;
    }
}
