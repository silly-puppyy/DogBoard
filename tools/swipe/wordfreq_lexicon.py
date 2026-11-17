#!/usr/bin/env python3
"""A test lexicon for another language from wordfreq's data (tools/swipe/wordfreq_lexicon.py).

    wordfreq_lexicon.py DATA/large_de.msgpack.gz out.tsv [--top 160000]

wordfreq 3.1.1 (CC BY-SA 4.0) "cBpack": a msgpack list, [header, words at 0 cB, at -1 cB, ...] (one
centibel = 0.01 of a log10 step; frequency = 10^(-bin/100)). Writes our lexicon format (word<TAB>count,
count = frequency x 1e9), lowercase letter-only words: for decoding swipe tests on German, French,
Spanish, ... layouts. Not the app's lexicon (that is tools/data/build_lexicon.py).
"""
import argparse
import gzip

import msgpack


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    ap.add_argument("out")
    ap.add_argument("--top", type=int, default=160000)
    a = ap.parse_args()
    data = msgpack.load(gzip.open(a.pack), raw=False)
    words = []
    for cb, bucket in enumerate(data[1:]):
        for w in bucket:
            w = w.lower()
            if w.isalpha():
                words.append((w, max(1, int(10 ** (-cb / 100) * 1e9))))
        if len(words) >= a.top:
            break
    seen = set()
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(f"#dogboard-lexicon 1\n#source wordfreq 3.1.1 (CC BY-SA 4.0) {a.pack.rsplit('/', 1)[-1]}; swipe tests only\n")
        for w, n in words[:a.top]:
            if w not in seen:
                seen.add(w)
                f.write(f"{w}\t{n}\n")
    print(f"{len(seen)} words -> {a.out}")


if __name__ == "__main__":
    main()
