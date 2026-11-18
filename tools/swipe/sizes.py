#!/usr/bin/env python3
"""Swipe recognition across keyboard sizes and shapes (tools/swipe/sizes.py).

    sizes.py eval --futo DIR --model best.pt --finger params.json --lexicon en.tsv [-n 1500]
    sizes.py make --futo DIR --finger params.json --lexicon en.tsv --out DIR [-n 300000]

User 2026-11-18: how does the recogniser cope with extreme sizes (landscape, very tall, a small
floating keyboard)? Real swipes only cover keyboard shapes (height / width of the letter area) of
about 0.38-0.8, so the extremes come from the physical finger model (physics.py, physical units):
the keyboard drawn at a shape and a scale (smaller keys under the same finger), the path then mapped
onto the recogniser's grid exactly as the app does (one unit per key width across, per row down).

eval: top-1 per condition for FUTO test words (lexicon decoding as evaluate.py).
make: training shards (prepare_futo format) with shapes and scales drawn per swipe, to mix with real
swipes when fine-tuning (train.py --init).
"""
import argparse
import json
import math
import os
import random

import numpy as np
import torch

import units
from decode import Trie, beam, load_lexicon
from model import ALPHABET, SwipeNet
from physics import FingerParams, path as fpath
from prepare_futo import resample

CONDITIONS = [  # name, letter-area height / width, scale (1 = a phone keyboard's full width)
    ("landscape", 0.18, 1.6), ("wide", 0.30, 1.0), ("phone", 0.42, 1.0), ("tall", 0.60, 1.0),
    ("very tall", 0.80, 1.0), ("floating small", 0.42, 0.7), ("tablet", 0.30, 2.0),
]


def swipe_features(word, layout, aspect, scale, gen, rng):
    """The finger model on a keyboard of this shape and scale; features in the recogniser's grid units."""
    sx, sy = 10.0 * scale, 10.0 * aspect * scale        # physical units: tenths of a phone keyboard's width
    keys = units.keys(layout, sx, sy)
    pts = [(x, y) for x, y, _ in fpath(word, keys, rng, gen)]
    gx = [(x * 10.0 / sx, y * 3.0 / sy) for x, y in pts]  # onto the grid, as the app maps its keys
    r = resample(gx, 0.3)
    return torch.tensor([(x, y, (r[i + 1][0] if i + 1 < len(r) else x) - x, (r[i + 1][1] if i + 1 < len(r) else y) - y)
                         for i, (x, y) in enumerate(r)], dtype=torch.float32)


def test_words(futo, n):
    words = []
    with open(f"{futo}/test.jsonl") as f:
        for line in f:
            w = json.loads(line)["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if w.isascii() and w.isalpha() and len(w) >= 2:
                words.append(w)
    random.Random(4).shuffle(words)
    return words[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=("eval", "make"))
    ap.add_argument("--futo", required=True)
    ap.add_argument("--finger", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--model", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("-n", type=int, default=1500)
    a = ap.parse_args()
    torch.set_num_threads(1)
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    gen = FingerParams(**json.load(open(a.finger)))
    if a.mode == "eval":
        m = SwipeNet()
        m.load_state_dict(torch.load(a.model, map_location="cpu"))
        m.eval()
        lex = load_lexicon(a.lexicon, top=160000)
        words = test_words(a.futo, a.n)
        floor = min(lp for _, lp in lex)
        root = Trie.build(lex + [(w, floor) for w in sorted(set(words) - {w for w, _ in lex})])
        for name, aspect, scale in CONDITIONS:
            rng = random.Random(9)
            ok = 0
            with torch.no_grad():
                for w in words:
                    f = swipe_features(w, layout, aspect, scale, gen, rng)
                    c = beam(m(f.unsqueeze(0))[0].numpy().astype(np.float64), root, ALPHABET, width=100, top=1)
                    ok += bool(c) and c[0][0] == w
            print(f"{name:15s} h/w {aspect:.2f} scale {scale:.1f}: top-1 {100 * ok / len(words):.1f}", flush=True)
        return
    # make: training shards with a shape and scale per swipe (log-uniform scale, uniform shape)
    lex = load_lexicon(a.lexicon, top=100000)
    words = [w for w, _ in lex]
    weights = [math.exp(lp) for _, lp in lex]
    rng = random.Random(42)
    os.makedirs(a.out, exist_ok=True)
    for part, n in enumerate([a.n]):
        items = []
        for i in range(n):
            w = rng.choices(words, weights=weights)[0] if i % 2 else rng.choice(words)
            aspect = rng.uniform(0.15, 0.9)
            scale = math.exp(rng.uniform(math.log(0.65), math.log(2.0)))
            items.append((swipe_features(w, layout, aspect, scale, gen, rng), w))
        torch.save(items, os.path.join(a.out, f"train-synth-{part}.pt"))
    print(f"{a.n} sized swipes -> {a.out}")


if __name__ == "__main__":
    main()
