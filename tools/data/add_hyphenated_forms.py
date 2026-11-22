#!/usr/bin/env python3
"""Adds the inflected forms of the lexicon's hyphenated words to an existing word list, nothing else changed
(fine-tune -> fine-tuned, fine-tuning; follow-up -> follow-ups; co-author -> co-authors). build_lexicon.py takes
hyphenated words from Zipf 3.0, so their rarer forms were missing (fine-tuning 2.85); it now applies the same rule
(MIN_ZIPF_HYPHENATED_FORM), and this script applies it to a list built before, without a full rebuild.

A form goes in when the counted text (count_hyphenated.py) uses it at Zipf >= 2.0, its base (fine-tune) is in the
list, it is not written closed as a word too (re-used / reused: re-open "corrected" reopen, d6e4dd4) and it is not a
known misspelling. In the casing the text uses most; the count as build_lexicon.py writes it, 10^(Zipf + 1).

    tools/data/add_hyphenated_forms.py LEXICON HYPHENATED_TSV TOTAL_WORDS [--misspellings FILE] [--write]
"""
import argparse
import math
import re
from collections import defaultdict

HYPHENATED_RE = re.compile(r"^[A-Za-zÀ-ÖØ-öø-ÿ]+(-[A-Za-zÀ-ÖØ-öø-ÿ]+){1,3}$")
MIN_ZIPF_FORM = 2.0
ZIPF_CAP = 4.0  # build_lexicon.py NAME_ZIPF_CAP


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lexicon")
    ap.add_argument("hyphenated")
    ap.add_argument("total_words", type=float)
    ap.add_argument("--misspellings", default="")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    head, rows = [], []
    for line in open(a.lexicon, encoding="utf-8"):
        (head if line.startswith("#") else rows).append(line.rstrip("\n"))
    known = {r.split("\t")[0].lower() for r in rows}
    bad = set()
    if a.misspellings:
        for line in open(a.misspellings, encoding="utf-8", errors="ignore"):
            w = line.split("->")[0].strip().lower()
            if w:
                bad.add(w)

    by_key = defaultdict(dict)
    for line in open(a.hyphenated, encoding="utf-8"):
        if line.startswith("#"):
            continue
        w, n = line.rstrip("\n").split("\t")
        if HYPHENATED_RE.match(w):
            by_key[w.lower()][w] = int(n)

    added = []
    for key, fs in by_key.items():
        if key in known or key in bad or key.replace("-", "") in known or key.replace("-", "") in bad:
            continue
        z = math.log10(sum(fs.values()) / a.total_words * 1e9)
        if z < MIN_ZIPF_FORM:
            continue
        stem_head, _, last = key.rpartition("-")
        if not last.endswith(("s", "ed", "d", "ing")):
            continue
        stems = {last[:-1], last[:-2], last[:-2] + "e", last[:-3], last[:-3] + "e", last[:-3] + "y", last[:-2] + "y"}
        if not any(f"{stem_head}-{st}" in known for st in stems if st):
            continue
        best = max(fs, key=fs.get)
        if best.isupper() and len(fs) > 1:
            best = max((f for f in fs if not f.isupper()), key=fs.get)
        added.append((best, round(10 ** (min(z, ZIPF_CAP) + 1))))

    print(f"{len(added)} forms:", ", ".join(w for w, _ in sorted(added, key=lambda x: -x[1])[:40]))
    if not a.write:
        return
    # merged in by count (the list is sorted by count, highest first); every other line stays as it was
    out = [(int(r.split("\t")[1]), i, r) for i, r in enumerate(rows)]
    out += [(c, len(rows) + i, f"{w}\t{c}") for i, (w, c) in enumerate(added)]
    out.sort(key=lambda x: (-x[0], x[1]))
    with open(a.lexicon, "w", encoding="utf-8") as f:
        f.write("\n".join(head + [r for _, _, r in out]) + "\n")


if __name__ == "__main__":
    main()
