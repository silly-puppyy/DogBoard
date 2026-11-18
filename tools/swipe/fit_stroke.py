#!/usr/bin/env python3
"""Fit the physical finger (physics.py mode "stroke") to real swipes' phenomena (tools/swipe/fit_stroke.py).

    fit_stroke.py --futo DIR --start stroke-start.json --out stroke-v1.json [-n 2000] [--evals 300]

The objective is how far the model's phenomena (phenomena.py: corner and straight-pass speed, time per key, speed vs
stroke length, hesitations, wobble size and frequency, veering, overshoot, run-on, start dwell, bow size) are from
the real ones, each as a relative error (correlations absolute), plus the mean-path gap of the commonest words in
the sample (mean_paths.py alignment) so shapes stay right. Same words, same random numbers every evaluation
(common random numbers), so CMA-ES compares parameters, not luck. Real swipes: FUTO train (a few hundred people);
the test set (swipe-5 real QWERTY) is never used here.
"""
import argparse
import dataclasses
import json
import math
import random
from collections import defaultdict

import numpy as np

import units
from mean_paths import key_aligned
from phenomena import measure, summarize
from physics import FingerParams, path as fpath

# fitted, in the space CMA-ES moves in: (name, to-space, from-space)
LOG = (math.log, math.exp)
LIN = (lambda v: v, lambda v: v)
FIT = [("omega", *LOG), ("zeta", *LOG), ("noise", *LOG), ("noise_tau", *LOG), ("fitts_a", *LOG), ("fitts_b", *LOG),
       ("overlap", *LIN), ("bow_bias", *LOG), ("bow_sd", *LOG), ("hes_p", *LOG), ("hes_ms", *LOG), ("dwell", *LOG),
       ("follow", *LOG), ("omega_spread", *LOG)]
TARGETS = {  # metric: (kind, weight)
    "corner slow (v/median)": ("rel", 1.0), "pass slow (v/median)": ("rel", 1.0), "ms per key": ("rel", 1.5),
    "speed~length corr": ("abs", 1.0), "hesitation share": ("rel", 0.5), "wobble RMS (keys)": ("rel", 0.7),
    "wobble Hz": ("rel", 0.5), "veer share": ("rel", 0.7), "corner overshoot (keys)": ("rel", 0.7),
    "run-on (keys)": ("rel", 0.5), "start dwell ms": ("rel", 0.5), "bow |mean| (/len)": ("rel", 0.7),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--start", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=2000)
    ap.add_argument("--evals", type=int, default=300)
    a = ap.parse_args()
    import cma
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    sx, sy = units.scale("phys", layout, 1.0, 0.42)
    keys = units.keys(layout, sx, sy)
    base = FingerParams(**json.load(open(a.start)))
    # real swipes: phenomena, and the key-aligned paths of the commonest words
    words, real, bows = [], defaultdict(list), defaultdict(list)
    paths = defaultdict(list)
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}")
            if not (w.isalpha() and 3 <= len(w) <= 9 and all(c in keys for c in w)):
                continue
            xy = [(q["x"] * sx, q["y"] * sy) for q in d["data"]]
            m = measure(xy, [q["t"] for q in d["data"]], [keys[c] for c in w])
            if m is None:
                continue
            for k, v in m.items():
                real[k] += v
            bows[d["session"]] += m.get("bow_side", [])
            al = key_aligned(xy, [keys[c] for c in w])
            if al is not None:
                paths[w].append(al)
            words.append(w)
            if len(words) >= a.n:
                break
    R = summarize(real, bows)
    common = [w for w, ps in sorted(paths.items(), key=lambda kv: -len(kv[1]))[:6] if len(ps) >= 25]
    real_mean = {w: np.mean(paths[w], 0) for w in common}
    print("real:", {k: round(R[k], 3) for k in TARGETS}, "\nshape words:", common, flush=True)

    def params_of(x):
        kw = {name: float(np.clip(frm(v), 1e-4, 1e5)) for (name, _, frm), v in zip(FIT, x)}
        kw["overlap"] = float(np.clip(kw["overlap"], 0.0, 0.9))
        kw["zeta"] = float(np.clip(kw["zeta"], 0.15, 2.5))
        kw["hes_p"] = float(np.clip(kw["hes_p"], 0.0, 0.5))
        return dataclasses.replace(base, **kw)

    def objective(x):
        p = params_of(x)
        sim, sbows = defaultdict(list), defaultdict(list)
        for i, w in enumerate(words):
            sp = fpath(w, keys, random.Random(1000003 * (i // 80) + i), p)
            m = measure([(q[0], q[1]) for q in sp], [q[2] for q in sp], [keys[c] for c in w])
            if m is None:
                continue
            for k, v in m.items():
                sim[k] += v
            sbows[i // 80] += m.get("bow_side", [])
        S = summarize(sim, sbows)
        loss = 0.0
        for k, (kind, wt) in TARGETS.items():
            r, s = R[k], S[k]
            if not (np.isfinite(r) and np.isfinite(s)):
                loss += 4.0 * wt
                continue
            e = (s - r) / max(abs(r), 1e-3) if kind == "rel" else (s - r) / 0.1
            loss += wt * min(e * e, 4.0)
        rng = random.Random(7)
        for w in common:
            al = [x for x in (key_aligned([(q[0], q[1]) for q in fpath(w, keys, rng, p)], [keys[c] for c in w]) for _ in range(60)) if x is not None]
            if al:
                loss += (float(np.hypot(*(np.mean(al, 0) - real_mean[w]).T).mean()) / 0.05) ** 2 / len(common)
        return loss, S

    x0 = [to(getattr(base, name)) for name, to, _ in FIT]
    es = cma.CMAEvolutionStrategy(x0, 0.3, {"maxfevals": a.evals, "verbose": -9, "seed": 2, "popsize": 12})
    best = (math.inf, None, None)
    gen = 0
    while not es.stop():
        xs = es.ask()
        res = [objective(x) for x in xs]
        es.tell(xs, [r[0] for r in res])
        for x, (l, S) in zip(xs, res):
            if l < best[0]:
                best = (l, x, S)
        gen += 1
        print(f"gen {gen}: best loss {best[0]:.3f}", flush=True)
        p = params_of(best[1])
        json.dump({k: v for k, v in dataclasses.asdict(p).items()}, open(a.out, "w"), indent=1)
    print("\nfitted:", {name: round(getattr(params_of(best[1]), name), 4) for name, _, _ in FIT})
    S = best[2]
    print(f"  {'phenomenon':26s} {'real':>9s} {'model':>9s}")
    for k in R:
        print(f"  {k:26s} {R[k]:9.3f} {S[k]:9.3f}")


if __name__ == "__main__":
    main()
