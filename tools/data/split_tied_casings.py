#!/usr/bin/env python3
"""Splits a word's count between its casings where a lexicon lists them all at the word's count
(tools/data/split_tied_casings.py). build_lexicon_hunspell.py takes counts from wordfreq, which lowercases, so every
casing Hunspell accepts got the whole count: French "ET" as likely as "et", "A" as "a" (49,964 groups, 9.6 % of the
French list; Spanish 222, Russian 554). Each group's count is shared by how often each casing is written mid-sentence in
a corpus (not a sentence's first word, not in all-caps text), as tools/data/casing_counts.py does for English; a casing
the corpus never writes keeps a sliver. Groups whose counts already differ are left alone.

    split_tied_casings.py IN.lexicon.tsv CORPUS.txt [CORPUS.txt ...] --out OUT.lexicon.tsv
Corpus files: one sentence per line; Leipzig "id<TAB>sentence" lines are read too.
"""
import argparse
import collections
import re

TOKEN = re.compile(r"^[^\w]*([\w'’-]+?)[^\w]*$")
SLIVER = 0.01  # the share of a casing the corpus never writes mid-sentence, before normalising


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lexicon")
    ap.add_argument("corpus", nargs="+")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    header, rows = [], []
    for line in open(a.lexicon, encoding="utf-8"):
        if line.startswith("#") or "\t" not in line:
            header.append(line)
            continue
        w, c, *rest = line.rstrip("\n").split("\t")
        rows.append([w, int(c), rest])
    groups = collections.defaultdict(list)
    for i, (w, c, _) in enumerate(rows):
        groups[w.lower()].append(i)
    tied = {k: ix for k, ix in groups.items() if len(ix) > 1 and len({rows[i][1] for i in ix}) < len(ix)}
    forms = {rows[i][0] for ix in tied.values() for i in ix}
    seen = collections.Counter()
    for path in a.corpus:
        for line in open(path, encoding="utf-8", errors="replace"):
            text = line.split("\t", 1)[1] if "\t" in line else line
            toks = text.split()
            letters = [ch for ch in text if ch.isalpha()]
            if not toks or (letters and all(ch.isupper() for ch in letters)):
                continue
            for t in toks[1:]:
                m = TOKEN.match(t)
                if m and m.group(1) in forms:
                    seen[m.group(1)] += 1
    changed = 0
    for key, ix in tied.items():
        total = max(rows[i][1] for i in ix)
        weights = [seen[rows[i][0]] + (SLIVER if seen[rows[i][0]] == 0 else 0) for i in ix]
        s = sum(weights)
        for i, wt in zip(ix, weights):
            rows[i][1] = max(1, round(total * wt / s))
        changed += 1
    with open(a.out, "w", encoding="utf-8") as out:
        out.writelines(header)
        for w, c, rest in rows:
            out.write("\t".join([w, str(c)] + rest) + "\n")
    print(f"{changed} tied groups split ({len(forms)} forms, {sum(1 for f in forms if seen[f])} seen mid-sentence)")


if __name__ == "__main__":
    main()
