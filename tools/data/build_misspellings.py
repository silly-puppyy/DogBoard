#!/usr/bin/env python3
"""Known misspellings (en.misspellings.txt): the misspelled forms of Wikipedia's machine-readable
"Lists of common misspellings" (CC BY-SA 4.0) that our lexicon lacks, lowercase, one per line. A
typed string on this list doesn't get the letter model's "plausible unknown word" score (it looks
like a word because it is one, misspelled), so the correction it means wins ("seperate" ->
"separate"); names and slang, which the letter model is there to keep, aren't on it.

    tools/data/build_misspellings.py common_misspellings.txt en.lexicon.tsv OUT
"""
import sys

src, lexicon, out = sys.argv[1:4]
known = {l.split("\t")[0].lower() for l in open(lexicon, encoding="utf-8") if not l.startswith("#")}
bad = set()
for line in open(src, encoding="utf-8"):
    if "->" in line:
        w = line.split("->")[0].strip().lower()
        if w.isalpha() and w not in known:
            bad.add(w)
with open(out, "w", encoding="utf-8") as f:
    f.write("# Known misspellings, from Wikipedia's Lists of common misspellings (CC BY-SA 4.0); tools/data/build_misspellings.py\n")
    for w in sorted(bad):
        f.write(w + "\n")
print(len(bad), "misspellings")
