#!/usr/bin/env python3
"""Reproducible CC BY-SA German ranking plus CC0 casing/noun case enrichment.
Run in an isolated Python environment with wordfreq==3.1.1.
Call: python scripts/make_lexicon.py /path/to/enz-words /path/to/german-nouns.csv /target/de_lexicon.json
These input sources must be legally acquired and credited in THIRD_PARTY_DATA.md.
"""
import csv
import json
import sys
from pathlib import Path
from wordfreq import top_n_list

case={}
for w in Path(sys.argv[1]).read_text(encoding='utf-8').splitlines():
    if len(w)>1 and w.isalpha():
        k=w.lower()
        if k not in case or (case[k][0].isupper() and w[0].islower()):
            case[k]=w
nouns={}
with open(sys.argv[2],encoding='utf-8',newline='') as f:
    for row in csv.DictReader(f):
        for name, val in row.items():
            if not name or not (name == 'lemma' or name.startswith(('nominativ','akkusativ','dativ','genitiv'))):
                continue
            if val and len(val)>1 and val.isalpha() and val[0].isupper():
                nouns[val.lower()]=val
common_names = 'Berlin Deutschland Europa Schweiz Frankreich Österreich Italien Amerika China Japan England Bayern Stuttgart Hamburg München Köln Frankfurt Bodensee Ravensburg Salem Überlingen Markdorf Zürich Freiburg Ulm'.split()
for n in common_names: nouns.setdefault(n.lower(),n)
# Very frequent conjunctions/pronouns/verbs should not be rendered as nouns
# merely because an obscure capitalized dictionary form exists.
# Most frequent surface forms are overwhelmingly lowercase function words.
# A noun database alone cannot disambiguate e.g. ich (das Ich) or würde (die Würde).
common_nouns = set('Zeit Jahr Jahre Welt Mensch Menschen Mann Frau Arbeit Leben Haus Tag Tage Frage Fragen Recht Stadt Land Kinder Kind Woche Wochen Uhr Ende Teil Teile Fall Leute Person Prozent Regierung Deutschland Euro Geld Problem Schule'.lower().split())
words=[]
for rank,w in enumerate(top_n_list('de',24000)):
    if len(w)<2 or not w.isalpha() or len(w)>45: continue
    s=nouns.get(w,case.get(w,w))
    if rank < 150 and w not in common_nouns:
        s=w
    words.append(s)
metadata={
    'language':'de-DE','format':'rank-ordered-forms',
    'license':'CC-BY-SA-4.0 (adapted combined lexicon)',
    'data_source':'wordfreq 3.1.1 by Robyn Speer, https://github.com/rspeer/wordfreq',
    'case_source':'enz/german-wordlist (CC0-1.0), https://github.com/enz/german-wordlist',
    'noun_forms':'gambolputty/german-nouns (CC-BY-SA-4.0; Wiktionary contributors), https://github.com/gambolputty/german-nouns',
    'credits':'See THIRD_PARTY_DATA.md distributed with source and in app settings',
    'count':len(words),'words':words
}
Path(sys.argv[3]).write_text(json.dumps(metadata,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('wrote',len(words),'word forms,',len(nouns),'nouns')
for sample in ('haus','mann','arbeit','zeit','frau','ich','tastatur','berlin','würde'):
    print(sample,'->',words[[x.lower() for x in words].index(sample)] if sample in [x.lower() for x in words] else 'not present')
