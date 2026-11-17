#!/usr/bin/env python3
"""Training shards from the human swipe model alone (tools/swipe/prepare_synth.py): no real swipes, so
the model's accuracy on real ones measures how human human.py is, and the same recipe works for any
layout and language that has a word list.

    prepare_synth.py --lexicon en.lexicon.tsv --layout qwerty.json --out DIR [-n 800000] [--workers 2] [--human h2]

Words: half drawn by frequency (as in text), half evenly from the --top most frequent (coverage).
Writes train-*.pt and dev.pt (synthetic too) in prepare_futo.py's format.
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
from prepare_futo import resample

CENTRES = {}
PARAMS = []


def feats(xy):
    # plain arrays: torch's tensor sharing between processes is slow for many small tensors
    pts = resample(xy, 0.3)
    return np.array([(x, y, (pts[i + 1][0] if i + 1 < len(pts) else x) - x, (pts[i + 1][1] if i + 1 < len(pts) else y) - y)
                     for i, (x, y) in enumerate(pts)], dtype=np.float32)


def make(job):
    seed, words = job
    rng = random.Random(seed)
    return [(feats([(x, y) for x, y, _ in human_path(w, CENTRES, rng, PARAMS[0])]), w) for w in words]


def init(centres, params):
    CENTRES.update(centres)
    PARAMS.append(params)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=800000)
    ap.add_argument("--top", type=int, default=100000)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--shard", type=int, default=200000)
    ap.add_argument("--human", default="h2", choices=sorted(PRESETS))
    a = ap.parse_args()
    torch.set_num_threads(1)
    lay = json.load(open(a.layout))
    centres = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
    lex = [(w, lp) for w, lp in load_lexicon(a.lexicon, top=a.top) if len(w) >= 2 or w in ("a", "i")]
    words = [w for w, _ in lex]
    weights = [math.exp(lp) for _, lp in lex]
    rng = random.Random(42)
    os.makedirs(a.out, exist_ok=True)

    def draw(n):
        half = n // 2
        return rng.choices(words, weights=weights, k=half) + rng.choices(words, k=n - half)

    with Pool(a.workers, initializer=init, initargs=(centres, PRESETS[a.human])) as pool:
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
