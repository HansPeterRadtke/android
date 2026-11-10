#!/usr/bin/env python3
"""Build ranked, consecutive two-word continuations from the attributed Tatoeba exports."""
import argparse
import bz2
from collections import Counter
import gzip
from pathlib import Path
import re
from tempfile import TemporaryFile

# A break in whitespace-separated words is also a break in training evidence.
WORD = r"[^\W\d_]+(?:['’][^\W\d_]+)*"
RUN = re.compile(rf"{WORD}(?:[^\S\n]+{WORD})*")
WIDTHS = (1, 2, 3)
TOP = 24

def build(source, language, output):
    stride = 4 if language == "en" else 2
    rows = {}
    with TemporaryFile(mode="w+t", encoding="utf-8") as clauses:
        with bz2.open(source, "rt", encoding="utf-8") as stream:
            for number, line in enumerate(stream, 1):
                if number % stride:
                    continue
                sentence = line.rstrip().split("\t", 2)[-1].lower().replace("’", "'")
                if len(sentence) > 210 or "http" in sentence:
                    continue
                for match in RUN.finditer(sentence):
                    words = match.group().split()
                    if 3 <= len(words) <= 24 and not {"tom", "mary", "muiriel"}.intersection(words):
                        # Initial questions and embedded clauses use different word orders.
                        preceding = sentence[:match.start()].rstrip(" \t\r\n\"'«»“”„(")
                        if not preceding or preceding[-1] in ".!?":
                            words.insert(0, "^")
                        clauses.write(" ".join(words) + "\n")
        for width in WIDTHS:
            clauses.seek(0)
            counts = Counter()
            for line in clauses:
                words = line.split()
                for i in range(width, len(words)-1):
                    counts[" ".join(words[i-width:i]) + "\t" + words[i] + " " + words[i+1]] += 1
            grouped = {}
            for sequence, count in counts.items():
                if count < (3 if width == 1 else 2):
                    continue
                context, pair = sequence.split("\t")
                grouped.setdefault(context, []).append((count, pair))
            del counts
            for context, options in grouped.items():
                options.sort(key=lambda item: (-item[0], item[1]))
                rows[context] = "\t".join(pair for _, pair in options[:TOP])
    with output.open("wb") as file, gzip.GzipFile(filename="", fileobj=file, mode="wb", mtime=0) as gz:
        gz.write(f"phrases-1\t{language}\n".encode())
        for context in sorted(rows):
            gz.write((context + "\t" + rows[context] + "\n").encode())
    print(language, len(rows), "contexts,", output.stat().st_size, "compressed bytes", flush=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("language", choices=["de", "en"])
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.source, args.language, args.output)
