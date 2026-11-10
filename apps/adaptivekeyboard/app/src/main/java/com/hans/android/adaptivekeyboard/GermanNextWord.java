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

/**
 * On-device German keyboard next-word model (LSTM with tied word vectors).
 * Weight/vocabulary copyright 2026 Destin Moss / youcoded.ai, distributed
 * under ODC-By-1.0 and CC-BY-2.0-FR. See third_party/german-neural/NOTICE.md.
 * The numeric layout and equations follow the public model card's example.
 */
public final class GermanNextWord {
    public static final class Candidate {
        public final String word;
        public final float score;
        Candidate(String value,float logit) {word=value;score=logit;}
    }
    private static final int MAX_CONTEXT=20, MAX_FILE_BYTES=6_000_000;
    private static final Pattern WORDS=Pattern.compile("[\\p{L}]+(?:['’][\\p{L}]+)*");
    private static final String[] EMPTY=new String[0];

    private static final class Matrix {
        final byte[] q;
        final float[] scales;
        final int cols;
        Matrix(ByteBuffer bytes,int rows,int cols) throws IOException {
            this.cols=cols;
            if(rows<=0||cols<=0)throw new IOException("Matrix dimensions invalid");
            q=new byte[rows*cols];bytes.get(q);
            int padding=(16-((rows*cols)&15))&15;
            bytes.position(bytes.position()+padding);
            scales=new float[rows];
            for(int i=0;i<rows;i++) {
                scales[i]=bytes.getFloat();
                if(!Float.isFinite(scales[i])||scales[i]<0)
                    throw new IOException("Invalid neural matrix scale");
            }
        }
    }
    private final int vocabularySize,embeddingDim,hiddenDim;
    private final float blendWeight;
    private final String[] words;
    private final Map<String,Integer> ids;
    private final Matrix embeddings,inputWeights,recurrentWeights,projection;
    private final float[] outputBias,gateBias;
    private final int[] offerIds;
    private String cachedContext="";
    private float[] cachedLogits;

    public GermanNextWord(InputStream input) throws IOException {
        byte[] file;
        try(InputStream in=input;ByteArrayOutputStream output=new ByteArrayOutputStream(4_900_000)) {
            byte[] block=new byte[32768];int count;
            while((count=in.read(block))!=-1) {
                output.write(block,0,count);
                if(output.size()>MAX_FILE_BYTES)throw new IOException("German model too large");
            }
            file=output.toByteArray();
        }
        if(file.length<250_000)throw new IOException("German model missing");
        long hash=0xcbf29ce484222325L;
        for(int i=0;i<file.length-8;i++)hash=(hash^(file[i]&255))*0x100000001b3L;
        ByteBuffer bytes=ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic=new byte[8];bytes.get(magic);
        if(!Arrays.equals(magic,"SWKBNWP1".getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Not a German NWP model");
        int version=bytes.getInt(),dtype=bytes.getInt();
        vocabularySize=bytes.getInt();embeddingDim=bytes.getInt();hiddenDim=bytes.getInt();
        int wordBytes=bytes.getInt();
        blendWeight=bytes.getFloat();
        bytes.getFloat(); // out-of-vocabulary constant used by original ngram blend
        if(version!=1||dtype!=1||vocabularySize<3||vocabularySize>50_000||
            embeddingDim<8||embeddingDim>256||hiddenDim<16||hiddenDim>2048||
            wordBytes<100||wordBytes>1_000_000 || !Float.isFinite(blendWeight))
            throw new IOException("Unsupported German NWP checkpoint");
        byte[] text=new byte[wordBytes];bytes.get(text);
        String[] wordList=new String(text,StandardCharsets.UTF_8).split("\\n",-1);
        if(wordList.length<vocabularySize-3)throw new IOException("Corrupt German vocabulary");
        bytes.position(40+((wordBytes+15)/16)*16);
        words=new String[vocabularySize];
        words[0]="<unk>";words[1]="<s>";words[2]="</s>";
        ids=new HashMap<>(vocabularySize*2);
        for(int i=3;i<vocabularySize;i++) {
            words[i]=wordList[i-3];
            ids.put(words[i],i);
        }
        embeddings=new Matrix(bytes,vocabularySize,embeddingDim);
        outputBias=new float[vocabularySize];
        for(int i=0;i<vocabularySize;i++)outputBias[i]=bytes.getFloat();
        inputWeights=new Matrix(bytes,4*hiddenDim,embeddingDim);
        recurrentWeights=new Matrix(bytes,4*hiddenDim,embeddingDim);
        gateBias=new float[4*hiddenDim];
        for(int i=0;i<gateBias.length;i++)gateBias[i]=bytes.getFloat();
        projection=new Matrix(bytes,embeddingDim,hiddenDim);
        if(bytes.position()!=file.length-8)throw new IOException("German model binary length mismatch");
        if(bytes.getLong()!=hash)throw new IOException("German model checksum mismatch");
        List<Integer> offered=new ArrayList<>(vocabularySize);
        for(int i=3;i<vocabularySize;i++)if(isOfferable(words[i]))offered.add(i);
        offerIds=new int[offered.size()];
        for(int i=0;i<offerIds.length;i++)offerIds[i]=offered.get(i);
    }
    public int vocabularySize() {return vocabularySize;}
    public float blendWeight(){return blendWeight;}
    private static boolean isOfferable(String word) {
        if(word==null||word.isEmpty() || !Character.isLetter(word.codePointAt(0)))return false;
        for(int i=0;i<word.length();) {
            int cp=word.codePointAt(i);
            if(!Character.isLetter(cp) && cp!='\'' && cp!='’' && cp!='-')return false;
            i+=Character.charCount(cp);
        }
        return true;
    }
    /** Recognize keyboard-style umlaut substitutes without inventing new words. */
    String canonicalGerman(String word) {
        String normalized=word.toLowerCase(Locale.GERMAN).replace('’','\'');
        if(normalized.contains("ae") || normalized.contains("oe") || normalized.contains("ue")) {
            String replacement=normalized.replace("ae","ä").replace("oe","ö").replace("ue","ü");
            // A German neural model can also list ASCII variants (e.g. fuer)
            // from web text; prefer the canonical spelling if known.
            if(ids.containsKey(replacement))return replacement;
        }
        if(ids.containsKey(normalized))return normalized;
        if(normalized.contains("ss")) {
            String replacement=normalized.replace("ss","ß");
            if(ids.containsKey(replacement))return replacement;
        }
        return normalized;
    }
    private String[] sentenceWords(String before) {
        ArrayList<String> tokens=new ArrayList<>();
        Matcher matcher=WORDS.matcher(before);
        while(matcher.find())tokens.add(canonicalGerman(matcher.group()));
        return tokens.size()<=MAX_CONTEXT?tokens.toArray(EMPTY):
            tokens.subList(tokens.size()-MAX_CONTEXT,tokens.size()).toArray(EMPTY);
    }
    private static float sigmoid(float x) {
        if(x>=0)return (float)(1.0/(1.0+Math.exp(-x)));
        double y=Math.exp(x);return (float)(y/(1+y));
    }
    private float[] logits(String[] tokens) {
        String key=Arrays.toString(tokens);
        if(key.equals(cachedContext)&&cachedLogits!=null)return cachedLogits;
        int h=hiddenDim,e=embeddingDim;
        float[] cell=new float[h],projected=new float[e];
        float[] gates=new float[4*h],inputVector=new float[e];
        for(int position=-1;position<tokens.length;position++) {
            int word=position<0?1:ids.getOrDefault(tokens[position],0);
            int row=word*e;
            float scale=embeddings.scales[word];
            for(int j=0;j<e;j++)inputVector[j]=embeddings.q[row+j]*scale;
            for(int gate=0;gate<4*h;gate++) {
                int offset=gate*e;
                // For int8 matrices, a row's scale multiplies the complete dot.
                float ins=0,rec=0;
                for(int j=0;j<e;j++) {
                    ins+=inputWeights.q[offset+j]*inputVector[j];
                    rec+=recurrentWeights.q[offset+j]*projected[j];
                }
                gates[gate]=ins*inputWeights.scales[gate]+
                    rec*recurrentWeights.scales[gate]+gateBias[gate];
            }
            float[] unprojected=new float[h];
            for(int j=0;j<h;j++) {
                cell[j]=sigmoid(gates[h+j])*cell[j]+
                    sigmoid(gates[j])*(float)Math.tanh(gates[2*h+j]);
                unprojected[j]=sigmoid(gates[3*h+j])*(float)Math.tanh(cell[j]);
            }
            for(int j=0;j<e;j++) {
                float dot=0;int offset=j*h;
                for(int k=0;k<h;k++)dot+=projection.q[offset+k]*unprojected[k];
                projected[j]=dot*projection.scales[j];
            }
        }
        float[] logits=new float[vocabularySize];
        for(int i=3;i<vocabularySize;i++) {
            float dot=0;int start=i*e;
            for(int j=0;j<e;j++)dot+=embeddings.q[start+j]*projected[j];
            logits[i]=dot*embeddings.scales[i]+outputBias[i];
        }
        cachedContext=key;cachedLogits=logits;
        return logits;
    }
    /** Returns the highest-scored German next-word candidates, excluding context. */
    public synchronized List<Candidate> predict(String beforeCursor,int requested) {
        int limit=Math.min(32,Math.max(0,requested));
        if(limit==0)return Collections.emptyList();
        String text = beforeCursor == null ? "" : beforeCursor;
        String typedPrefix = ChimeEnglish.activePrefix(text);
        String prefix = typedPrefix.toLowerCase(Locale.GERMAN);
        float[] scores = logits(sentenceWords(GrammarTokens.sentenceBeforePrefix(text, typedPrefix)));
        float[] best=new float[limit];int[] indices=new int[limit];
        Arrays.fill(best,Float.NEGATIVE_INFINITY);
        for(int id:offerIds) {
            String word=words[id];
            if(!word.startsWith(prefix))continue;
            float score=scores[id];
            for(int j=0;j<limit;j++) {
                if(score>best[j]) {
                    for(int k=limit-1;k>j;k--){best[k]=best[k-1];indices[k]=indices[k-1];}
                    best[j]=score;indices[j]=id;break;
                }
            }
        }
        List<Candidate> result=new ArrayList<>(limit);
        for(int j=0;j<limit && best[j]!=Float.NEGATIVE_INFINITY;j++)
            result.add(new Candidate(words[indices[j]],best[j]));
        return result;
    }
}
