package com.hans.android.adaptivekeyboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Ranked joint continuations. Store UTF-8 rows and their offsets, decoding only matches. */
final class PhraseTable {
    private final byte[] data;
    private final int[] offsets;

    PhraseTable(InputStream stream,String language) throws IOException {
        try(GZIPInputStream gzip=new GZIPInputStream(stream);
            ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[32768];
            int n;
            while((n=gzip.read(buffer))!=-1)out.write(buffer,0,n);
            data=out.toByteArray();
        }
        byte[] header=("phrases-1\t"+language+"\n").getBytes(StandardCharsets.UTF_8);
        if(data.length<header.length || data[data.length-1]!='\n')
            throw new IOException("Invalid phrase table");
        for(int i=0;i<header.length;i++)
            if(header[i]!=data[i])throw new IOException("Wrong phrase table header");
        int rows=0;
        for(int i=header.length;i<data.length;i++)if(data[i]=='\n')rows++;
        offsets=new int[rows+1];
        offsets[0]=header.length;
        int row=1;
        for(int i=header.length;i<data.length;i++)if(data[i]=='\n')offsets[row++]=i+1;
    }

    private int compare(int offset,byte[] key) {
        for(int i=0;i<key.length;i++) {
            int next=data[offset+i]&255;
            if(next=='\t')return -1;
            int diff=next-(key[i]&255);
            if(diff!=0)return diff;
        }
        return data[offset+key.length]=='\t'?0:1;
    }

    private List<String> lookup(String context) {
        byte[] key=context.getBytes(StandardCharsets.UTF_8);
        int low=0,high=offsets.length-2;
        while(low<=high) {
            int mid=(low+high)>>>1;
            int comparison=compare(offsets[mid],key);
            if(comparison<0)low=mid+1;
            else if(comparison>0)high=mid-1;
            else {
                int from=offsets[mid]+key.length+1;
                String pairs=new String(data,from,offsets[mid+1]-from-1,StandardCharsets.UTF_8);
                return Arrays.asList(pairs.split("\t"));
            }
        }
        return Collections.emptyList();
    }

    List<String> complete(String before,List<String> preferred,PhrasePredictor.Casing casing,int limit) {
        if(limit<=0)return Collections.emptyList();
        String prefix=ChimeEnglish.activePrefix(before);
        String typed=TextContext.normalize(prefix);
        String left=before.substring(0,before.length()-prefix.length());
        List<GrammarTokens.Token> tokens=GrammarTokens.tokenize(left);
        int end=tokens.size(),start=end;
        while(start>0 && end-start<3 && tokens.get(start-1).word)start--;
        Set<String> result=new LinkedHashSet<>();
        boolean noWords=start==end;
        if(noWords && typed.isEmpty())return Collections.emptyList();
        List<String> context=new ArrayList<>(3);
        String beforeContext=noWords?left:left.substring(0,tokens.get(start).start);
        if(end-start<3 && GrammarTokens.isSentenceStart(beforeContext))context.add("^");
        for(int i=start;i<end;i++)context.add(GrammarTokens.normalize(tokens.get(i).text));
        for(int i=0;i<context.size() && result.size()<limit;i++) {
            String key=String.join(" ",context.subList(i,context.size()));
            List<String> eligible=new ArrayList<>();
            for(String pair:lookup(key))
                if(pair.startsWith(typed))eligible.add(pair);
            // A trusted first word still needs a continuation attested after
            // this same suffix. The longer row also covers pairs outside top 24.
            Set<String> used=new HashSet<>();
            for(String word:preferred) {
                String first=TextContext.normalize(word);
                if(!PhrasePredictor.validWord(first) || !first.startsWith(typed) || used.contains(first))continue;
                String chosen=null;
                for(String pair:eligible)if(pair.startsWith(first+" ")) {chosen=pair;break;}
                if(chosen==null && context.size()-i<3) {
                    List<String> longer=lookup(key+" "+first);
                    if(!longer.isEmpty()) {
                        String continuation=longer.get(0);
                        chosen=first+" "+continuation.substring(0,continuation.indexOf(' '));
                    }
                }
                // A second preferred first word must also rank highly as a pair.
                // Reserve the remaining slot for the most frequent whole phrase.
                if(chosen==null || (!used.isEmpty() &&
                    !eligible.subList(0,Math.min(6,eligible.size())).contains(chosen)))continue;
                if(add(result,chosen,prefix,left,casing))used.add(first);
                if(used.size()==2 || result.size()==limit)break;
            }
            for(String pair:eligible) {
                if(result.size()==limit)break;
                String first=pair.substring(0,pair.indexOf(' '));
                if(!used.contains(first) && add(result,pair,prefix,left,casing))used.add(first);
            }
            for(String pair:eligible) {
                if(result.size()==limit)break;
                add(result,pair,prefix,left,casing);
            }
        }
        if(result.size()<limit && !typed.isEmpty()) {
            // The first word is already in the field. Preserve it and offer its
            // observed successors even when this is the first word of a clause.
            List<String> starts=new ArrayList<>();
            starts.add(typed);
            if(noWords)starts.addAll(preferred);
            for(String word:starts) {
                String first=TextContext.normalize(word);
                if(!first.startsWith(typed))continue;
                for(String pair:lookup(first)) {
                    String second=pair.substring(0,pair.indexOf(' '));
                    add(result,first+" "+second,prefix,left,casing);
                    if(result.size()==limit)return new ArrayList<>(result);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static boolean add(Set<String> result,String pair,String prefix,String left,PhrasePredictor.Casing casing) {
        int split=pair.indexOf(' ');
        String first=pair.substring(0,split),second=pair.substring(split+1);
        if(!PhrasePredictor.validWord(first) || !PhrasePredictor.validWord(second) || first.equals(second))return false;
        String display=casing.format(first,prefix,left);
        return result.add(display+" "+casing.format(second,"",left+display+" "));
    }
}
