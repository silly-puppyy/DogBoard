#!/usr/bin/env python3
"""Fits the finger model (physics.py) to real swipes (tools/swipe/fit_physics.py), from a small sample.

    fit_physics.py --futo DIR [--words 40] [--per-word 50] [--evals 400] [--out params.json]

Sample: the --words most common words of FUTO's train split, --per-word real swipes each (2,000 by
default: 0.2% of the data; all the model keeps of it is the fitted numbers). Each real swipe is paired
with a simulated swipe of the same word on the same keyboard (its canvas, physical units). Matched:
key-by-key statistics (compare_paths.py: miss distance, along / across spread per key role, path
length, run-on past the last key), the band around each word's mean path (RMS, 64 points by arc
length), speed (ms per key width) and how much longer slow swipes are than fast ones. Nelder-Mead
over the parameters (log scale), from physics.py's defaults.
"""
import argparse
import dataclasses
import json
import math
import random
from collections import defaultdict

import numpy as np
from scipy.optimize import minimize

import units
from analyze_words import resample
from compare_paths import measure, stats
from physics import FingerParams, path as fpath

FIT = ["omega", "omega_spread", "zeta", "switch", "switch_spread", "aim_first", "aim_mid", "aim_last",
       "care_spread", "bias", "noise", "noise_tau", "reach", "lift", "lift_spread"]


def summary(samples):
    """samples: [(word, keys, [(x, y, t)])] -> statistics dict."""
    r, by_word, rows = {}, defaultdict(list), []
    for w, keys, pts in samples:
        xy = [(x, y) for x, y, _ in pts]
        if len(xy) < 3:
            continue
        measure(w, xy, keys, r)
        by_word[w].append(resample(xy))
        ks = [keys[c] for c in w]
        ideal = sum(math.dist(u, v) for u, v in zip(ks, ks[1:])) or 1e-6
        rows.append((sum(math.dist(u, v) for u, v in zip(xy, xy[1:])) / ideal, (pts[-1][2] - pts[0][2]) / ideal))
    out = stats(r)
    rms = [float(np.sqrt(((np.stack(v) - np.stack(v).mean(0)) ** 2).sum(-1).mean())) for v in by_word.values() if len(v) >= 10]
    out["band"] = float(np.median(rms))
    a = np.array(rows)
    slow = a[:, 1] > np.median(a[:, 1])
    out["ms_per_unit"] = float(np.median(a[:, 1]))
    out["len_slow_minus_fast"] = float(np.median(a[slow, 0]) - np.median(a[~slow, 0]))
    return out


def error(m, r):
    e = 0.0
    for k, v in r.items():
        if k not in m:
            continue
        if k.startswith("len"):
            e += abs(m[k] - v) / 0.03
        else:
            e += abs(m[k] - v) / max(abs(v), 0.05)
    return e


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--words", type=int, default=40)
    ap.add_argument("--per-word", type=int, default=50)
    ap.add_argument("--evals", type=int, default=400)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    counts, rows = defaultdict(int), []
    # pass 1: the most common words (first 300k swipes); pass 2: --per-word swipes of each
    with open(f"{a.futo}/train.jsonl") as f:
        for i, line in enumerate(f):
            if i >= 300000:
                break
            w = json.loads(line)["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if w.isascii() and w.isalpha() and len(w) >= 3:
                counts[w] += 1
    words = set(sorted(counts, key=lambda w: -counts[w])[:a.words])
    taken = defaultdict(int)
    real = []
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if w in words and taken[w] < a.per_word and len(d["data"]) >= 4:
                sx, sy = units.scale("phys", layout, d.get("canvas_width"), d.get("canvas_height"))
                keys = units.keys(layout, sx, sy)
                real.append((w, keys, [(x, y, p["t"]) for (x, y), p in zip(units.points(d["data"], sx, sy), d["data"])]))
                taken[w] += 1
            if len(real) >= a.words * a.per_word:
                break
    R = summary(real)
    print(f"{len(real)} real swipes of {len(words)} words", flush=True)

    def sim(p):
        rng = random.Random(1)
        return summary([(w, keys, fpath(w, keys, rng, p)) for w, keys, _ in real])

    base = FingerParams()
    x0 = np.log([getattr(base, k) for k in FIT])

    def f(v):
        p = dataclasses.replace(base, **{k: float(math.exp(x)) for k, x in zip(FIT, v)})
        if p.zeta > 3 or p.omega > 200:
            return 1e9
        try:
            e = error(sim(p), R)
        except (OverflowError, ValueError, FloatingPointError):
            return 1e9
        return e if math.isfinite(e) else 1e9
    best = [1e18, None]

    def cb(v):
        e = f(v)
        if e < best[0]:
            best[0], best[1] = e, v
            print(f"error {e:.2f}", flush=True)
    print(f"start error {f(x0):.2f}", flush=True)
    res = minimize(f, x0, method="Nelder-Mead", callback=cb, options={"maxfev": a.evals, "initial_simplex": np.vstack([x0] + [x0 + 0.4 * np.eye(len(FIT))[i] for i in range(len(FIT))])})
    p = dataclasses.replace(base, **{k: round(float(math.exp(x)), 4) for k, x in zip(FIT, res.x)})
    M = sim(p)
    print(f"final error {error(M, R):.2f}\n{p}")
    for k in R:
        print(f"  {k:22s} sim {M.get(k, float('nan')):.3f}  real {R[k]:.3f}")
    if a.out:
        json.dump(dataclasses.asdict(p), open(a.out, "w"), indent=1)


if __name__ == "__main__":
    main()
