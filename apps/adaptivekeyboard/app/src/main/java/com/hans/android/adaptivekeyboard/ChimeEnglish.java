package com.hans.android.adaptivekeyboard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Fully local Java inference for Chime-SMS-2M (CIFG-LSTM, 2.03M parameters).
 * The weights/vocabulary are copyright Luke Steuber, CC BY-SA 4.0.
 * Model format/math reimplemented with attribution from the Apache-2.0 NumPy
 * reference: https://huggingface.co/lukeslp/chime-sms-2m
 * No network, prompt logging, personal training or external ML runtime.
 */
public final class ChimeEnglish {
    public static final class Candidate {
        public final String word;
        public final float logit;
        Candidate(String word,float score){this.word=word;this.logit=score;}
    }

    private static final int V=16384,E=96,H=670,GATES=3*H,MAX_TOKENS=63;
    private static final Pattern TOKENS=Pattern.compile(
        "[\\p{L}]+(?:['’\\-][\\p{L}]+)*|[0-9]+(?:[.,:/][0-9]+)*|[^\\s]",
        0);
    private static final String PUNCT=".,?!…'\"-:;()/&$%*+=@#~_[]<>^\\\\|°";

    private static final class Matrix {
        final byte[] data;
        final float[] scale;
        final int rows,columns;
        Matrix(ByteBuffer b,int rows,int columns) throws IOException {
            this.rows=rows;this.columns=columns;
            scale=new float[rows];
            for(int row=0;row<rows;row++) {
                float s=b.getFloat();
                if(Float.isNaN(s)||Float.isInfinite(s) || s<0)throw new IOException("Invalid Chime scale");
                scale[row]=s;
            }
            data=new byte[rows*columns];b.get(data);
            b.position(b.position()+((4-(rows*columns&3))&3));
        }
    }
    private final Matrix embeddings,wx,wr,proj;
    private final float[] bias=new float[GATES];
    private final String[] vocabulary;
    private final Map<String,Integer> lookup=new HashMap<>(V*2);
    private final int[] offerIds;
    private String lastContext="";
    private float[] lastLogits;

    public ChimeEnglish(InputStream file) throws IOException {
        byte[] raw;
        try(InputStream input=file;ByteArrayOutputStream output=new ByteArrayOutputStream(2_300_000)) {
            byte[] buffer=new byte[16384];int size;
            while((size=input.read(buffer))!=-1) {
                output.write(buffer,0,size);
                if(output.size()>5_000_000)throw new IOException("Chime model too large");
            }
            raw=output.toByteArray();
        }
        if(raw.length<2000)throw new IOException("Missing model data");
        CRC32 crc=new CRC32();crc.update(raw,0,raw.length-4);
        int expected=ByteBuffer.wrap(raw,raw.length-4,4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if((int)crc.getValue()!=expected)throw new IOException("Chime model checksum mismatch");
        ByteBuffer bytes=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic=new byte[4];bytes.get(magic);
        if(!Arrays.equals(magic,"LTCF".getBytes(StandardCharsets.US_ASCII)) ||
            bytes.getInt()!=1 || bytes.getInt()!=V || bytes.getInt()!=E || bytes.getInt()!=H)
            throw new IOException("Unsupported Chime checkpoint format");
        int vocabularySize=bytes.getInt();
        embeddings=new Matrix(bytes,V,E);
        wx=new Matrix(bytes,E,GATES);
        wr=new Matrix(bytes,E,GATES);
        proj=new Matrix(bytes,H,E);
        for(int j=0;j<GATES;j++)bias[j]=bytes.getFloat();
        if(vocabularySize<=0 || vocabularySize>600_000 ||
            bytes.remaining()!=vocabularySize+4)throw new IOException("Chime vocabulary size invalid");
        byte[] tokenBytes=new byte[vocabularySize];bytes.get(tokenBytes);
        String tokenLines=new String(tokenBytes,StandardCharsets.UTF_8);
        vocabulary=tokenLines.split("\\n",-1);
        if(vocabulary.length!=V+1||!vocabulary[V].isEmpty())
            throw new IOException("Chime vocabulary mismatch");
        List<Integer> offered=new ArrayList<>(V);
        for(int i=0;i<V;i++) {
            lookup.put(vocabulary[i],i);
            if(isOfferable(vocabulary[i]))offered.add(i);
        }
        offerIds=new int[offered.size()];
        for(int i=0;i<offerIds.length;i++)offerIds[i]=offered.get(i);
    }

    static boolean isOfferable(String word) {
        if(word==null||word.isEmpty() || !Character.isLetter(word.codePointAt(0)))return false;
        if(word.length()<2 && !(word.equals("a")||word.equals("i")))return false;
        for(int i=0;i<word.length();) {
            int cp=word.codePointAt(i);
            if(!Character.isLetter(cp) && cp!='\'' && cp!='’' && cp!='-')return false;
            i+=Character.charCount(cp);
        }
        return true;
    }
    public int vocabularySize(){return V;}

    public static String activePrefix(String before) {
        if(before==null)return "";
        int i=before.length();
        while(i>0) {
            int cp=before.codePointBefore(i);
            if(!Character.isLetter(cp) && cp!='\'' && cp!='’' && cp!='-')break;
            i-=Character.charCount(cp);
        }
        String rest=before.substring(i);
        if(rest.length()==0|| !Character.isLetter(rest.codePointAt(0)))return "";
        return rest;
    }
    static String normalize(String s){return s.replace('’','\'').toLowerCase(Locale.ROOT);}
    private String normalizeToken(String token) {
        String t=normalize(token);
        if(lookup.containsKey(t))return t;
        if(Character.isDigit(t.charAt(0)))return "<num>";
        if(t.equals("…"))return t;
        if(t.equals("–")||t.equals("—"))return "-";
        if(t.equals("“")||t.equals("”"))return "\"";
        if(t.equals("。"))return ".";
        if(t.length()==1&&PUNCT.indexOf(t.charAt(0))>=0)return t;
        if(Character.isLetter(t.codePointAt(0)))return "<unk>";
        return "<sym>";
    }
    static String sentenceContext(String before) {
        if(before==null)return "";
        return GrammarTokens.sentenceBeforePrefix(before, activePrefix(before));
    }
    int[] contextIds(String before) {
        String context=sentenceContext(before);
        List<Integer> ids=new ArrayList<>(MAX_TOKENS+1);
        ids.add(1); // <s>
        Matcher matcher=TOKENS.matcher(context);
        while(matcher.find()) {
            String token=normalizeToken(matcher.group());
            Integer id=lookup.get(token);
            ids.add(id==null?3:id);
        }
        if(ids.size()>MAX_TOKENS+1) {
            int start=ids.size()-MAX_TOKENS;
            List<Integer> clipped=new ArrayList<>(MAX_TOKENS+1);
            clipped.add(1);
            clipped.addAll(ids.subList(start,ids.size()));
            ids=clipped;
        }
        int[] out=new int[ids.size()];
        for(int i=0;i<ids.size();i++)out[i]=ids.get(i);
        return out;
    }
    private static float sigmoid(float x) {
        if(x>=0)return (float)(1.0/(1+Math.exp(-x)));
        double v=Math.exp(x);return (float)(v/(1+v));
    }

    /** Inference reuses output state while the user types a partial word. */
    private float[] logitsFor(int[] context) {
        String cacheKey=Arrays.toString(context);
        if(cacheKey.equals(lastContext)&&lastLogits!=null)return lastLogits;
        float[] state=new float[H],projected=new float[E];
        float[] gates=new float[GATES],updates=new float[H];
        for(int token:context) {
            System.arraycopy(bias,0,gates,0,GATES);
            int entry=token*E;
            float embedScale=embeddings.scale[token];
            for(int e=0;e<E;e++) {
                float input=embeddings.data[entry+e]*embedScale*wx.scale[e];
                float recurrent=projected[e]*wr.scale[e];
                int index=e*GATES;
                for(int col=0;col<GATES;col++)
                    gates[col]+=input*wx.data[index+col]+recurrent*wr.data[index+col];
            }
            for(int h=0;h<H;h++) {
                float forget=sigmoid(gates[h]);
                float output=sigmoid(gates[H+h]);
                float update=(float)Math.tanh(gates[2*H+h]);
                state[h]=forget*state[h]+(1-forget)*update;
                updates[h]=output*(float)Math.tanh(state[h]);
            }
            Arrays.fill(projected,0);
            for(int h=0;h<H;h++) {
                float v=updates[h]*proj.scale[h];
                int row=h*E;
                for(int e=0;e<E;e++)projected[e]+=v*proj.data[row+e];
            }
        }
        float[] result=new float[V];
        for(int token:offerIds) {
            int row=token*E;
            float sum=0;
            for(int e=0;e<E;e++)sum+=embeddings.data[row+e]*projected[e];
            result[token]=sum*embeddings.scale[token];
        }
        lastContext=cacheKey;
        lastLogits=result;
        return result;
    }
    /** Scores candidates without modifying text; caller owns formatting and privacy. */
    public synchronized List<Candidate> predict(String before,int k) {
        if(k<1)return Collections.emptyList();
        String prefix=normalize(activePrefix(before==null?"":before));
        float[] logits=logitsFor(contextIds(before));
        int limit=Math.min(32,k);
        int[] ids=new int[limit];float[] scores=new float[limit];
        Arrays.fill(scores,Float.NEGATIVE_INFINITY);
        for(int id:offerIds) {
            if(!vocabulary[id].startsWith(prefix))continue;
            float score=logits[id];
            for(int slot=0;slot<limit;slot++) {
                if(score>scores[slot]) {
                    for(int next=limit-1;next>slot;next--) {
                        scores[next]=scores[next-1];ids[next]=ids[next-1];
                    }
                    ids[slot]=id;scores[slot]=score;break;
                }
            }
        }
        List<Candidate> results=new ArrayList<>(limit);
        for(int slot=0;slot<limit && scores[slot]!=Float.NEGATIVE_INFINITY;slot++)
            results.add(new Candidate(vocabulary[ids[slot]],scores[slot]));
        return results;
    }
}
