#!/usr/bin/env python3
"""Create compact *baseline* next-word candidates from attributed Tatoeba sentences.

Only aggregated frequency tuples (not raw sentences or authors' writing) are bundled.
The source files are downloaded from Tatoeba's official, weekly CC BY 2.0 FR
per-language exports. See THIRD_PARTY_DATA.md for source and attribution.

Usage: python scripts/make_next_word_data.py eng_sentences.tsv.bz2 deu_sentences.tsv.bz2 assets-dir
"""
from collections import Counter,defaultdict
from pathlib import Path
import re,sys,bz2,gzip,json,random

alphabet=set('abcdefghijklmnopqrstuvwxyzäöüßáéíóúàèìòùçñâêîôûœæ')
pattern=re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*", re.UNICODE)


def extract(sentence):
    if len(sentence)>210 or 'http' in sentence.lower():return []
    terms=[]
    for token in pattern.findall(sentence):
        t=token.lower().replace('’',"'")
        if len(t)>35 or not all(c in alphabet or c=="'" for c in t):return []
        terms.append(t)
    return terms if 3<=len(terms)<=24 else []


def build(source, language, destination, stride, skip):
    pairs=Counter(); triples=Counter(); starters=Counter()
    included=0; total=0
    with bz2.open(source,'rt',encoding='utf-8',errors='replace') as f:
        for line in f:
            parts=line.rstrip('\n').split('\t',2)
            total+=1
            if len(parts)<3 or total%stride!=skip:continue
            words=extract(parts[2])
            if not words or ('tom' in words or 'mary' in words or 'muiriel' in words):continue
            included+=1
            starters[words[0]]+=1
            for i in range(1,len(words)):
                pairs[(words[i-1],words[i])]+=1
                if i>1: triples[(words[i-2],words[i-1],words[i])]+=1
    # Deterministic top next tokens per context, suitable for mobile memory.
    data=[]
    for typ, counts, min_occ, top_k in (("B",pairs,3,6),("T",triples,2,6)):
        contexts=defaultdict(list)
        for key,count in counts.items():
            if count<min_occ:continue
            if typ=='B': contexts[('',key[0])].append((key[1],count))
            else:contexts[(key[0],key[1])].append((key[2],count))
        for (prev2,prev), values in contexts.items():
            for word,count in sorted(values,key=lambda x:(-x[1],x[0]))[:top_k]:
                data.append((typ,prev2,prev,word,count))
    for word,count in sorted(starters.items(),key=lambda x:(-x[1],x[0]))[:60]:
        data.append(('S','','',word,count))
    data.sort(key=lambda x:(x[0],x[1],x[2],-x[4],x[3]))
    with gzip.open(destination,'wt',encoding='utf-8',compresslevel=8,newline='\n') as f:
        f.write('language\t'+language+'\n')
        for typ,prev2,prev,word,count in data:
            f.write(f'{typ}\t{prev2}\t{prev}\t{word}\t{count}\n')
    print(language,'total lines',total,'included',included,'bigram entries',sum(v[0]=='B' for v in data),
          'trigram entries',sum(v[0]=='T' for v in data),'starters',sum(v[0]=='S' for v in data),
          'gzip bytes',destination.stat().st_size,flush=True)
    for pref in [(('ich','bin') if language=='de' else ('i','am'))]:
        top=[(v,c) for (a,b,v),c in triples.items() if a==pref[0] and b==pref[1]]
        print('  context',pref,'->',sorted(top,key=lambda e:-e[1])[:12],flush=True)
    print('  common',starters.most_common(10),flush=True)

if __name__=='__main__':
    en,de,out=sys.argv[1:];path=Path(out);path.mkdir(parents=True,exist_ok=True)
    build(en,'en',path/'en_next_words.ngram',stride=4,skip=0)
    build(de,'de',path/'de_next_words.ngram',stride=2,skip=0)
