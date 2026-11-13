#!/usr/bin/env python3
"""Builds the never-suggest list (tools/data/build_nosuggest.py).

    tools/data/build_nosuggest.py <LDNOOBW en list>

Writes app/src/main/assets/dogboard/en.nosuggest.txt: one lowercase word per line. As iOS never
introduces a vulgar or sensitive word through autocorrection, completion or prediction (it stays
typeable: the typed word itself is never removed), DogBoard keeps these words out of everything
it offers. Only single words that are in DogBoard's lexicon are kept.
Source: List of Dirty, Naughty, Obscene, and Otherwise Bad Words (LDNOOBW, github.com/LDNOOBW),
English list, CC BY 4.0.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "dogboard")


def main():
    lexicon = set()
    with open(os.path.join(ASSETS, "en.lexicon.tsv"), encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                lexicon.add(line.split("\t", 1)[0].lower())
    words = set()
    with open(sys.argv[1], encoding="utf-8") as f:
        for line in f:
            w = line.strip().lower()
            if re.fullmatch(r"[a-z]+(?:'[a-z]+)?", w) and w in lexicon:
                words.add(w)
    out = os.path.join(ASSETS, "en.nosuggest.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("# Never suggested (still typeable); tools/data/build_nosuggest.py\n")
        f.write("# from LDNOOBW (github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words), CC BY 4.0\n")
        for w in sorted(words):
            f.write(w + "\n")
    print(f"{len(words)} words -> {out}")


if __name__ == "__main__":
    main()
