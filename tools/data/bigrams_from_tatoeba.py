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
import unicodedata

# a letter, or a mark belonging to one (Devanagari ि ा ्): Python's \w has no marks, so किताब split into क त ब
# (the Hindi pack's text, 2026-11-23)
L = '(?:[^\\W\\d_]|[' + ''.join(chr(c) for c in range(0x300, 0x10000) if unicodedata.category(chr(c))[0] == 'M') + '])'

LEXICON = "app/src/main/assets/dogboard/en.lexicon.tsv"
DST = "app/src/main/assets/dogboard/en.bigrams.tsv"
MIN_COUNT = 5
START = "<s>"
# Tatoeba's example sentences use a few names constantly ("Tom" is its third most common word);
# pairs with them would teach the keyboard to suggest them everywhere.
OVERUSED_NAMES = {"tom", "mary", "maría"}  # Spanish Tatoeba translates Mary as María
TOKEN = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*|[.!?]")
# other languages: Latin-1 letters too (English keeps the ASCII tokens its shipped file was built with)
TOKEN_LATIN1 = re.compile(L + r"+(?:['’]" + L + r"+)*|[.!?]")  # any script's letters (name kept)


def is_eval_sentence(sentence_id: str) -> bool:
    return sentence_id.endswith("0")


def tokens(sentence, token=TOKEN):
    """Lowercase words of a sentence, with START at the beginning and after . ! ?"""
    out = [START]
    for t in token.findall(sentence):
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
    ap.add_argument("--lang", default="en")
    a = ap.parse_args()
    src = a.sentences
    vocab = {line.split("\t")[0].lower() for line in open(a.lexicon, encoding="utf-8") if not line.startswith("#")}
    counts = collections.Counter()
    for line in open(src, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3 or is_eval_sentence(parts[0]):
            continue
        toks = tokens(parts[2], TOKEN if a.lang == "en" else TOKEN_LATIN1)
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
        out.write(f"#dogboard-bigrams 1\n#lang {a.lang}\n")
        out.write(f"#source Tatoeba (https://tatoeba.org) {"English" if a.lang == "en" else a.lang} sentences, CC-BY 2.0 FR; min count {MIN_COUNT}\n")
        for p, w, c in kept:
            out.write(f"{p}\t{w}\t{c}\n")
    print(f"{len(kept)} bigrams -> {a.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
