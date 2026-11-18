#!/usr/bin/env python3
"""Stand-in English bigram counts from Tatoeba sentences (https://tatoeba.org, CC-BY 2.0 FR).

    tools/data/bigrams_from_tatoeba.py ~/Documents/Code/AI/keyboard/data/tatoeba/eng_sentences.tsv

DogBoard bigram format, version 1 (UTF-8 text):
    #dogboard-bigrams 1
    #lang en
    #source <free text>
    previous<TAB>word<TAB>count

Words are lowercase; "<s>" as previous word means the start of a sentence. Only pairs whose
words are in the lexicon (assets/dogboard/en.lexicon.tsv) and that occur at least MIN_COUNT
times are kept. Sentences whose id ends in 0 are left out, for evaluation.
"""
import collections
import re
import sys

LEXICON = "app/src/main/assets/dogboard/en.lexicon.tsv"
DST = "app/src/main/assets/dogboard/en.bigrams.tsv"
MIN_COUNT = 5
START = "<s>"
# Tatoeba's example sentences use a few names constantly ("Tom" is its third most common word);
# pairs with them would teach the keyboard to suggest them everywhere.
OVERUSED_NAMES = {"tom", "mary"}
TOKEN = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*|[.!?]")


def is_eval_sentence(sentence_id: str) -> bool:
    return sentence_id.endswith("0")


def tokens(sentence):
    """Lowercase words of a sentence, with START at the beginning and after . ! ?"""
    out = [START]
    for t in TOKEN.findall(sentence):
        if t in ".!?":
            if out[-1] != START:
                out.append(START)
            continue
        out.append(t.lower().replace("’", "'"))
    return out


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("sentences", help="Tatoeba <lang>_sentences.tsv")
    ap.add_argument("--lexicon", default=LEXICON)
    ap.add_argument("--out", default=DST)
    a = ap.parse_args()
    src = a.sentences
    vocab = {line.split("\t")[0].lower() for line in open(a.lexicon, encoding="utf-8") if not line.startswith("#")}
    counts = collections.Counter()
    for line in open(src, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3 or is_eval_sentence(parts[0]):
            continue
        toks = tokens(parts[2])
        for prev, word in zip(toks, toks[1:]):
            if word == START:
                continue
            counts[(prev, word)] += 1
    kept = [
        (p, w, c) for (p, w), c in counts.items()
        if c >= MIN_COUNT and w in vocab and (p == START or p in vocab)
        and p not in OVERUSED_NAMES and w not in OVERUSED_NAMES
    ]
    kept.sort(key=lambda x: (x[0], -x[2], x[1]))
    with open(a.out, "w", encoding="utf-8") as out:
        out.write("#dogboard-bigrams 1\n#lang en\n")
        out.write(f"#source Tatoeba (https://tatoeba.org) English sentences, CC-BY 2.0 FR; min count {MIN_COUNT}\n")
        for p, w, c in kept:
            out.write(f"{p}\t{w}\t{c}\n")
    print(f"{len(kept)} bigrams -> {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
