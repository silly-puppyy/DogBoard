#!/usr/bin/env python3
"""English noun plurals for a plural token (LM tokenizer A/B, user 2026-11-22: "the CPU must form plurals before the
keyboard suggests 'fishs' or 'skys'"): every plural comes from a table, never from an add-s rule.

Source: AGID infl.txt (Kevin Atkinson, SCOWL; permissive licence, see its README): "child N: children",
"fish N: fish, fishes {:1}", "cactus N: cacti, cactuses 1". Kept: lines whose part of speech is certain ("N:", not
"N?:"), forms without a "?" (questionable) or "~" (not a real inflection) mark and with a variant level of at most
1, in AGID's order (the first is the main plural, the one the token writes; ordering by our counts let the verb
"dies" outrank "dice"). Then only lemmas AND plurals in the DogBoard lexicon, and no entry when:
  - the main plural is the singular (fish, sheep, deer, aircraft): text can't tell the number, so never split;
  - it is under 1 % as frequent as the singular ("newses", "informations": junk or mass nouns).
A noun with no entry ("series N: serieses?") is never split into stem + plural.

Usage: build_plurals.py infl.txt en.lexicon.tsv out.tsv"""
import re
import sys

infl, lexicon, out = sys.argv[1:4]
lex = {}
with open(lexicon, encoding="utf-8") as f:
    for line in f:
        if not line.startswith("#"):
            w, c = line.rstrip("\n").split("\t")[:2]
            lex[w.lower()] = max(lex.get(w.lower(), 0), int(c))

LINE = re.compile(r"^(\S+) N: (.*)$")
rows = {}
with open(infl, encoding="utf-8", errors="replace") as f:
    for line in f:
        m = LINE.match(line.rstrip("\n"))
        if not m:
            continue
        lemma = m.group(1).lower()
        forms, main = [], None
        for part in m.group(2).split("|")[0].split(","):
            part = re.sub(r"\{[^}]*\}", "", part).strip()
            if not part or "?" in part or "~" in part:
                continue
            bits = part.split()
            main = main or bits[0].lower()
            level = float(bits[1]) if len(bits) > 1 and re.match(r"^[0-9.]+$", bits[1]) else 0.0
            if level <= 1 and re.match(r"^[a-z][a-z'-]*$", bits[0]):
                forms.append(bits[0])
        forms = list(dict.fromkeys(p for p in forms if p in lex and p != lemma))
        if lemma in lex and forms and main != lemma and lex[forms[0]] >= 0.01 * lex[lemma]:
            rows[lemma] = forms

with open(out, "w", encoding="utf-8") as o:
    o.write("#en plurals: AGID infl.txt (SCOWL, Kevin Atkinson) N forms in the DogBoard lexicon, main plural first;"
            " tools/data/build_plurals.py\n")
    for lemma in sorted(rows):
        o.write(lemma + "\t" + "\t".join(rows[lemma]) + "\n")
plain = sum(1 for k, v in rows.items() if v[0] == k + "s")
print(f"{len(rows)} nouns, {len(rows) - plain} with a plural other than +s", file=sys.stderr)
