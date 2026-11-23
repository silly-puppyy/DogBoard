#!/usr/bin/env python3
"""Missed-space cases for RealTypoEvalTest (DOGBOARD_REAL_TYPOS=<out>): two neighbouring words typed correctly in the
source data, joined as a thumb that missed the space bar joins them: nothing between ("ofthe") or one of the letters
just above the space bar ("andnwaited"; c v b n m). The intended text keeps the space. The real typo data has no
such case (its intended column holds single words), so Rule 3 and the missed-space search were never measured.

    tools/test/make_missed_space.py <aalto-words-clean.tsv> <out.tsv> [n=2000] [typo]

With "typo": one of the two words also gets a slip on a neighbouring key ("continuebwirh" for
"continue with": the space typed as b AND r for t), the harder case the exact joins don't cover.
"""
import random
import sys

SEPARATORS = ["", "c", "v", "b", "n", "m"]
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
NEIGHBOURS = {}  # same-row neighbours (most real slips are sideways: held-out check 2026-11-20)
for row in ROWS:
    for i, c in enumerate(row):
        NEIGHBOURS[c] = [row[j] for j in (i - 1, i + 1) if 0 <= j < len(row)]


def slip(word, rnd):
    """One letter replaced by a same-row neighbour, not the first letter."""
    spots = [i for i in range(1, len(word)) if word[i].lower() in NEIGHBOURS]
    if not spots: return word
    i = rnd.choice(spots)
    return word[:i] + rnd.choice(NEIGHBOURS[word[i].lower()]) + word[i + 1:]


def main():
    src, out = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 2000
    with_typo = len(sys.argv) > 4 and sys.argv[4] == "typo"
    rng = random.Random(5)
    pairs = []
    prev = None  # (sentence, index, context, word) of the previous correctly typed word
    with open(src, encoding="utf-8") as f:
        for line in f:
            row = line.rstrip("\n").split("\t")
            if len(row) != 5:
                prev = None
                continue
            sid, idx, ctx, typed, intended = row
            ok = typed == intended and typed.isalpha() and typed.isascii()
            if ok and prev and prev[0] == sid and int(idx) == int(prev[1]) + 1:
                pairs.append((prev, (sid, idx, ctx, typed)))
            prev = (sid, idx, ctx, typed) if ok else None
    rng.shuffle(pairs)
    with open(out, "w", encoding="utf-8") as f:
        for (sid, idx, ctx, w1), (_, _, _, w2) in pairs[:n]:
            sep = rng.choice(SEPARATORS)
            t1, t2 = w1, w2.lower()
            if with_typo:
                if rng.random() < 0.5 and len(w1) > 2: t1 = slip(w1, rng)
                elif len(t2) > 2: t2 = slip(t2, rng)
            f.write(f"{sid}\t{idx}\t{ctx}\t{t1}{sep}{t2}\t{w1} {w2}\n")
    print(f"{min(n, len(pairs))} of {len(pairs)} pairs -> {out}")


if __name__ == "__main__":
    main()
