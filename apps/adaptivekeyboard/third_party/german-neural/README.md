---
license: other
license_name: odc-by-1.0-and-cc-by-2.0-fr
license_link: https://huggingface.co/youcoded/next-word-german/blob/main/NOTICE.md
language:
- de
library_name: custom
pipeline_tag: text-generation
tags:
- next-word-prediction
- keyboard
- on-device
- lstm
- int8
- swipe-keyboard
datasets:
- HuggingFaceFW/fineweb-2
model-index:
- name: next-word-german
  results:
  - task:
      type: text-generation
      name: Next-word suggestion (this model blended with the n-gram pack)
    dataset:
      name: Common Voice everyday sentences, first 300 (CC0)
      type: custom
    metrics:
    - type: accuracy
      value: 25.04
      name: right word in top 3 (word-level, case-insensitive)
  - task:
      type: text-generation
      name: Next-word suggestion (this model blended with the n-gram pack)
    dataset:
      name: FineWeb-2 test pages, first 300 sentences
      type: custom
    metrics:
    - type: accuracy
      value: 24.22
      name: right word in top 3 (word-level, case-insensitive)
  - task:
      type: text-generation
      name: Next-word suggestion (this model blended with the n-gram pack)
    dataset:
      name: Tatoeba held-out tenth, first 300 sentences
      type: custom
    metrics:
    - type: accuracy
      value: 31.57
      name: right word in top 3 (word-level, case-insensitive)
---

# next-word-german

A small offline model that guesses the next word of a German sentence. It is built for the [Swipe Keyboard](https://github.com/itsdestin/swipe-keyboard), an open-source swipe-typing and word-prediction add-on for KDE Plasma's on-screen keyboard, but the file is plain and can be read by any program.

Models trained and released by Destin Moss via youcoded.ai, 2026

| | |
|---|---|
| File | `de.nwp.bin`, 4,718,096 bytes (4.7 MB) |
| Language | German |
| Shape | one-layer LSTM, word vectors of 128 numbers, 1,024 hidden units, 23,987 words, 8-bit weights (about 4.3 million numbers) |
| Licence | ODC-By 1.0 and CC BY 2.0 FR (attribution only, see the end) |
| SHA-256 | `bb881b20d227c7b1ed7c9a04d67171b08c25494a10b2f11138eb7914194dac84` |

It is not a chat model and cannot write text. Given the words so far in a sentence, it gives a probability for each of its 23,987 words, and it remembers nothing between sentences.

## How it was built

- **Data:** FineWeb-2 (`deu_Latn`) web text (Hugging Face, from Common Crawl), plus German sentences from Tatoeba whose id does not end in 0. Text was lower-cased and cut into sentences of letter-only words; web pages that look like forums or Q&A were counted twice.
- **Vocabulary:** the 23,987 most frequent words that are also in the keyboard's word list (so every suggestion is something the keyboard can show).
- **Held out:** any training sentence sharing an 8-word run with a test sentence was dropped. Tatoeba ids ending in 0 are never trained on.
- **Training:** `scripts/lm/nwp/train.py` (PyTorch), from scratch. The model reads whole sentences starting from a sentence-start token, one pass over the data, AdamW, bfloat16, one NVIDIA RTX 4070 Super. The exact command-line flags of the shipped runs and the final loss were not recorded, so a re-run will come close, not match exactly.
- **Export:** `scripts/lm/nwp/export.py` rounds the weights to 8 bits (one scale per row) and stores two blend settings in the file header (see below). Scripts: [github.com/itsdestin/swipe-keyboard](https://github.com/itsdestin/swipe-keyboard) (currently on branch `feature/179-engine-in-keyboard`).

## How to use it

**Who it is for:** developers who want small, offline next-word suggestions in a keyboard or text box, with no network and no machine-learning library. The keyboard's own C++ reader is `engine/src/nwp_model.cpp` (about 250 lines).

**Use it together with an n-gram pack.** The model knows only 23,987 words but reads the whole sentence. The keyboard blends it with a separate word-pair/word-triple file (`de.ngram` and `de.vocab.tsv`, GitHub release `language-files-2026-10-23-apostrophes`) that knows more words: for words both know, score = 0.7 x model + 0.3 x n-gram (log probabilities); for words only the n-gram knows, its score plus 0.0. Those two numbers are stored in the header. All accuracy figures below are for the blend; **the model alone was not scored.**

**File layout** (little-endian; written by `export.py`, read by `nwp_model.cpp`): the 8 bytes `SWKBNWP1`; six 32-bit integers (version 1, data type 1 = int8, vocabulary size 23,990, vector size 128, hidden size 1024, byte length of the word list); two 32-bit floats (blend weight, offset); the 23,987 words, one per line, padded to 16 bytes (token 0 = unknown, 1 = sentence start, 2 = sentence end, words are tokens 3 and up); then word vectors, output bias, LSTM input weights, recurrent weights, gate bias and projection (int8 rows padded to 16 bytes, each followed by one float32 scale per row); last, an 8-byte FNV-1a checksum of everything before it.

**Minimal example** (Python with numpy; reads the file and prints the model's own top 3 words, with no n-gram blend; output shown was produced by this script):

```python
import struct, numpy as np

def load(path):
    b = open(path, "rb").read()
    assert b[:8] == b"SWKBNWP1"
    ver, dtype, V, D, H, wb, lam, off = struct.unpack("<6I2f", b[8:40])
    assert ver == 1 and dtype == 1                       # int8 file, as published
    words = b[40:40 + wb].decode().split("\n")           # token k+3 is words[k]
    at = 40 + (wb + 15) // 16 * 16
    def mat(rows, cols):                                 # int8 rows, then one float32 scale per row
        nonlocal at
        q = np.frombuffer(b, np.int8, rows * cols, at).reshape(rows, cols)
        at += (rows * cols + 15) // 16 * 16
        s = np.frombuffer(b, np.float32, rows, at); at += rows * 4
        return q * s[:, None]
    emb = mat(V, D)
    out_bias = np.frombuffer(b, np.float32, V, at); at += V * 4
    wih, whh = mat(4 * H, D), mat(4 * H, D)
    bias = np.frombuffer(b, np.float32, 4 * H, at); at += 4 * H * 4
    proj = mat(D, H)
    return dict(words=words, emb=emb, out_bias=out_bias, wih=wih, whh=whh, bias=bias, proj=proj, H=H, D=D)

def next_words(m, sentence, k=3):
    sig = lambda x: 1 / (1 + np.exp(-x))
    index = {w: i + 3 for i, w in enumerate(m["words"])}
    h, c = np.zeros(m["D"]), np.zeros(m["H"])
    for t in [1] + [index.get(w, 0) for w in sentence.lower().split()]:   # 1 = sentence start, 0 = unknown
        g = m["wih"] @ m["emb"][t] + m["whh"] @ h + m["bias"]
        i, f, cand, o = np.split(g, 4)                                    # input, forget, cell, output
        c = sig(f) * c + sig(i) * np.tanh(cand)
        h = m["proj"] @ (sig(o) * np.tanh(c))
    logits = m["emb"] @ h + m["out_bias"]                                 # output layer reuses the word vectors
    return [m["words"][t - 3] for t in np.argsort(-logits) if t >= 3][:k]

print(next_words(load("de.nwp.bin"), "ich habe"))   # ['mich', 'mir', 'die']
```

**Limits**

- Trained on web pages and short example sentences, not on texting. Slang, emoji, names and mixed languages are weak spots.
- Words are lower-case letters only: no capitals, digits, punctuation or apostrophes. The keyboard handles those. Words outside the 23,987 are seen as "unknown"; rarer words can only come from the n-gram pack.
- It reads only the current sentence (the keyboard passes at most the last 20 words) and does not learn.
- The keyboard ignores a model whose word list matches its own by less than half.
- No bias or toxicity evaluation was done; web-trained models reflect the web.

## How well it does

Measured with `bench/scoreboard.sh`, which replays the keyboard's own prediction code over test sentences one word at a time. **Unit:** percent of words for which the right word was among the suggestions. **Sets:** the first 300 sentences of each: *everyday* = Common Voice read-aloud sentences (CC0, test-only); *web* = FineWeb-2's official test split, one sentence per page; *Tatoeba* = ids ending in 0. Web and Tatoeba sets are partly "home ground" for a model trained on web text and Tatoeba, so everyday is the fairest guide. Differences under about 1 point are noise.

**Against the n-gram pack alone** (same pack in both columns, release `language-files-2026-10-22-open-words`; only the neural model differs). "Next word" = nothing typed yet, right word in the top 3. "After 3 letters" = three letters of the word typed, right word in the top 2 (the keyboard uses one of its three slots to show the typed letters).

| Test sentences | Next word: n-gram alone | with this model | After 3 letters: n-gram alone | with this model |
|---|---|---|---|---|
| Everyday (Common Voice) | 21.2 | **25.0** | 65.5 | **70.0** |
| FineWeb-2 test pages | 19.2 | **24.2** | 57.5 | **66.7** |
| Tatoeba held-out tenth | 29.9 | **31.6** | 72.9 | **78.4** |

**Against Gboard** (Gboard 14.2, Android 35 emulator, learning off, tested 2026-10-22). Percentage points by which this model plus the n-gram pack offered the right word more often than Gboard did on the same words, for 100 sentences each of the everyday and web sets, by number of letters typed (0 = next word, top 3 against Gboard's three suggestions; 1 to 3 letters = our top 2 against Gboard's suggestions). Plus means ahead; this model was ahead on every measure (3.9 to 14.8 points). Gboard was recorded once; results are about plus or minus 2 points, and say nothing about newer Gboard versions or about Gboard after it has learned from a user.

| Test sentences | 0 letters | 1 letter | 2 letters | 3 letters |
|---|---|---|---|---|
| Everyday (Common Voice) | +7.4 | +5.9 | +6.4 | +3.9 |
| Web (FineWeb-2) | +9.6 | +10.8 | +12.1 | +14.8 |

The files above were scored with the 2026-10-22 n-gram files; the keyboard now ships the 2026-10-23 apostrophe-aware files with this same model, which have not been re-scored. Test sets, the Gboard recording method and every other detail: [METHODS.md](METHODS.md).

## Licence and credit

`de.nwp.bin` is released under **ODC-By 1.0 AND CC BY 2.0 FR**: attribution only, no share-alike. The weights hold no text. When you redistribute it, keep:

- the credit line above (Models trained and released by Destin Moss via youcoded.ai, 2026) and the files `NOTICE.md`, `ODC-By-1.0.txt` and `TATOEBA-CC-BY-2.0-FR.txt` from this repository;
- credit to FineWeb / FineWeb-2 by Hugging Face ([ODC-By 1.0](https://opendatacommons.org/licenses/by/1-0/), derived from Common Crawl, whose terms of use also apply);
- credit to [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR) and its contributor list `de-CONTRIBUTORS.txt`, which is in GitHub release `language-files-2026-10-22-open-words`.
