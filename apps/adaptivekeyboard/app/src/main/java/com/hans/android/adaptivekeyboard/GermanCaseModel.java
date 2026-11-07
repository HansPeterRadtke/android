package com.hans.android.adaptivekeyboard;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Offline corpus-derived German noun/verb capitalization, not a hardcoded noun rule. */
public final class GermanCaseModel {
    private static final class Frequency {
        final int upper,lower;
        Frequency(int u,int l) {upper=u;lower=l;}
    }
    private final Map<String,Frequency> global=new HashMap<>();
    private final Map<String,Frequency> contextual=new HashMap<>();

    public GermanCaseModel(InputStream file) throws IOException {
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(
            new GZIPInputStream(file),StandardCharsets.UTF_8))) {
            if(!"de-case\t1".equals(reader.readLine()))throw new IOException("Invalid German casing file");
            String line;
            while((line=reader.readLine())!=null) {
                String[] r=line.split("\t",-1);
                if(r.length==4 && "W".equals(r[0]))
                    global.put(r[1],new Frequency(Integer.parseInt(r[2]),Integer.parseInt(r[3])));
                else if(r.length==5 && "C".equals(r[0]))
                    contextual.put(r[1]+"\u0001"+r[2],
                        new Frequency(Integer.parseInt(r[3]),Integer.parseInt(r[4])));
            }
        } catch(NumberFormatException e) {throw new IOException("Invalid German case statistics",e);}
    }
    public int wordCount(){return global.size();}
    private static String lastWord(String before) {
        if(before==null)return "";
        String s=before.trim();int end=s.length();
        while(end>0 && !Character.isLetter(s.charAt(end-1)))end--;
        int start=end;
        while(start>0 && Character.isLetter(s.charAt(start-1)))start--;
        return s.substring(start,end).toLowerCase(Locale.GERMAN);
    }
    private static boolean useCapital(Frequency f) {
        return f.upper>f.lower;
    }
    public String apply(String candidate,String typedPrefix,String before,boolean sentenceCap) {
        if(candidate==null||candidate.isEmpty())return "";
        if(typedPrefix!=null&&!typedPrefix.isEmpty())
            return WordCase.matchPrefix(candidate,typedPrefix);
        if(sentenceCap||WordCase.sentenceStart(before))
            return candidate.substring(0,1).toUpperCase(Locale.GERMAN)+candidate.substring(1);
        String k=candidate.toLowerCase(Locale.GERMAN);
        Frequency word=global.get(k);
        if(word==null)return candidate;
        Frequency specific=contextual.get(lastWord(before)+"\u0001"+k);
        boolean uppercase;
        if(specific!=null && specific.upper+specific.lower>=6 &&
            (specific.upper>=2*specific.lower || specific.lower>=2*specific.upper))
            uppercase=useCapital(specific);
        else uppercase=useCapital(word);
        return uppercase ? candidate.substring(0,1).toUpperCase(Locale.GERMAN)+candidate.substring(1) :
            candidate.substring(0,1).toLowerCase(Locale.GERMAN)+candidate.substring(1);
    }
}
