#!/usr/bin/env python3
"""Does a human model built on QWERTY carry over to other layouts? (tools/swipe/human_layouts.py)

    human_layouts.py --futo DIR --lib stitch-lib.pkl [-n 1500]

For each held-out layout in FUTO's swipe-5 (real swipes on ClearFlow, Dvorak, KASROZ, ...), the
key-by-key statistics (compare_paths.py) of the real swipes against swipes the human models make for
the same words on that layout: the parametric h2 and the stitched model (its pieces all from QWERTY).
A human model that only learned QWERTY habits shows a larger error on the other layouts than on
QWERTY itself.
"""
import argparse
import json
import random

from compare_paths import measure, stats
from fit_human import error
from human import PRESETS, path as hpath
from stitch import Library


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--lib", required=True)
    ap.add_argument("-n", type=int, default=1500)
    a = ap.parse_args()
    lib = Library.load(a.lib)
    by = {}
    with open(f"{a.futo}/swipe-5/swipe5.jsonl") as f:
        for line in f:
            d = json.loads(line)
            if d.get("language") != "en" or not isinstance(d.get("data"), list):
                continue
            w = d["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if w.isascii() and w.isalpha() and len(w) >= 2:
                by.setdefault(d["layout"], []).append((w, d["data"]))
    print("layout       n     error vs real: h2   stitched   (lower = more like real swipes)")
    for lay, items in sorted(by.items()):
        if len(items) < 500:
            continue
        layout = json.load(open(f"{a.futo}/swipe-5/layouts/{lay}.json"))
        keys = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in layout["keys"]}
        random.Random(2).shuffle(items)
        real, h2, st = {}, {}, {}
        rng = random.Random(3)
        for w, data in items[:a.n]:
            if not all(c in keys for c in w):
                continue
            measure(w, [(p["x"] * 10, p["y"] * 3) for p in data], keys, real)
            measure(w, [(x, y) for x, y, _ in hpath(w, keys, rng, PRESETS["h2"])], keys, h2)
            measure(w, lib.path(w, keys, rng), keys, st)
        R = stats(real)
        print(f"{lay:10s} {len(items[:a.n]):5d}   {error(stats(h2), R):6.2f}   {error(stats(st), R):6.2f}", flush=True)


if __name__ == "__main__":
    main()
