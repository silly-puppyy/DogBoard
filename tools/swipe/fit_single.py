#!/usr/bin/env python3
"""Where does the finger model fail? Single real swipes fitted one by one, and path heatmaps (tools/swipe/fit_single.py).

    fit_single.py --futo DIR --params pursuit.json pick                       # words that cross the keyboard without crossing themselves
    fit_single.py --futo DIR --params pursuit.json heatmap WORD --png out.png  # real vs model path density, mean paths
    fit_single.py --futo DIR --params pursuit.json fit WORD -n 40 --png out.png

User 2026-11-18: "fit the parameters of the model over single paths and see where it falters, maybe a visual
comparison; how does the real path heatmap of a word that goes over the keyboard without intersecting itself
look?" Synthetic-only training plateaued at 74.9 top-1 (real-trained 82.8), and 3x more synthetic data made it
worse (70.0): the gap is the simulated paths' realism, not their number.

fit: the pursuit model made deterministic (no motor noise, no drift, aims exactly where the fit puts them) and
fitted to each real swipe by CMA-ES, two ways:
  A  motion only: finger stiffness and damping, guide speed, corner slowing (amount, reach), run-on past the last
     key, one overall offset. What A misses is motion the model cannot make.
  B  A + each key's own aim point. What B still misses is motion even with the aims free; A - B is aiming.
Error = mean distance between key-aligned paths (mean_paths.py: lead-in, 16 points per key gap, run-on), in key
widths, reported per stretch and along each gap (start / middle / end of a stroke).
"""
import argparse
import dataclasses
import json
import math
import random

import numpy as np

import units
from mean_paths import END, SEG, key_aligned
from physics import FingerParams, _pursuit, path as fpath


def load_real(futo, words, sx, sy, max_n):
    real = {w: [] for w in words}
    with open(f"{futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}")
            if w in real and len(real[w]) < max_n:
                real[w].append([(p["x"] * sx, p["y"] * sy) for p in d["data"]])
            if all(len(v) >= max_n for v in real.values()):
                break
    return real


def word_counts(futo, limit=400000):
    c = {}
    with open(f"{futo}/train.jsonl") as f:
        for i, line in enumerate(f):
            if i >= limit:
                break
            w = json.loads(line)["word"].lower().strip(".,;:!?\"()[]{}")
            if w.isalpha():
                c[w] = c.get(w, 0) + 1
    return c


def crosses(route):
    """Whether the key route (a polyline) crosses itself (adjacent segments excepted)."""
    def inter(p1, p2, p3, p4):
        def o(a, b, c):
            return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        return (o(p1, p2, p3) * o(p1, p2, p4) < 0) and (o(p3, p4, p1) * o(p3, p4, p2) < 0)
    segs = list(zip(route, route[1:]))
    return any(inter(*segs[i], *segs[j]) for i in range(len(segs)) for j in range(i + 2, len(segs)))


def deterministic(params, word, centres, x, offsets=None):
    """The pursuit path for motion parameters x (and per-key aim offsets), no randomness."""
    om, z, gs, cs, cr, fo, bx, by = x[:8]
    p = dataclasses.replace(params, zeta=float(np.clip(z, 0.2, 2.0)), guide_speed=float(math.exp(gs)),
                            corner_slow=float(np.clip(cs, 0, 0.95)), corner_r=float(abs(cr) + 0.05),
                            follow=float(abs(fo)), noise=0.0, drift=0.0, lift_spread=0.0, dwell=0.0)
    c = dict(centres)
    if offsets is not None:
        for i, ch in enumerate(dict.fromkeys(word)):
            c[ch] = (centres[ch][0] + offsets[2 * i], centres[ch][1] + offsets[2 * i + 1])
    who = {"speed": 1.0, "omega": float(math.exp(om)), "care": 0.0, "bias": (bx, by), "switch": params.switch}
    return [(px, py) for px, py, _ in _pursuit(word, c, random.Random(0), p, who)]


def fit_one(params, word, keys, real_xy, per_key, evals):
    import cma
    ks = [keys[c] for c in word]
    target = key_aligned(real_xy, ks)
    if target is None:
        return None
    nk = len(dict.fromkeys(word))
    x0 = [math.log(28.0), params.zeta, math.log(params.guide_speed), params.corner_slow, params.corner_r, params.follow, 0.0, 0.0]
    if per_key:
        x0 += [0.0] * (2 * nk)

    def loss(x):
        sim = deterministic(params, word, keys, x, x[8:] if per_key else None)
        a = key_aligned(sim, ks)
        return 9.0 if a is None else float(np.hypot(*(a - target).T).mean())
    es = cma.CMAEvolutionStrategy(x0, 0.25, {"maxfevals": evals, "verbose": -9, "seed": 1})
    es.optimize(loss)
    x = es.result.xbest
    sim = deterministic(params, word, keys, x, x[8:] if per_key else None)
    return target, key_aligned(sim, ks), x, es.result.fbest


def stretch_names(word):
    return ["lead-in"] + [f"{word[i]}>{word[i + 1]}" for i in range(len(word) - 1)] + ["run-on"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--params", required=True)
    ap.add_argument("mode", choices=("pick", "heatmap", "fit"))
    ap.add_argument("word", nargs="?", default="")
    ap.add_argument("-n", type=int, default=40)
    ap.add_argument("--evals", type=int, default=400)
    ap.add_argument("--png", default="")
    a = ap.parse_args()
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    sx, sy = units.scale("phys", layout, 1.0, 0.42)
    keys = units.keys(layout, sx, sy)
    params = FingerParams(**json.load(open(a.params)))

    if a.mode == "pick":
        c = word_counts(a.futo)
        out = []
        for w, n in c.items():
            if n < 150 or not 4 <= len(w) <= 7 or any(ch not in keys for ch in w):
                continue
            route = [keys[ch] for ch in w]
            route = [k for i, k in enumerate(route) if i == 0 or k != route[i - 1]]
            span = max(k[0] for k in route) - min(k[0] for k in route)
            if not crosses(route) and span >= 5.0:
                out.append((span, n, w))
        for span, n, w in sorted(out, reverse=True)[:25]:
            print(f"{w:10s} {n:5d} swipes, spans {span:.1f} key widths")
        return

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    w = a.word
    ks = [keys[c] for c in w]
    real = load_real(a.futo, [w], sx, sy, 2000 if a.mode == "heatmap" else a.n)[w]

    def draw_keys(ax):
        for ch, (x, y) in keys.items():
            ax.add_patch(plt.Rectangle((x - 0.47, y - 0.47 * sy / 3 * 3 / sy * 0.9), 0.94, 0.85 * sy / 3 if sy > 0 else 0.9, fill=False, lw=0.4, color="0.75"))
            ax.text(x, y, ch, ha="center", va="center", color="0.55", fontsize=8)
        k = np.array(ks)
        ax.plot(k[:, 0], k[:, 1], "k--", lw=0.7, alpha=0.6)
        ax.set_xlim(-0.3, 10.3); ax.set_ylim(sy + 0.3, -0.3); ax.set_aspect("equal")

    if a.mode == "heatmap":
        rng = random.Random(3)
        sim = [[(x, y) for x, y, _ in fpath(w, keys, rng, params)] for _ in range(len(real))]
        fig, axs = plt.subplots(2, 1, figsize=(11, 9))
        R = [p for p in (key_aligned(r, ks) for r in real) if p is not None]
        S = [p for p in (key_aligned(s, ks) for s in sim) if p is not None]
        for ax, paths, al, name in ((axs[0], real, R, f"real ({len(real)} people's swipes)"), (axs[1], sim, S, f"model ({len(sim)} simulated, {a.params.split('/')[-1]})")):
            pts = np.vstack([np.asarray(p) for p in paths if len(p) > 1])
            ax.hist2d(pts[:, 0], pts[:, 1], bins=[220, int(220 * (sy + 0.6) / 10.6)], range=[[-0.3, 10.3], [-0.3, sy + 0.3]], cmap="magma", cmin=1)
            mr = np.mean(al, 0)
            ax.plot(mr[:, 0], mr[:, 1], "-", color="cyan", lw=2, label="mean path")
            if al is S:
                mR = np.mean(R, 0); ax.plot(mR[:, 0], mR[:, 1], "--", color="white", lw=1.5, label="real mean path")
            draw_keys(ax)
            ax.set_title(f"'{w}': {name}")
            ax.legend(loc="lower right", fontsize=8)
        plt.tight_layout(); plt.savefig(a.png, dpi=80)
        gap = np.hypot(*(np.mean(R, 0) - np.mean(S, 0)).T)
        print(f"mean-path gap {gap.mean():.3f} key widths (max {gap.max():.3f})")
        return

    # fit
    rows = {"A": [], "B": []}
    shows = []
    for i, r in enumerate(real[: a.n]):
        fa = fit_one(params, w, keys, r, False, a.evals)
        fb = fit_one(params, w, keys, r, True, a.evals)
        if fa is None or fb is None:
            continue
        rows["A"].append(np.hypot(*(fa[1] - fa[0]).T)); rows["B"].append(np.hypot(*(fb[1] - fb[0]).T))
        if len(shows) < 6:
            shows.append((r, fa, fb))
    names = stretch_names(w)
    bounds = [0, END] + [END + SEG * (i + 1) for i in range(len(w) - 1)] + [END + SEG * (len(w) - 1) + END]
    for m in ("A", "B"):
        E = np.mean(rows[m], 0)
        print(f"\nfit {m} ({'motion only' if m == 'A' else 'motion + each key aim'}), {len(rows[m])} swipes: mean error {E.mean():.3f} key widths")
        print("  " + "  ".join(f"{nm} {E[b0:b1].mean():.3f}" for nm, b0, b1 in zip(names, bounds, bounds[1:])))
        gaps = np.stack([E[END + SEG * g: END + SEG * (g + 1)] for g in range(len(w) - 1)])
        thirds = gaps.reshape(len(w) - 1, 4, 4).mean((0, 2))
        print(f"  along a stroke (start .. end): {' '.join(f'{v:.3f}' for v in thirds)}")
    if a.png:
        fig, axs = plt.subplots(len(shows), 1, figsize=(10, 3.4 * len(shows)))
        for ax, (r, fa, fb) in zip(np.atleast_1d(axs), shows):
            rr = np.asarray(r)
            ax.plot(rr[:, 0], rr[:, 1], "-", color="k", lw=2.2, label="real swipe")
            ax.plot(fa[1][:, 0], fa[1][:, 1], "-", color="C1", lw=1.6, label=f"fit A, motion only ({np.hypot(*(fa[1] - fa[0]).T).mean():.2f})")
            ax.plot(fb[1][:, 0], fb[1][:, 1], "-", color="C2", lw=1.6, label=f"fit B, + key aims ({np.hypot(*(fb[1] - fb[0]).T).mean():.2f})")
            draw_keys(ax)
            ax.legend(loc="lower right", fontsize=7)
        plt.tight_layout(); plt.savefig(a.png, dpi=75)


if __name__ == "__main__":
    main()
