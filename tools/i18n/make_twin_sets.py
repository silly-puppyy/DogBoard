#!/usr/bin/env python3
"""Balanced accent-twin test sets (tools/i18n/make_twin_sets.py): words that are right both plain and accented (French a / à,
Spanish si / sí), typed plain, from held-out sentences. For each pair, as many sentences where the plain form is right
(it must stay: an accent added is a broken word, the "à bit" bug) as where the accented one is (the accent should come
back). RealTypoEvalTest's TSV (sentence, word, the sentence so far, typed, intended): its "fixed" counts accents
restored, its "broken" accents wrongly added. The old *-accents-twin sets held only the accented side.

    make_twin_sets.py <lang> <heldout.txt> <out.tsv> [N per side per pair = 100]
"""
import random
import re
import sys

PAIRS = {
    "fr": [("a", "à"), ("ou", "où"), ("la", "là"), ("du", "dû"), ("sur", "sûr")],
    "es": [("si", "sí"), ("el", "él"), ("tu", "tú"), ("mi", "mí"), ("se", "sé"), ("te", "té"), ("mas", "más"),
           ("aun", "aún"), ("que", "qué"), ("como", "cómo"), ("donde", "dónde"), ("cuando", "cuándo")],
    "pt": [("e", "é"), ("a", "à"), ("as", "às"), ("esta", "está"), ("da", "dá"), ("nos", "nós"), ("por", "pôr"),
           ("pais", "país"), ("so", "só"), ("pode", "pôde")],
    "it": [("e", "è"), ("la", "là"), ("si", "sì"), ("se", "sé"), ("da", "dà"), ("li", "lì"), ("ne", "né")],
}
WORD = re.compile(r"^[^\w]*([\w’']+?)[^\w]*$")


def main():
    lang, heldout, out = sys.argv[1], sys.argv[2], sys.argv[3]
    n = int(sys.argv[4]) if len(sys.argv) > 4 else 100
    pairs = PAIRS[lang]
    want = {w for p in pairs for w in p}
    found = {w: [] for w in want}
    for si, line in enumerate(open(heldout, encoding="utf-8")):
        words = line.split()
        for wi, tok in enumerate(words):
            m = WORD.match(tok)
            # mid-sentence, lower case as written: the case logic stays out of it
            if not m or wi == 0 or m.group(1) not in want:
                continue
            w = m.group(1)
            if len(found[w]) < 20 * n:
                found[w].append((si, wi, " ".join(words[:wi]), w))
    rnd = random.Random(11)
    rows = []
    for plain, accented in pairs:
        k = min(n, len(found[plain]), len(found[accented]))
        for si, wi, ctx, w in rnd.sample(found[plain], k) + rnd.sample(found[accented], k):
            rows.append(f"{si}\t{wi}\t{ctx}\t{plain}\t{w}\n")
        print(f"{lang} {plain}/{accented}: {k} each (found {len(found[plain])} / {len(found[accented])})", file=sys.stderr)
    rnd.shuffle(rows)
    open(out, "w", encoding="utf-8").writelines(rows)
    print(f"{lang}: {len(rows)} rows -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
