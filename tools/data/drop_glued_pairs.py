#!/usr/bin/env python3
"""Drops run-together word pairs from the lexicon (tools/data/drop_glued_pairs.py).

    tools/data/drop_glued_pairs.py <scowl final/ dir> [--write]

Web word counts contain slips like "ofthe" and "thankyou" as if they were words; with them in the
lexicon, typing them counts as correct and word separation never splits them. A word is dropped
when it is two lexicon words run together (a + b) and "a b" as a pair (en.bigrams.tsv) is at
least RATIO times as likely as the glued word (en.lexicon.tsv), each as a share of its own
source, and it is not a real word: not in SCOWL (English and American lists up to level 70) and not
stored capitalised (a name: "Avery"). So "ofthe", "atleast", "eachother" go; "anew", "atone",
"into", "anymore" stay. Without --write it only prints what it would drop. Run it after build_lexicon.py.
"""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "dogboard")
RATIO = 20.0
SCOWL_MAX_LEVEL = 70
# Real words the rule would drop, found by reading its output (2026-11-13)
KEEP = {"herbed", "atopic", "aplastic", "berberis", "astable", "innumerous", "poleis", "costus", "senseis",
        "islanding", "avital", "postapocalyptic", "superduper", "tictac", "mein"}


def scowl_words(final_dir):
    words = set()
    for name in os.listdir(final_dir):
        parts = name.split(".")
        if len(parts) == 2 and parts[0] in ("english-words", "american-words", "english-contractions") \
                and parts[1].isdigit() and int(parts[1]) <= SCOWL_MAX_LEVEL:
            with open(os.path.join(final_dir, name), encoding="latin-1") as f:
                words.update(line.strip().lower() for line in f)
    return words


# hand-reviewed words that build_lexicon.py always adds (hotspot, dropdown): never dropped
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "en.extra_words.txt"), encoding="utf-8") as _f:
    EXTRA = {l.split("\t")[0].strip().lower() for l in _f if l.strip() and not l.startswith("#")}  # (word, then an optional Zipf; compared lowercase)


def main():
    lex_path = os.path.join(ASSETS, "en.lexicon.tsv")
    lines = open(lex_path, encoding="utf-8").read().split("\n")
    real = scowl_words(sys.argv[1])
    counts = {}
    capitalised = set()
    for line in lines:
        if line and not line.startswith("#"):
            w, c = line.split("\t")[:2]
            counts[w.lower()] = counts.get(w.lower(), 0) + float(c)
            if w[0].isupper():
                capitalised.add(w.lower())
    lex_total = sum(counts.values())
    pairs = {}
    for line in open(os.path.join(ASSETS, "en.bigrams.tsv"), encoding="utf-8"):
        if line.startswith("#"):
            continue
        a, b, c = line.rstrip("\n").split("\t")
        pairs[(a, b)] = float(c)
    pair_total = sum(pairs.values())
    drop = {}
    for w, c in counts.items():
        if len(w) < 4 or not w.isalpha() or w in real or w in capitalised or w in KEEP or w in EXTRA:
            continue
        best = max((pairs.get((w[:i], w[i:]), 0.0) for i in range(1, len(w))), default=0.0)
        if best and (best / pair_total) >= RATIO * (c / lex_total):
            drop[w] = (c / lex_total, best / pair_total)
    for w, (pg, pp) in sorted(drop.items(), key=lambda x: -x[1][0]):
        print(f"{w}\tglued {pg:.2e}\tpair {pp:.2e}")
    print(f"{len(drop)} glued pairs", file=sys.stderr)
    if "--write" in sys.argv:
        kept = [l for l in lines if not l or l.startswith("#") or l.split("\t")[0].lower() not in drop]
        open(lex_path, "w", encoding="utf-8").write("\n".join(kept))


if __name__ == "__main__":
    main()
