#!/usr/bin/env python3
"""Compares the FineWeb word counts with the shipped lexicon (coverage and frequency agreement).

    uv run --with msgpack tools/data/compare_counts.py UNIGRAMS.tsv WORDFREQ.msgpack.gz

Reports: how many lexicon words FineWeb has, the rank correlation of the two frequency sources over
the lexicon, the most frequent FineWeb words the lexicon lacks (candidates), and lexicon words that
FineWeb never saw.
"""
import gzip
import math
import sys

import msgpack

LEX = "app/src/main/assets/dogboard/en.lexicon.tsv"


def load_counts(path):
    lower = {}
    total = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, n = line.rstrip("\n").split("\t")
            n = int(n)
            total += n
            k = w.lower()
            lower[k] = lower.get(k, 0) + n
    return lower, total


def load_wordfreq(path):
    bins = msgpack.unpackb(gzip.open(path).read(), raw=False)
    z = {}
    for i, b in enumerate(bins[1:], start=1):
        for w in b:
            z.setdefault(w, 9 - i / 100)
    return z


def spearman(a, b):
    def ranks(v):
        order = sorted(range(len(v)), key=lambda i: v[i])
        r = [0.0] * len(v)
        for rank, i in enumerate(order):
            r[i] = rank
        return r
    ra, rb = ranks(a), ranks(b)
    n = len(a)
    d2 = sum((x - y) ** 2 for x, y in zip(ra, rb))
    return 1 - 6 * d2 / (n * (n * n - 1))


def main():
    counts, total = load_counts(sys.argv[1])
    wf = load_wordfreq(sys.argv[2])
    lex = [l.split("\t")[0] for l in open(LEX, encoding="utf-8") if not l.startswith("#")]
    lexkeys = {w.lower() for w in lex}
    zipf = {k: math.log10(n / total * 1e9) for k, n in counts.items()}
    have = [w for w in lexkeys if w in zipf]
    print(f"FineWeb: {total} words, {len(counts)} distinct (lowercased)")
    print(f"lexicon: {len(lexkeys)} keys, {len(have)} seen in FineWeb ({100 * len(have) / len(lexkeys):.1f}%)")
    both = [w for w in have if w in wf]
    print(f"rank correlation FineWeb vs wordfreq over {len(both)} lexicon words: "
          f"{spearman([zipf[w] for w in both], [wf[w] for w in both]):.3f}")
    missing = sorted((k for k in zipf if k not in lexkeys and k.isalpha() and len(k) > 1), key=lambda k: -zipf[k])
    print("most frequent FineWeb words not in the lexicon:")
    print(" ".join(f"{k}({zipf[k]:.1f})" for k in missing[:150]))
    unseen = [w for w in lexkeys if w not in zipf]
    print(f"lexicon keys FineWeb never saw: {len(unseen)}; sample: {' '.join(sorted(unseen)[:40])}")


if __name__ == "__main__":
    main()
