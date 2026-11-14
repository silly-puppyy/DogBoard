#!/usr/bin/env python3
"""Targeted eval sets: sentences picked by the KIND of words they contain, not by whether the keyboard
gets them wrong (picking by failures inflates any change through regression to the mean).

    tools/data/build_bucket_sets.py LEXICON OUT_DIR SENTENCES.txt [SENTENCES.txt ...] [--per-bucket 2000]

Input: Leipzig Corpora Collection sentence files (CC BY; "id<TAB>sentence", sorted alphabetically, so
they are sampled at random here). Sentences of 4-20 words with few digits or symbols are kept. Buckets
(one file each, one sentence per line; a sentence can be in several):
- unknown: a word our lexicon lacks (names, brands, slang)
- apostrophe: a contraction or possessive (don't, dog's)
- possplural: a word whose plural / possessive twin is also a word (phones / phone's)
- rare: a word among the lexicon's rarest 20 %
- names: a capitalised word mid-sentence that the lexicon stores capitalised
- control: random sentences (to catch regressions on ordinary text)
"""
import argparse
import os
import random
import re

WORDS = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lexicon")
    ap.add_argument("out")
    ap.add_argument("sentences", nargs="+")
    ap.add_argument("--per-bucket", type=int, default=2000)
    ap.add_argument("--control", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=11)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    counts = {}
    forms = {}
    with open(args.lexicon, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, c = line.rstrip("\n").split("\t")
            counts[w.lower()] = counts.get(w.lower(), 0) + int(c)
            forms.setdefault(w.lower(), w)
    ranked = sorted(counts.values())
    rare_cut = ranked[len(ranked) // 5]

    lines = []
    for path in args.sentences:
        with open(path, encoding="utf-8") as f:
            for line in f:
                s = line.rstrip("\n").split("\t", 1)[-1].replace("’", "'")
                words = WORDS.findall(s)
                if not 4 <= len(words) <= 20:
                    continue
                letters = sum(ch.isalpha() or ch in " '.,!?" for ch in s)
                if letters < 0.92 * len(s) or "http" in s or "@" in s:
                    continue
                lines.append((s, words))
    rng.shuffle(lines)

    buckets = {k: [] for k in ("unknown", "apostrophe", "possplural", "rare", "names")}
    for s, words in lines:
        low = [w.lower() for w in words]
        tags = set()
        if any(w not in counts for w in low):
            tags.add("unknown")
        if any("'" in w for w in low):
            tags.add("apostrophe")
        for w in low:
            if w.endswith("'s") and w[:-2] + "s" in counts or w.endswith("s") and "'" not in w and w[:-1] + "'s" in counts:
                tags.add("possplural")
        if any(w in counts and counts[w] <= rare_cut for w in low):
            tags.add("rare")
        if any(i > 0 and w[:1].isupper() and forms.get(w.lower(), "")[:1].isupper() for i, w in enumerate(words)):
            tags.add("names")
        for t in tags:
            if len(buckets[t]) < args.per_bucket:
                buckets[t].append(s)
        if all(len(v) >= args.per_bucket for v in buckets.values()):
            break
    control = [s for s, _ in rng.sample(lines, args.control)]

    os.makedirs(args.out, exist_ok=True)
    for name, items in list(buckets.items()) + [("control", control)]:
        with open(os.path.join(args.out, f"{name}.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(items) + "\n")
        print(f"{name}: {len(items)} sentences")


if __name__ == "__main__":
    main()
