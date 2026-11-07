# Bundled German language data and licensing

`app/src/main/assets/de_lexicon.json` contains an adapted and transformed rank-ordered German lexical list. This generated combined dataset is distributed under **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)** because it combines CC BY-SA data. Attribution does not imply endorsement.

1. **wordfreq version 3.1.1** by Robyn Speer and contributors, https://github.com/rspeer/wordfreq. The German rank ordering was derived from its `top_n_list('de', 24000)` database, which is licensed under CC BY-SA 4.0 and contains frequency information derived from several language corpora. See the original project's data credits and license text before distribution. The library code itself has a separate license. Word frequency entries have been filtered and German word surface forms adjusted.
2. **german-wordlist** by Markus Enzenberger (enz) and contributors, https://github.com/enz/german-wordlist. The lexicon is CC0 1.0 Universal (`COPYING` in its repository). Only word-surface casing was used as auxiliary data.
3. **german-nouns** by the project's maintainers and German Wiktionary contributors, https://github.com/gambolputty/german-nouns. Distributed under CC BY-SA 4.0 (see upstream `LICENSE`). Lemma/inflected noun casing was used to restore common noun capitalization.
4. A small, explicitly written list of common German place names was added to the generator to improve local capitalization.

Reproduce the lexicon using `scripts/make_lexicon.py` and the above two source files plus `wordfreq==3.1.1`. The script does not require a model inference service, phone permissions or proprietary Google lexicon. The generated input archives are not committed. The app's end-user learned word statistics belong to the user and are *not* part of this CC BY-SA reference lexicon.

License text: https://creativecommons.org/licenses/by-sa/4.0/legalcode and https://creativecommons.org/publicdomain/zero/1.0/legalcode.

GIPHY is a separate third-party online service: no GIFs are bundled with the APK, no provider API key is distributed, and its content is not covered by the lexicon's Creative Commons license. The GIF panel attributes the provider and the receiving application may impose its own content support limitations.

## English vocabulary and keyless GIF search (v0.2.0)

`app/src/main/assets/en_lexicon.json` is a filtered rank-ordered English list generated with `wordfreq==3.1.1`, from the same contributor project and its CC BY-SA 4.0 underlying data. Regenerate it using `scripts/make_english_lexicon.py` in an isolated environment with `wordfreq==3.1.1`. It is adapted and distributed under CC BY-SA 4.0; original licensing and source corpus acknowledgements still apply.

Openverse is a WordPress/open-source search service. Its image search API permits limited unauthenticated queries. The keyless GIF panel filters to Wikimedia Commons GIFs and displays the author/license when available; each retrieved file has its own terms, usually Creative Commons licenses, which the sender should respect. No Commons imagery is bundled with the APK. Sources: https://api.openverse.org/v1/ , https://commons.wikimedia.org/ , and https://docs.openverse.org/ .

## GIF provider access and rights (v0.6.0)

The user may enter their own GIPHY developer API key in the Android Settings activity. Keys are private local app configuration and are not included in GitHub, builds, bug reports, analytics, or normal typed content. The user's actual GIPHY search query is sent only after explicitly searching in the GIF panel. The integration is marked `Powered By GIPHY` and accepts G, PG, PG-13, and R ratings. GIPHY's rating scheme and hosted media remain subject to GIPHY's developer and content terms, which are independent of the app's CC BY-SA word data: https://developers.giphy.com/docs/ and https://developers.giphy.com/docs/optional-settings/rating/ . A test-key rate limit is not a license to mirror or resell GIPHY media; this implementation uses platform-supplied media URLs. Binary downloads are limited to the user-initiated image share operation and bounded temporary Android cache.

Tenor's third-party API was terminated in June 2026 by Google, but its official `tenor.com/search/` website remains accessible in users' browsers. The keyboard's `Tenor-Web` entry opens the browser rather than accessing a discontinued API or extracting website data. The user must share or save content through the website/browser's own interface. No Tenor content is bundled or ingested into a local library.

KLIPY is a viable official competitor but its current documentation requests prior provider approval for custom integrations, caching or mixing sources. This version does not bypass that requirement or reuse third-party keys. The keyless Google Image Search approach used by independent software was not copied: automated search scraping is contrary to Google's stated machine-traffic restrictions. Wikimedia Commons and Openverse remain separate, clearly labelled limited educational GIF sources; each result has its own license. No unrestricted anonymous reaction-GIF API was verified, and the app does not pretend otherwise.
