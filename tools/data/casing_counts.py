#!/usr/bin/env python3
"""Counts how words are capitalised in the middle of sentences (tools/data/casing_counts.py).

    tools/data/casing_counts.py --data DATA_DIR --out casing.tsv

For words that SCOWL lists in more than one casing ("google" and "Google", "may" and "May",
"bill" and "Bill"), counts each casing where the capital says something: not the first word of a
sentence, not in all-caps text. Only edited text is used (Common Voice sentences, Tatoeba): in
chats people lowercase names out of habit, which says nothing about which word they meant.
build_lexicon.py uses these counts to split a word's frequency between its casings.
Output: form<TAB>count, for every listed casing (0 when not seen).
"""
import argparse
import os
import re
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_lexicon import load_scowl  # noqa: E402
from build_ngrams import common_voice, tatoeba  # noqa: E402

WORD = re.compile(r"[A-Za-z][A-Za-z']*")
SENTENCE_END = re.compile(r"[.!?:;\"“”()]\s*$")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-level", type=int, default=80)
    args = ap.parse_args()

    scowl = load_scowl(os.path.join(args.data, "scowl/scowl-2020.12.07/final"), args.max_level)
    casings = defaultdict(set)
    for w in scowl:
        if WORD.fullmatch(w):
            casings[w.lower()].add(w)
    multi = {k: v for k, v in casings.items() if len(v) > 1}
    print(f"{len(multi)} words listed in more than one casing", file=sys.stderr)

    counts = Counter()
    seen = Counter()
    for name, source in (("common_voice", common_voice), ("tatoeba", tatoeba)):
        for text, _held in source(args.data):
            if text.isupper():
                continue
            prev_end = 0
            for m in WORD.finditer(text):
                key = m.group().lower()
                before = text[prev_end:m.start()]
                first = m.start() == 0 or bool(SENTENCE_END.search(text[:m.start()]))
                prev_end = m.end()
                if first or key not in multi:
                    continue
                form = m.group()
                if form in multi[key]:
                    counts[form] += 1
                seen[name] += 1
    with open(args.out, "w", encoding="utf-8") as f:
        for key in sorted(multi):
            for form in sorted(multi[key]):
                f.write(f"{form}\t{counts[form]}\n")
    print(f"counted {sum(counts.values())} mid-sentence uses ({dict(seen)}) -> {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
