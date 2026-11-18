#!/usr/bin/env python3
"""Training shards from the human swipe model alone (tools/swipe/prepare_synth.py): no real swipes, so
the model's accuracy on real ones measures how human human.py is, and the same recipe works for any
layout and language that has a word list.

    prepare_synth.py --lexicon en.lexicon.tsv --layout qwerty.json --out DIR [-n 800000] [--workers 2] [--human h2]

Words: half drawn by frequency (as in text), half evenly from the --top most frequent (coverage).
Writes train-*.pt and dev.pt (synthetic too) in prepare_futo.py's format. Any layout (--layout, FUTO's
json format) and language (--lexicon, its words): with --units phys and a stitched library built in
physical units, the way to make training data for a layout nobody has swiped on yet.
"""
import argparse
import json
import math
import os
import random
from multiprocessing import Pool

import numpy as np
import torch

from decode import load_lexicon
from human import PRESETS, path as human_path
from stitch import Library
from physics import FingerParams, path as finger_path
import units
from prepare_futo import resample

CENTRES = {}
PARAMS = []
SHAPE = {}  # layout json and aspect range, when each swipe gets its own keyboard shape


# A simulated swipe longer than this (in keyboard-width units: a real one covers a few dozen at most) or not
# finite is a numerically unstable finger (stiff, barely damped springs with wide spreads fly off): dropped
# before resampling, which would cut it into millions of points (2026-11-18: the x1.5 / x2 spread sweep ate
# all memory and the out-of-memory killer took the desktop session twice).
MAX_PATH = 120.0


def feats(xy):
    # plain arrays: torch's tensor sharing between processes is slow for many small tensors
    total = 0.0
    for (x0, y0), (x1, y1) in zip(xy, xy[1:]):
        total += math.hypot(x1 - x0, y1 - y0)
        if not math.isfinite(total) or total > MAX_PATH:
            return None
    pts = resample(xy, 0.3)
    return np.array([(x, y, (pts[i + 1][0] if i + 1 < len(pts) else x) - x, (pts[i + 1][1] if i + 1 < len(pts) else y) - y)
                     for i, (x, y) in enumerate(pts)], dtype=np.float32)


def make(job):
    seed, words = job
    rng = random.Random(seed)
    gen = PARAMS[0]
    if SHAPE:
        out = []
        for w in words:
            sx, sy = units.scale("phys", SHAPE["layout"], 1.0, rng.uniform(*SHAPE["range"]))
            c = units.keys(SHAPE["layout"], sx, sy)
            if isinstance(gen, FingerParams):
                out.append((feats([(x, y) for x, y, _ in finger_path(w, c, rng, gen)]), w))
            elif isinstance(gen, Library):
                out.append((feats(gen.path(w, c, rng)), w))
            else:
                out.append((feats([(x, y) for x, y, _ in human_path(w, c, rng, gen)]), w))
        return [(f, w) for f, w in out if f is not None]
    if isinstance(gen, FingerParams):
        out = [(feats([(x, y) for x, y, _ in finger_path(w, CENTRES, rng, gen)]), w) for w in words]
    elif isinstance(gen, Library):
        out = [(feats(gen.path(w, CENTRES, rng)), w) for w in words]
    else:
        out = [(feats([(x, y) for x, y, _ in human_path(w, CENTRES, rng, gen)]), w) for w in words]
    return [(f, w) for f, w in out if f is not None]


def gen_of(spec):
    if spec.startswith("lib:"):
        return Library.load(spec[4:])
    if spec.startswith("finger:"):
        return FingerParams(**json.load(open(spec[7:])))
    return PRESETS[spec]


def init(centres, params, shape=None):
    CENTRES.update(centres)
    PARAMS.append(params)
    if shape:
        SHAPE.update(shape)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=800000)
    ap.add_argument("--top", type=int, default=100000)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--uniform", type=float, default=0.5, help="share of words drawn evenly from --top (the rest by frequency, as in text)")
    ap.add_argument("--shard", type=int, default=200000)
    ap.add_argument("--units", default="grid", choices=("grid", "phys"))
    ap.add_argument("--aspect", type=float, default=0.42, help="phys: the keyboard's letter area height / width")
    ap.add_argument("--aspect-max", type=float, default=0.0, help="phys: each swipe its own aspect, between --aspect and this")
    ap.add_argument("--human", default="h2", help=f"{sorted(PRESETS)}, lib:PATH (stitch.py library) or finger:PARAMS.json (physics.py)")
    a = ap.parse_args()
    torch.set_num_threads(1)
    lay = json.load(open(a.layout))
    sx, sy = units.scale(a.units, lay, 1.0, a.aspect)
    centres = units.keys(lay, sx, sy)
    letters = "".join(sorted(centres))
    lex = [(w, lp) for w, lp in load_lexicon(a.lexicon, top=a.top, letters=None if letters == "abcdefghijklmnopqrstuvwxyz" else letters)
           if len(w) >= 2 or w in ("a", "i")]
    words = [w for w, _ in lex]
    weights = [math.exp(lp) for _, lp in lex]
    rng = random.Random(42)
    os.makedirs(a.out, exist_ok=True)

    def draw(n):
        even = int(n * a.uniform)
        return rng.choices(words, weights=weights, k=n - even) + rng.choices(words, k=even)

    with Pool(a.workers, initializer=init, initargs=(centres, gen_of(a.human), {"layout": lay, "range": (a.aspect, a.aspect_max)} if a.aspect_max > a.aspect else None)) as pool:
        def build(n, seed0):
            ws = draw(n)
            rng.shuffle(ws)
            jobs = [(seed0 + i, ws[i:i + 2000]) for i in range(0, n, 2000)]
            return [(torch.from_numpy(f), w) for part in pool.map(make, jobs) for f, w in part]
        torch.save(build(20000, 10 ** 9), os.path.join(a.out, "dev.pt"))
        for part, start in enumerate(range(0, a.n, a.shard)):
            torch.save(build(min(a.shard, a.n - start), start), os.path.join(a.out, f"train-{part}.pt"))
            print(f"shard {part} done", flush=True)


if __name__ == "__main__":
    main()
