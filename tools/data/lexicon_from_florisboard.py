#!/usr/bin/env python3
"""Stand-in English lexicon: converts FlorisBoard's bundled word list (ime/dict/data.json,
word -> 0..255 frequency class) into DogBoard's lexicon format.

DogBoard lexicon format, version 1 (UTF-8 text):
    #dogboard-lexicon 1
    #lang en
    #source <free text>
    word<TAB>count

Counts are relative frequencies; only their ratios matter. The source list only has frequency
classes, so counts here are a Zipf estimate from each class's average rank (count ~ 1/rank).
Real corpus counts replace this later without changing the format.
"""
import json
import sys
from collections import defaultdict

SRC = "app/src/main/assets/ime/dict/data.json"
DST = "app/src/main/assets/dogboard/en.lexicon.tsv"
SCALE = 1e9


def cased(word):
    """The source list is all lowercase; restore the pronoun I and its contractions."""
    if word == "i" or word.startswith("i'"):
        return "I" + word[1:]
    return word


# Drop an apostrophe-less spelling ("dont", "im", "ill") when its contraction is at least this
# many classes more frequent, so autocorrect restores the apostrophe. Real words that are more
# frequent than the contraction ("well", "were", "shell") stay.
CONTRACTION_GAP = 15


def without_apostrophe_misspellings(data):
    drop = set()
    for word, cls in data.items():
        if "'" in word:
            plain = word.replace("'", "")
            if plain in data and cls - data[plain] >= CONTRACTION_GAP:
                drop.add(plain)
    return {w: c for w, c in data.items() if w not in drop}


def main():
    data = without_apostrophe_misspellings(json.load(open(SRC, encoding="utf-8")))
    ranked = sorted(data.items(), key=lambda kv: (-kv[1], kv[0]))
    ranks = defaultdict(list)
    for rank, (_, cls) in enumerate(ranked, start=1):
        ranks[cls].append(rank)
    count_of = {cls: max(1, round(SCALE / (sum(r) / len(r)))) for cls, r in ranks.items()}
    with open(DST, "w", encoding="utf-8") as out:
        out.write("#dogboard-lexicon 1\n#lang en\n")
        out.write("#source FlorisBoard ime/dict/data.json frequency classes, Zipf-estimated counts\n")
        for word, cls in ranked:
            out.write(f"{cased(word)}\t{count_of[cls]}\n")
    print(f"{len(ranked)} words -> {DST}", file=sys.stderr)


if __name__ == "__main__":
    main()
