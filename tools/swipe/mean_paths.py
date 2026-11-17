#!/usr/bin/env python3
"""Mean path of real swipes vs mean path of a human model, word by word (tools/swipe/mean_paths.py).

    mean_paths.py --futo DIR --params finger.json [--words between,people,...] [--sim 100] [--png out.png]

User 2026-11-17: average all real paths of a medium-length word, average 100 simulated ones, and see
where the error sits. Paths are lined up by key progress, not raw length: each swipe is cut where it
passes its keys (in order, least total distance) and every key-to-key stretch is resampled to 16
points (plus 8 for the lead-in before the first key and 8 for the run-on after the last). Real swipes
are put on one reference keyboard shape (physical units, h/w 0.42; y rescaled per swipe), the model
swipes on that keyboard. Per stretch: distance between the two mean paths (systematic error) and the
spread around each mean (RMS, across swipes).
"""
import argparse
import json
import random

import numpy as np

import units
from physics import FingerParams, path as fpath
from stitch import align

SEG, END = 16, 8


def key_aligned(xy, keys_seq):
    """Lead-in (END) + SEG per key gap + run-on (END) points, or None."""
    if len(xy) < 4:
        return None
    picks = align(keys_seq, xy)
    p = np.asarray(xy, dtype=np.float64)

    def res(a, b, n):
        seg = p[a:b + 1]
        if len(seg) < 2:
            seg = np.vstack([p[a], p[min(b, len(p) - 1)]])
        d = np.hypot(*np.diff(seg, axis=0).T)
        cum = np.concatenate([[0.0], np.cumsum(d)])
        t = np.linspace(0, cum[-1], n) if cum[-1] > 0 else np.zeros(n)
        return np.stack([np.interp(t, cum, seg[:, 0]), np.interp(t, cum, seg[:, 1])], 1)
    parts = [res(0, picks[0], END)]
    for i in range(len(picks) - 1):
        parts.append(res(picks[i], picks[i + 1], SEG))
    parts.append(res(picks[-1], len(p) - 1, END))
    return np.vstack(parts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--params", required=True)
    ap.add_argument("--words", default="between,people,during,school,number,would")
    ap.add_argument("--sim", type=int, default=100)
    ap.add_argument("--max-real", type=int, default=400)
    ap.add_argument("--png", default="")
    a = ap.parse_args()
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    sx, sy = units.scale("phys", layout, 1.0, 0.42)
    keys = units.keys(layout, sx, sy)
    words = a.words.split(",")
    real = {w: [] for w in words}
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}")
            if w in real and len(real[w]) < a.max_real:
                # put the swipe on the reference keyboard: x as is, y in the reference's rows
                real[w].append([(p["x"] * sx, p["y"] * sy) for p in d["data"]])
            if all(len(v) >= a.max_real for v in real.values()):
                break
    params = FingerParams(**json.load(open(a.params)))
    rng = random.Random(1)
    rows = []
    figs = []
    for w in words:
        ks = [keys[c] for c in w]
        R = np.stack([x for x in (key_aligned(p, ks) for p in real[w]) if x is not None])
        S = np.stack([x for x in (key_aligned([(x, y) for x, y, _ in fpath(w, keys, rng, params)], ks) for _ in range(a.sim)) if x is not None])
        mr, ms = R.mean(0), S.mean(0)
        gap = np.hypot(*(mr - ms).T)                               # systematic error per point
        sr = np.sqrt(((R - mr) ** 2).sum(-1).mean(0))              # spread per point
        ss = np.sqrt(((S - ms) ** 2).sum(-1).mean(0))
        # per stretch: lead-in, each key gap, run-on
        names = ["lead-in"] + [f"{w[i]}>{w[i + 1]}" for i in range(len(w) - 1)] + ["run-on"]
        bounds = [0, END] + [END + SEG * (i + 1) for i in range(len(w) - 1)] + [END + SEG * (len(w) - 1) + END]
        print(f"\n{w}: {len(R)} real, {len(S)} simulated   (key widths)")
        print(f"  {'stretch':8s} {'mean-path gap':>13s} {'real spread':>12s} {'sim spread':>11s}")
        for nm, b0, b1 in zip(names, bounds, bounds[1:]):
            print(f"  {nm:8s} {gap[b0:b1].mean():13.3f} {sr[b0:b1].mean():12.3f} {ss[b0:b1].mean():11.3f}")
            rows.append((w, nm, gap[b0:b1].mean(), sr[b0:b1].mean(), ss[b0:b1].mean()))
        figs.append((w, ks, R, S, mr, ms))
    g = np.array([r[2] for r in rows]); s1 = np.array([r[3] for r in rows]); s2 = np.array([r[4] for r in rows])
    print(f"\nall stretches: mean-path gap {g.mean():.3f}, real spread {s1.mean():.3f}, sim spread {s2.mean():.3f}")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, axs = plt.subplots((len(figs) + 1) // 2, 2, figsize=(14, 4 * ((len(figs) + 1) // 2)))
        for ax, (w, ks, R, S, mr, ms) in zip(axs.flat, figs):
            for c, (x, y) in keys.items():
                ax.text(x, y, c, ha="center", va="center", color="0.65", fontsize=9)
            for r in R[:150]:
                ax.plot(r[:, 0], r[:, 1], "-", lw=0.3, alpha=0.15, color="C0")
            for s_ in S[:100]:
                ax.plot(s_[:, 0], s_[:, 1], "-", lw=0.3, alpha=0.15, color="C1")
            ax.plot(mr[:, 0], mr[:, 1], "-", lw=2.2, color="C0", label="real mean")
            ax.plot(ms[:, 0], ms[:, 1], "-", lw=2.2, color="C1", label="model mean")
            k = np.array(ks)
            ax.plot(k[:, 0], k[:, 1], "k--", lw=0.8)
            ax.set_title(w)
            ax.set_xlim(-0.3, 10.3); ax.set_ylim(4.4, -0.3); ax.set_aspect("equal")
            ax.legend(loc="lower left", fontsize=8)
        plt.tight_layout()
        plt.savefig(a.png, dpi=70)


if __name__ == "__main__":
    main()
