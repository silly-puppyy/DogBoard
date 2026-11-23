#!/usr/bin/env python3
"""Streams Leipzig corpus sentences as KenLM training text, holding some out for tests.

    tools/data/leipzig_sentences.py --heldout HELD.txt CORPUS-sentences.txt [...] | lmplz -o 3 ...

Tokens as fineweb_sentences.py: lowercase words (Latin-1 letters with inner apostrophes, ’ as '), anything else
splits the text, . ! ? end a sentence. Every sentence whose Leipzig id ends in 0 goes, as written, to --heldout
(test sentences that never reach the models, as with Tatoeba ids ending in 0). Leipzig corpora are CC BY 4.0.
"""
import argparse
import re
import sys
import unicodedata

# a letter, or a mark belonging to one (Devanagari ि ा ्): Python's \w has no marks, so किताब split into क त ब
# (the Hindi pack's text, 2026-11-23)
L = '(?:[^\\W\\d_]|[' + ''.join(chr(c) for c in range(0x300, 0x10000) if unicodedata.category(chr(c))[0] == 'M') + '])'

# letters of any script (Latin, Cyrillic, Greek...), digits and underscores excluded
WORD = re.compile(L + r"+(?:['’]" + L + r"+)*")
SENTENCE_END = re.compile(r"[.!?¿¡\n]+")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--heldout", required=True)
    ap.add_argument("files", nargs="+")
    args = ap.parse_args()
    out = sys.stdout
    with open(args.heldout, "w", encoding="utf-8") as held:
        for path in args.files:
            with open(path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    sid, _, text = line.rstrip("\n").partition("\t")
                    if sid.endswith("0"):
                        held.write(text + "\n")
                        continue
                    for part in SENTENCE_END.split(text):
                        words = [w.lower().replace("’", "'") for w in WORD.findall(part)]
                        if words:
                            out.write(" ".join(words) + "\n")


if __name__ == "__main__":
    main()
