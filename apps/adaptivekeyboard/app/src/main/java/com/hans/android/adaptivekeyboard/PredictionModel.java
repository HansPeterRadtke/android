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
    private static final class Word {
        String surface;
        String lexicalSurface;
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
        private Event(String word,String previous,String beforePrevious,String prefix,boolean selected,long timestamp) {
            this.word=word;this.previous=previous;this.beforePrevious=beforePrevious;this.prefix=prefix;
            this.selected=selected;this.timestamp=timestamp;
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
    private List<String> sortedKeys=Collections.emptyList();
    private boolean indexDirty=true;
    // Prefixes of one or two characters can match thousands of words. Only
    // a bounded, relevance-ranked shortlist is needed for three suggestions.
    private final Map<String,List<String>> commonPrefixCache=new HashMap<>();
    private static final int SHORTLIST_SIZE=160;
    private final Map<String,Set<String>> nextByPrevious=new HashMap<>();
    private final Map<String,Set<String>> nextByTwoPrevious=new HashMap<>();
    private final Map<String,Integer> pairs=new HashMap<>();
    private final Map<String,Integer> triples=new HashMap<>();
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
        if("en".equals(language))w.enRank=Math.min(w.enRank,r);
        else w.deRank=Math.min(w.deRank,r);
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
        String word=norm(raw),a=norm(prev),b=norm(prev2),prefix=norm(pref);
        if(word.isEmpty()||word.length()>60)return null;
        Word w=words.get(word);
        if(w==null){w=new Word(raw);words.put(word,w);indexDirty=true;}
        // Explicit user confirmation wins over dictionary spelling and noun casing.
        w.surface=raw;
        commonPrefixCache.clear();
        long now=System.currentTimeMillis();
        w.lastUsedMs=now;
        w.typed++;
        if(selected)w.selected++;
        if(!a.isEmpty()) {
            adjust(pairs,key(a,word),1);
            rememberNext(a,b,word);
        }
        if(!b.isEmpty()&&!a.isEmpty())adjust(triples,key(b,a,word),1);
        if(selected&&!prefix.isEmpty())updateChoices(b,a,prefix,word,1,now);
        return new Event(word,a,b,prefix,selected,now);
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
        Word w=words.get(e.word);
        commonPrefixCache.clear();
        if(w!=null){
            w.typed=Math.max(0,w.typed-1);
            if(e.selected)w.selected=Math.max(0,w.selected-1);
            w.lastUsedMs=0;
            if(w.typed==0 && w.baseRank==UNKNOWN_RANK){words.remove(e.word);indexDirty=true;}
        }
        if(!e.previous.isEmpty()) {
            adjust(pairs,key(e.previous,e.word),-1);
            if(get(pairs,key(e.previous,e.word))==0) {
                Set<String> after=nextByPrevious.get(e.previous);
                if(after!=null)after.remove(e.word);
            }
        }
        if(!e.beforePrevious.isEmpty()&&!e.previous.isEmpty()) {
            adjust(triples,key(e.beforePrevious,e.previous,e.word),-1);
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
        String k=norm(surface);if(k.isEmpty())return;
        Word w=words.get(k);
        if(w==null){w=new Word(surface);words.put(k,w);indexDirty=true;}
        commonPrefixCache.clear();
        w.surface=surface;w.typed=Math.max(0,typed);w.selected=Math.max(0,selected);
        w.lastUsedMs=Math.max(0,lastUsedMs);
    }
    public synchronized void restorePair(String a,String word,int count) {
        if(count>0){pairs.put(key(a,word),count);rememberNext(a,"",word);}
    }
    public synchronized void restoreTriple(String b,String a,String word,int count) {
        if(count>0){triples.put(key(b,a,word),count);rememberNext(a,b,word);}
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
        java.util.Iterator<Map.Entry<String,Word>> iter=words.entrySet().iterator();
        while(iter.hasNext()) {
            Word w=iter.next().getValue();
            if(w.baseRank==UNKNOWN_RANK)iter.remove();
            else {w.typed=0;w.selected=0;w.lastUsedMs=0;
                if(w.lexicalSurface!=null)w.surface=w.lexicalSurface;}
        }
        pairs.clear();triples.clear();choices.clear();choiceTimes.clear();
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
        Map<String,Double> generic=new HashMap<>();
        if(baseline!=null)generic.putAll(baseline.candidates(prefix,a,b,language));
        if(neuralScores!=null)for(Map.Entry<String,Double> e:neuralScores.entrySet()) {
            String word=norm(e.getKey());
            if(word.startsWith(prefix) && e.getValue()!=null && e.getValue()>0)
                generic.merge(word,e.getValue(),Double::sum);
        }
        if(prefix.isEmpty()&&a.isEmpty()&&generic.isEmpty())return Collections.emptyList();
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
                offer(best,form,word,a,b,prefix,rawPrefix,englishBias,0,
                    generic.getOrDefault(form,0.0));
            }
        } else {
            ensureIndex();
            Set<String> visited=new HashSet<>();
            if(prefix.length()<=3) {
                for(String word:shortPrefixCandidates(prefix)) {
                    exact++;visited.add(word);
                    offer(best,word,words.get(word),a,b,prefix,rawPrefix,englishBias,0,
                        generic.getOrDefault(word,0.0));
                }
            } else {
                for(int i=indexAtOrAfter(prefix);i<sortedKeys.size();i++) {
                    String word=sortedKeys.get(i);
                    if(!word.startsWith(prefix))break;
                    exact++;visited.add(word);
                    offer(best,word,words.get(word),a,b,prefix,rawPrefix,englishBias,0,
                        generic.getOrDefault(word,0.0));
                }
            }
            // Always include words used in this personal context even if they
            // are too rare to reach the global short-prefix shortlist.
            Set<String> contextual=nextByPrevious.getOrDefault(a,Collections.emptySet());
            for(String word:contextual)if(word.startsWith(prefix)&&visited.add(word)) {
                Word learned=words.get(word);
                if(learned!=null)offer(best,word,learned,a,b,prefix,rawPrefix,englishBias,0,
                    generic.getOrDefault(word,0.0));
            }
            for(String word:nextByTwoPrevious.getOrDefault(key(b,a),Collections.emptySet()))
                if(word.startsWith(prefix)&&visited.add(word)) {
                    Word learned=words.get(word);
                    if(learned!=null)offer(best,word,learned,a,b,prefix,rawPrefix,englishBias,0,
                        generic.getOrDefault(word,0.0));
                }
            for(Map.Entry<String,Double> entry:generic.entrySet()) {
                String word=entry.getKey();
                if(word.startsWith(prefix)&&visited.add(word)) {
                    Word w=words.get(word);
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
                if(oneEditFromPrefix(prefix,form)) {
                    offer(best,form,words.get(form),a,b,prefix,rawPrefix,englishBias,-14,
                        generic.getOrDefault(form,0.0));
                    if(++offered>=12)break;
                }
            }
        }
        List<String> out=new ArrayList<>(3);
        for(Scored item:best)if(item!=null)out.add(item.output);
        return out;
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
    private double signal(String token) {
        Word w=words.get(token);
        if(w==null)return 0;
        if(w.enRank==UNKNOWN_RANK)return -1;
        if(w.deRank==UNKNOWN_RANK)return 1;
        if(w.enRank<80 && w.deRank>600)return 0.6;
        if(w.deRank<80 && w.enRank>600)return -0.6;
        return 0;
    }
    private void offer(Scored[] best,String form,Word w,String a,String b,String prefix,String rawPrefix,double languageBias,double penalty,double genericScore) {
        int pair=get(pairs,key(a,form)),triple=get(triples,key(b,a,form));
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
        double score=base+contextLanguage+genericScore+6*Math.log1p(w.typed)+6*Math.log1p(w.selected)
            +latestPreference
            +12*Math.log1p(pair)+18*Math.log1p(triple)
            +19*Math.log1p(contextualChoice)
            +9*Math.log1p(get(choices,key("","",prefix,form)))
            +recencyBonus(w.lastUsedMs,5)
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
