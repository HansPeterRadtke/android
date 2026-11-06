# Bundled German language data and licensing

`app/src/main/assets/de_lexicon.json` contains an adapted and transformed rank-ordered German lexical list. This generated combined dataset is distributed under **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)** because it combines CC BY-SA data. Attribution does not imply endorsement.

1. **wordfreq version 3.1.1** by Robyn Speer and contributors, https://github.com/rspeer/wordfreq. The German rank ordering was derived from its `top_n_list('de', 24000)` database, which is licensed under CC BY-SA 4.0 and contains frequency information derived from several language corpora. See the original project's data credits and license text before distribution. The library code itself has a separate license. Word frequency entries have been filtered and German word surface forms adjusted.
2. **german-wordlist** by Markus Enzenberger (enz) and contributors, https://github.com/enz/german-wordlist. The lexicon is CC0 1.0 Universal (`COPYING` in its repository). Only word-surface casing was used as auxiliary data.
3. **german-nouns** by the project's maintainers and German Wiktionary contributors, https://github.com/gambolputty/german-nouns. Distributed under CC BY-SA 4.0 (see upstream `LICENSE`). Lemma/inflected noun casing was used to restore common noun capitalization.
4. A small, explicitly written list of common German place names was added to the generator to improve local capitalization.

Reproduce the lexicon using `scripts/make_lexicon.py` and the above two source files plus `wordfreq==3.1.1`. The script does not require a model inference service, phone permissions or proprietary Google lexicon. The generated input archives are not committed. The app's end-user learned word statistics belong to the user and are *not* part of this CC BY-SA reference lexicon.

License text: https://creativecommons.org/licenses/by-sa/4.0/legalcode and https://creativecommons.org/publicdomain/zero/1.0/legalcode.

GIPHY is a separate third-party online service: no GIFs are bundled with the APK, no provider API key is distributed, and its content is not covered by the lexicon's Creative Commons license. The GIF panel attributes the provider and the receiving application may impose its own content support limitations.
