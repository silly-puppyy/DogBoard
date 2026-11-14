#!/usr/bin/env python3
"""Which spelling of the same letters is the usual one: "abbeys" or "abbey's", "Johns" or "John's".
iOS marks one form per sort key in its lexicon (filter BoostPreferedFormForSortkey); ours comes from
real text: the form at least --ratio times as common as its twin in the word counts is preferred, and
near-ties get no mark (context decides). Contractions ("it's", "let's") are not possessives.

    tools/data/build_preferred_forms.py UNIGRAMS.tsv LEXICON.tsv OUT.tsv [--ratio 2.0]

UNIGRAMS: tools/data/count_unigrams.py output (case-sensitive counts). Output: one line per word
whose possessive is preferred over its plain plural ("john"), the default being the plain form;
words where neither form clearly wins are listed with a leading "=" ("=saturday").
"""
import argparse
import collections

CONTRACTIONS = {"it", "he", "she", "that", "what", "there", "here", "who", "let", "where", "how", "when", "why"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("unigrams")
    ap.add_argument("lexicon")
    ap.add_argument("out")
    ap.add_argument("--ratio", type=float, default=2.0)
    args = ap.parse_args()
    counts = collections.Counter()
    with open(args.unigrams, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, c = line.rstrip("\n").split("\t")
            counts[w.replace("’", "'").lower()] += int(c)
    words = set()
    with open(args.lexicon, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                words.add(line.split("\t")[0].lower())
    possessive, tie = [], []
    for w in sorted(words):
        # a plain word ending in s whose possessive twin ("Xs" / "X's") is seen in text
        if not w.endswith("s") or "'" in w or len(w) < 3 or w[:-1] in CONTRACTIONS:
            continue
        plain, poss = counts[w], counts[w[:-1] + "'s"]
        if poss == 0:
            continue
        if poss >= args.ratio * plain:
            possessive.append(w[:-1])
        elif plain < args.ratio * poss:
            tie.append(w[:-1])
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(f"# preferred forms (possessive over plain plural) from word counts, ratio {args.ratio}; "
                "'=' marks near-ties; tools/data/build_preferred_forms.py\n")
        for w in possessive:
            f.write(w + "\n")
        for w in tie:
            f.write("=" + w + "\n")
    print(f"possessive preferred: {len(possessive)}, near-ties: {len(tie)}")


if __name__ == "__main__":
    main()
