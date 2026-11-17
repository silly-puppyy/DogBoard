#!/usr/bin/env python3
"""Real swipes on other layouts (FUTO swipe-5, MIT) -> test sets (tools/swipe/prepare_layouts.py).

    prepare_layouts.py --futo DIR --out DIR

FUTO's 5th collection has real swipes on ClearFlow, Dvorak, KASROZ, QWERTZ, AZERTY, German, Spanish
and more, with each layout's key centres (layouts/*.json, x and y in 0..1 over the letter area). For
each (layout, language): <layout>-<lang>.pt = [(features, word)] (prepare_futo.py's features, key units
x * 10, y * 3) and keys = {letter: (x, y)} in the same units. Two-finger swipes are left out. These
are test sets only: layouts never trained on show whether a model or a human model generalises.
"""
import argparse
import json
import os

import torch

from prepare_futo import features


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--min", type=int, default=500)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    sets = {}
    with open(f"{a.futo}/swipe-5/swipe5.jsonl") as f:
        for line in f:
            d = json.loads(line)
            if not isinstance(d.get("data"), list) or not d["data"]:
                continue
            lay, lang = d.get("layout"), d.get("language")
            w = d["word"].lower().strip(".,;:!?\"()[]{}“”‘’«»¿¡").replace("'", "").replace("’", "")
            if not w.isalpha():
                continue
            sets.setdefault((lay, lang), []).append((d, w))
    for (lay, lang), items in sorted(sets.items()):
        path = f"{a.futo}/swipe-5/layouts/{lay}.json"
        if len(items) < a.min or not os.path.exists(path):
            continue
        layout = json.load(open(path))
        keys = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in layout["keys"]}
        out = [(features(d["data"], 0.3), w) for d, w in items if all(c in keys for c in w)]
        torch.save({"items": out, "keys": keys, "layout": lay, "lang": lang}, os.path.join(a.out, f"{lay}-{lang}.pt"))
        print(f"{lay}-{lang}: {len(out)} swipes, {len(keys)} keys", flush=True)


if __name__ == "__main__":
    main()
