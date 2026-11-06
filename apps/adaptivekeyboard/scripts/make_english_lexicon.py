#!/usr/bin/env python3
"""Rebuild the ordered English lexicon (CC BY-SA, wordfreq 3.1.1).
usage: python3 scripts/make_english_lexicon.py app/src/main/assets/en_lexicon.json
"""
from pathlib import Path
import json,sys
from wordfreq import top_n_list
words = []
seen = set()
for word in top_n_list('en', 40000):
    if len(word) < 1 or len(word) > 45: continue
    if len(word)==1 and word not in ('i','a'): continue
    if not all(c.isalpha() or c in "'’" for c in word): continue
    if word[0] in "'’" or word[-1] in "'’": continue
    w=word.lower()
    if w in seen: continue
    seen.add(w); words.append(word)
meta = {'language':'en','license':'CC-BY-SA-4.0','data_source':'wordfreq 3.1.1 by Robyn Speer and contributors, https://github.com/rspeer/wordfreq','words':words}
Path(sys.argv[1]).write_text(json.dumps(meta,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('English lexicon',len(words),'words')
