#!/usr/bin/env python3
"""Builds the abbreviation list for auto-capitalisation (tools/data/build_abbreviations.py).

    tools/data/build_abbreviations.py <cldr common/segments/en.xml>

Writes app/src/main/assets/dogboard/en.abbreviations.txt: words that keep their period ("e.g.",
"U.S.", "Ph.D."), so a period after them does not start a sentence. The iOS rule (see
notes/design/settings-model.md §3) asks the lexicon whether a word is stored with its final
period; Apple's own list is almost only dotted forms, so ours is CLDR's English sentence-break
suppressions that contain two or more periods, plus each one in lowercase ("E.g." -> "e.g.").
Single-period words ("Mr.", "etc.") stay out: like on iOS, the next word is capitalised.
Source: Unicode CLDR (unicode-org/cldr, common/segments/en.xml), Unicode License v3.
"""
import os
import re
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "dogboard", "en.abbreviations.txt")


def main():
    xml = open(sys.argv[1], encoding="utf-8").read()
    items = set(re.findall(r"<suppression>([^<]+)</suppression>", xml))
    dotted = {w for w in items if w.count(".") >= 2}
    words = sorted(dotted | {w.lower() for w in dotted}, key=lambda w: (w.lower(), w))
    with open(OUT, "w", encoding="utf-8") as f:
        f.write("# Abbreviations that keep their period; built by tools/data/build_abbreviations.py\n")
        f.write("# from Unicode CLDR common/segments/en.xml (Unicode License v3, licenses/Unicode-License.txt)\n")
        for w in words:
            f.write(w + "\n")
    print(f"{len(words)} abbreviations -> {OUT}")


if __name__ == "__main__":
    main()
