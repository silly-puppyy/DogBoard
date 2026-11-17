#!/usr/bin/env python3
"""Fits human.py's parameters to real swipes key by key (tools/swipe/fit_human.py).

    fit_human.py --futo DIR [-n 2000] [--rounds 3]

Statistics from compare_paths.py (miss distance median/p90 and along/across spread per key role,
path length, run-on past the last key) on real FUTO dev swipes and on the human model's swipes of
the same words; coordinate descent on the summed relative error. Prints the best HumanParams.
All parameters are in key units and turn angles, so they carry over to other layouts.
"""
import argparse
import dataclasses
import json
import random

from compare_paths import measure, stats
from human import HumanParams, path as human_path

STEPS = {
    "sigma_start": 0.03, "sigma_mid": 0.02, "sigma_end": 0.03, "start_dy": 0.03, "cut": 0.05,
    "wobble": 0.02, "sloppy_spread": 0.08, "corner_along": 0.05, "over_median": 0.04,
    "over_spread": 0.15, "over_curl": 0.2, "along_ratio": 0.1,
}


def error(model, real):
    e = 0.0
    for k, r in real.items():
        m = model.get(k)
        if m is None:
            continue
        if k.startswith("len"):
            e += abs(m - r) / 0.03          # length ratio: in units of 3%
        else:
            e += abs(m - r) / max(r, 0.05)  # relative
    return e


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("-n", type=int, default=2000)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--along-ratio", type=float, default=1.0, help="start value (0 = the older x/y spread)")
    ap.add_argument("--joint", type=int, default=0, help="then a Nelder-Mead fit of all parameters together, this many evaluations")
    a = ap.parse_args()
    lay = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    centres = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
    words, real = [], {}
    with open(f"{a.futo}/dev.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower()
            if not (w.isascii() and w.isalpha()):
                continue
            words.append(w)
            measure(w, [(p["x"] * 10, p["y"] * 3) for p in d["data"]], centres, real)
            if len(words) >= a.n:
                break
    real_s = stats(real)

    def score(p):
        rng = random.Random(1)
        r = {}
        for w in words:
            measure(w, [(x, y) for x, y, _ in human_path(w, centres, rng, p)], centres, r)
        return error(stats(r), real_s), stats(r)

    p = HumanParams(along_ratio=a.along_ratio)  # from the current defaults
    best, best_s = score(p)
    print(f"start {best:.2f}", flush=True)
    for rnd in range(a.rounds):
        for name, step in STEPS.items():
            for sign in (1, -1):
                while True:
                    v = getattr(p, name) + sign * step
                    if v < 0:
                        break
                    q = dataclasses.replace(p, **{name: round(v, 4)})
                    e, s = score(q)
                    if e < best - 1e-6:
                        p, best, best_s = q, e, s
                    else:
                        break
        print(f"round {rnd}: error {best:.2f}  {p}", flush=True)
    if a.joint:
        from scipy.optimize import minimize
        names = list(STEPS)

        def f(v):
            if min(v) < 0:
                return 1e9
            return score(dataclasses.replace(p, **{k: float(x) for k, x in zip(names, v)}))[0]
        x0 = [getattr(p, k) for k in names]
        simplex = [x0] + [[x + (STEPS[k] * 3 if i == j else 0) for j, (k, x) in enumerate(zip(names, x0))] for i in range(len(names))]
        res = minimize(f, x0, method="Nelder-Mead", options={"maxfev": a.joint, "initial_simplex": simplex, "xatol": 1e-3, "fatol": 1e-3})
        q = dataclasses.replace(p, **{k: round(float(x), 4) for k, x in zip(names, res.x)})
        e, s_ = score(q)
        if e < best:
            p, best, best_s = q, e, s_
        print(f"joint: error {best:.2f}  {p}", flush=True)
    print("statistic: model vs real")
    for k in real_s:
        print(f"  {k:20s} {best_s.get(k, float('nan')):.3f}  {real_s[k]:.3f}")


if __name__ == "__main__":
    main()
