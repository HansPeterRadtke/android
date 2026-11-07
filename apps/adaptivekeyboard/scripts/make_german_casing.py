#!/usr/bin/env python3
"""Aggregated contextual German spelling-case statistics; Tatoeba CC BY 2.0 FR.
Usage: python make_german_casing.py de_lexicon.json de.nwp.bin deu_sentences.tsv.bz2 output.gz
No source sentences, private data or personal author names are packaged.
"""
from collections import defaultdict
from pathlib import Path
import bz2,gzip,json,re,struct,sys
lex=Path(sys.argv[1]);model=Path(sys.argv[2]);corpus=Path(sys.argv[3]);out=Path(sys.argv[4])
words={w.lower() for w in json.loads(lex.read_text(encoding='utf-8'))['words']}
b=model.read_bytes();wb=struct.unpack_from('<I',b,28)[0];words.update(b[40:40+wb].decode().splitlines())
rx=re.compile(r'[^\W\d_]+',re.UNICODE)
global_count=defaultdict(lambda:[0,0]);context_count=defaultdict(lambda:[0,0]);total=0
for line in bz2.open(corpus,'rt',encoding='utf-8',errors='replace'):
 try: text=line.split('\t',2)[2]
 except IndexError: continue
 tokens=rx.findall(text);last=''
 for j,word in enumerate(tokens):
  normalized=word.lower()
  if j>0 and normalized in words:
   idx=0 if word[0].isupper() else 1
   global_count[normalized][idx]+=1
   if last:context_count[(last,normalized)][idx]+=1
  last=normalized
 total+=1
with gzip.open(out,'wt',encoding='utf-8',compresslevel=8,newline='\n') as f:
 f.write('de-case\t1\n')
 for token,(upper,lower) in sorted(global_count.items()):
  if upper+lower>=3:f.write(f'W\t{token}\t{upper}\t{lower}\n')
 for (previous,token),(upper,lower) in sorted(context_count.items()):
  if upper+lower>=4 and token in global_count:
   f.write(f'C\t{previous}\t{token}\t{upper}\t{lower}\n')
print('sentences',total,'global words',len(global_count),'contexts',len(context_count),
  'compressed bytes',out.stat().st_size,flush=True)
for word in ('wissen','haus','essen','leben','morgen','frage','machen'):
 print(word,global_count.get(word),context_count.get(('gerne',word)),context_count.get(('das',word)))
