#!/usr/bin/env python3
"""A model trained on real swipes reads each human model's swipes (tools/swipe/read_synthetic.py).

    read_synthetic.py --futo DIR --model real.pt --lexicon en.tsv --gen NAME=SPEC ... [-n 2000]

The reverse of train-on-synthetic: if a generator's swipes look real, a recogniser that learned from
real swipes reads them about as well as real ones (and makes the same kind of mistakes). SPEC as
prepare_synth.py --human (h1, h2, lib:PATH, finger:PATH) or "real". Words: FUTO test words (real ones),
on QWERTY at FUTO's main canvas (h/w 0.42); the real-trained model uses grid units (x*10, y*3), so
physical-unit paths are mapped back (y * 3 / 4.2).
"""
import argparse
import json
import random

import numpy as np
import torch

import units
from decode import Trie, beam, load_lexicon
from model import ALPHABET, SwipeNet
from prepare_futo import resample
from prepare_synth import gen_of
from human import path as hpath, HumanParams
from physics import FingerParams, path as fpath
from stitch import Library


def feats(xy):
    pts = resample(xy, 0.3)
    return torch.tensor([(x, y, (pts[i + 1][0] if i + 1 < len(pts) else x) - x, (pts[i + 1][1] if i + 1 < len(pts) else y) - y)
                         for i, (x, y) in enumerate(pts)], dtype=torch.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--gen", nargs="+", required=True)
    ap.add_argument("-n", type=int, default=2000)
    a = ap.parse_args()
    torch.set_num_threads(1)
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    gx, gy = 10.0, 3.0
    px, py = units.scale("phys", layout, 1.0, 0.42)
    grid_keys = units.keys(layout, gx, gy)
    phys_keys = units.keys(layout, px, py)
    real = []
    with open(f"{a.futo}/test.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if w.isascii() and w.isalpha() and len(w) >= 2:
                real.append((w, [(p["x"] * gx, p["y"] * gy) for p in d["data"]]))
    random.Random(3).shuffle(real)
    real = real[:a.n]
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    lex = load_lexicon(a.lexicon, top=160000)
    floor = min(lp for _, lp in lex)
    root = Trie.build(lex + [(w, floor) for w in sorted({w for w, _ in real} - {w for w, _ in lex})])
    for spec in a.gen:
        name, g = spec.split("=", 1)
        rng = random.Random(5)
        if g != "real":
            gen = gen_of(g)
        items = []
        for w, xy in real:
            if g == "real":
                items.append((feats(xy), w))
            elif isinstance(gen, HumanParams):
                items.append((feats([(x, y) for x, y, _ in hpath(w, grid_keys, rng, gen)]), w))
            else:
                pts = gen.path(w, phys_keys, rng) if isinstance(gen, Library) else [(x, y) for x, y, _ in fpath(w, phys_keys, rng, gen)]
                items.append((feats([(x, y * gy / py) for x, y in pts]), w))
        t1 = t3 = 0
        with torch.no_grad():
            for f, w in items:
                c = [x for x, _ in beam(m(f.unsqueeze(0))[0].numpy().astype(np.float64), root, ALPHABET, width=100, top=3)]
                t1 += c[:1] == [w]; t3 += w in c
        print(f"{name:12s} read by the real-trained model: top-1 {100 * t1 / len(items):.1f}, top-3 {100 * t3 / len(items):.1f}", flush=True)


if __name__ == "__main__":
    main()
