#!/usr/bin/env python3
"""The universal model on the app's own layouts (tools/swipe/eval_app_layouts.py): human-model swipes (human.py h2)
for each language's frequent words on its layout as the app draws it (GeneratedLayouts rows, centred, phone
proportions), decoded with that language's word list, no context. Top-1 / top-3 per layout: which scripts the model
carries to and which need their own synthetic training (S5 in notes/design/swipe-typing-plan.md).

    eval_app_layouts.py --model uni2/best.pt [-n 400] [--langs ar,ru,el,fi,es]
"""
import argparse
import math
import os
import random
import re
import unicodedata

import numpy as np
import torch

from decode import Trie, beam
from human import PRESETS, path as human_path
from prepare_futo import features
from uni import UniNet, phys_keys

HERE = os.path.dirname(os.path.abspath(__file__))
PACKS = "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/packs-work"
GEN = os.path.join(HERE, "../../app/src/main/kotlin/io/github/sillypuppyy/dogboard/ime/GeneratedLayouts.kt")
ASPECT = 0.46  # the letter rows' height / width on a phone (emulator: 3 rows of 165 px on 1080)


def app_layout(lang):
    """The language's default layout's letter rows."""
    src = open(GEN, encoding="utf-8").read()
    for m in re.finditer(r'LayoutInfo\("([^"]+)", "([^"]+)", "[^"]*", "[^"]*", "[^"]*", listOf\(([^)]*)\), default = (\w+)', src):
        if m.group(2) == lang and m.group(4) == "true":
            return m.group(1), re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(3))
    raise SystemExit(f"no default layout for {lang}")


def table(rows):
    """Letters (sorted) and their boxes (cx, cy, rx, ry) in 0..1: each row centred, keys one width apart."""
    n = max(len(r) for r in rows)
    boxes = {}
    for i, r in enumerate(rows):
        off = (n - len(r)) / 2
        for j, c in enumerate(r):
            if c.isalpha() and c not in boxes:
                boxes[c] = ((off + j + 0.5) / n, (i + 0.5) / len(rows), 0.5 / n, 0.5 / len(rows))
    letters = "".join(sorted(boxes))
    return letters, torch.tensor([boxes[c] for c in letters], dtype=torch.float32)


def spell(word, letters):
    """The word as keys: a letter on the keyboard as is, else its base letter (á -> a), else None."""
    out = []
    for c in word.lower():
        if c in letters:
            out.append(c)
            continue
        b = unicodedata.normalize("NFD", c)[0]
        if b in letters:
            out.append(b)
        elif unicodedata.category(c).startswith("M"):
            continue
        else:
            return None
    return "".join(out)


def lexicon(lang, letters, top):
    counts = {}
    for line in open(f"{PACKS}/{lang}/{lang}.lexicon.tsv", encoding="utf-8"):
        if line.startswith("#") or "\t" not in line:
            continue
        w, n = line.rstrip("\n").split("\t")[:2]
        k = spell(w, letters)
        if k and n.isdigit():
            counts[k] = counts.get(k, 0) + int(n)
    best = sorted(counts.items(), key=lambda kv: -kv[1])[:top]
    total = sum(n for _, n in best)
    return [(w, math.log(n / total)) for w, n in best]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("-n", type=int, default=400)
    ap.add_argument("--langs", default="es,fi,ru,el,ar")
    ap.add_argument("--top", type=int, default=100000)
    a = ap.parse_args()
    torch.set_num_threads(3)
    model = UniNet(); model.load_state_dict(torch.load(a.model, map_location="cpu")); model.eval()
    sy = 10.0 * ASPECT
    for lang in a.langs.split(","):
        lid, rows = app_layout(lang)
        letters, boxes = table(rows)
        lex = lexicon(lang, letters, a.top)
        root = Trie.build(lex)
        rng = random.Random(7)
        # words as people write them: drawn by frequency from the 5,000 most common, two letters or more
        pool = [(w, lp) for w, lp in lex[:5000] if len(w) >= 2]
        words = rng.choices([w for w, _ in pool], weights=[math.exp(lp) for _, lp in pool], k=a.n)
        centres = {c: (float(boxes[i, 0]) * 10.0, float(boxes[i, 1]) * sy) for i, c in enumerate(letters)}
        keys = phys_keys(boxes, sy).unsqueeze(0); km = torch.ones(1, len(letters), dtype=torch.bool)
        t1 = t3 = 0; misses = []
        for w in words:
            xy = [(x, y) for x, y, _ in human_path(w, centres, rng, PRESETS["h2"])]
            f = features([{"x": x, "y": y} for x, y in xy], 0.3, 1.0, 1.0)
            with torch.no_grad():
                lp = model(f.unsqueeze(0), keys, km)[0].numpy().astype(np.float64)
            c = [x for x, _ in beam(lp, root, letters, width=100, top=3)]
            t1 += c[:1] == [w]; t3 += w in c
            if c[:1] != [w] and len(misses) < 6: misses.append(f"{w}->{c[0] if c else '-'}")
        print(f"{lang} {lid} ({len(letters)} keys, rows {'/'.join(str(len(r)) for r in rows)}): "
              f"top1 {100 * t1 / a.n:.1f}%  top3 {100 * t3 / a.n:.1f}%  e.g. {' '.join(misses)}", flush=True)


if __name__ == "__main__":
    main()
