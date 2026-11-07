package com.hans.android.adaptivekeyboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Generic offline bigram/trigram fallback; never includes private user text. */
public final class BaselineNgrams {
    // Tatoeba examples overrepresent fictional person names. Only everyday
    // opening words are offered before any contextual text exists.
    private static final Set<String> COMMON_START=new HashSet<>(Arrays.asList(
        "ich","du","er","sie","es","wir","i","i'm","you","we","he","she","it",
        "the","a","this","that","there","what","why","where","when","how","can",
        "do","would","if","is","my","your","yes","no","please","hello",
        "das","die","der","was","wie","warum","wo","wann","wenn","heute",
        "ja","nein","bitte","hallo","vielleicht","und","aber","so","viele"));
    private static final class Candidate {
        final String token;
        final int count;
        final String language;
        Candidate(String word,int n,String lang) {token=word;count=n;language=lang;}
    }
    private final Map<String,List<Candidate>> nextAfterOne=new HashMap<>();
    private final Map<String,List<Candidate>> nextAfterTwo=new HashMap<>();
    private final List<Candidate> starts=new ArrayList<>();
    private int count;
    private static String norm(String text) {return TextContext.normalize(text);}
    private static String ctx(String a,String b) {return a+"\u0001"+b;}

    public synchronized void add(String kind,String prev2,String previous,String word,int n,String language) {
        if(n<=0 || word==null || word.isEmpty() || word.length()>60)return;
        Candidate candidate=new Candidate(norm(word),n,language);
        if("B".equals(kind))
            nextAfterOne.computeIfAbsent(norm(previous),k->new ArrayList<>(6)).add(candidate);
        else if("T".equals(kind))
            nextAfterTwo.computeIfAbsent(ctx(norm(prev2),norm(previous)),k->new ArrayList<>(6)).add(candidate);
        else if("S".equals(kind)) {
            if(!COMMON_START.contains(candidate.token))return;
            starts.add(candidate);
        }
        else return;
        count++;
    }

    public synchronized int size() {return count;}

    /** Return bounded offline scores; a personal choice carries much greater weight. */
    public synchronized Map<String,Double> candidates(String rawPrefix,String previous,String beforePrevious) {
        String prefix=norm(rawPrefix),a=norm(previous),b=norm(beforePrevious);
        Map<String,Double> scores=new LinkedHashMap<>(24);
        if(!a.isEmpty()) {
            addScores(scores,nextAfterOne.get(a),prefix,2.7,16);
            if(!b.isEmpty())addScores(scores,nextAfterTwo.get(ctx(b,a)),prefix,4.2,24);
        }
        // Without any observed personal or baseline continuation, still show
        // useful possible first words instead of a permanently empty suggestion bar.
        if(scores.size()<3) {
            addScores(scores,starts,prefix,1.25,8);
        }
        return scores;
    }
    private static void addScores(Map<String,Double> scores,List<Candidate> candidates,
                                  String prefix,double weight,double maxScore) {
        if(candidates==null)return;
        for(Candidate entry:candidates) {
            if(!entry.token.startsWith(prefix))continue;
            double score=Math.min(maxScore,weight*Math.log1p(entry.count));
            scores.merge(entry.token,score,Double::sum);
        }
    }

    public void load(InputStream file,String language) throws IOException {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                new GZIPInputStream(file),StandardCharsets.UTF_8))) {
            String first=reader.readLine();
            if(first==null || !first.equals("language\t"+language))
                throw new IOException("Unexpected or invalid baseline source language");
            String line;
            while((line=reader.readLine())!=null) {
                String[] row=line.split("\t",-1);
                if(row.length!=5)continue;
                try{add(row[0],row[1],row[2],row[3],Integer.parseInt(row[4]),language);}
                catch(NumberFormatException invalid) { /* Skip corrupted count without losing all data. */ }
            }
        }
    }
}
