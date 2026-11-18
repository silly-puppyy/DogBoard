#!/usr/bin/env python3
"""Are generated swipes as varied and original as people's? (tools/swipe/diversity.py)

    diversity.py --futo DIR --gen NAME=SPEC [--gen ...] [--words 20] [--per 120] [--skip 300000]

User 2026-11-18: "Are you sure the cvae is being as unique and original as the humans are?" Per common word,
held-out real swipes (FUTO train lines after --skip: cvae.py prep used the first 300,000) against each generator
(prepare_synth.py --human specs), all as key-aligned paths (mean_paths.py), key widths:
  spread     mean distance from the word's mean path (how much swipes of one word differ)
  pairwise   mean distance between two swipes of the word
  precision  share of generated swipes inside the real swipes' k-NN balls (k=3): realistic
  recall     share of real swipes inside the generated swipes' k-NN balls: the human variety it covers
             (Kynkaanniemi et al. 2019, improved precision and recall)
  copying    median distance to the nearest TRAINING swipe of the word, generated / held-out real
             (< 1: closer to what it learned from than new people are, i.e. memorising)
  rough      high-frequency jitter: RMS of the second difference along the path (wobble)
"""
import argparse
import json
import random
from collections import defaultdict

import numpy as np

import units
from mean_paths import key_aligned
from prepare_synth import gen_of
from physics import FingerParams, path as fpath


def dist(a, b):
    """Mean point distance between key-aligned paths, all pairs: a [n, P, 2], b [m, P, 2] -> [n, m]."""
    return np.hypot(a[:, None, :, 0] - b[None, :, :, 0], a[:, None, :, 1] - b[None, :, :, 1]).mean(-1)


def knn_radius(x, k=3):
    d = dist(x, x)
    np.fill_diagonal(d, np.inf)
    return np.sort(d, 1)[:, k - 1]


def inside(q, ref, rad):
    return float((dist(q, ref) <= rad[None, :]).any(1).mean())


def draw(gen, w, keys, rng, n):
    if hasattr(gen, "paths"):
        return gen.paths([w] * n, keys, rng)
    if isinstance(gen, FingerParams):
        return [[(x, y) for x, y, _ in fpath(w, keys, rng, gen)] for _ in range(n)]
    return [gen.path(w, keys, rng) for _ in range(n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--gen", action="append", default=[])
    ap.add_argument("--words", type=int, default=20)
    ap.add_argument("--per", type=int, default=120)
    ap.add_argument("--skip", type=int, default=300000)
    ap.add_argument("--swipe5", default="", help="LAYOUT:ASPECT: real swipes from swipe-5 on that layout (no copying test)")
    a = ap.parse_args()
    lay_name, _, asp = (a.swipe5 or "qwerty:0.42").partition(":")
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/{lay_name}.json"))
    sx, sy = units.scale("phys", layout, 1.0, float(asp))
    keys = units.keys(layout, sx, sy)
    train, held = defaultdict(list), defaultdict(list)
    if a.swipe5:  # all of that layout's swipes are new people; no training pool
        a.skip = 0
    with open(f"{a.futo}/swipe-5/swipe5.jsonl" if a.swipe5 else f"{a.futo}/train.jsonl") as f:
        for i, line in enumerate(f):
            if i >= a.skip + 300000:
                break
            d = json.loads(line)
            if a.swipe5 and (d.get("layout") != lay_name or not isinstance(d.get("data"), list)):
                continue  # other layouts; multi-stroke records (as prepare_layouts.py skips them)
            w = d["word"].lower()
            if not (w.isalpha() and len(w) >= 3 and all(c in keys for c in w)):
                continue
            pool = train if i < a.skip else held
            if len(pool[w]) >= (400 if pool is train else a.per):
                continue
            ks = [keys[c] for c in w]
            ks = [k for j, k in enumerate(ks) if j == 0 or k != ks[j - 1]]
            al = key_aligned([(q["x"] * sx, q["y"] * sy) for q in d["data"]], ks)
            if al is not None and np.isfinite(al).all():
                pool[w].append(al)
    words = [w for w in sorted(held, key=lambda w: -len(held[w])) if len(held[w]) >= a.per and (a.swipe5 or len(train[w]) >= 100)][:a.words]
    print(f"{len(words)} words: {', '.join(words)}")
    gens = [("real (new people)", None)] + [(s.split("=", 1)[0], gen_of(s.split("=", 1)[1])) for s in a.gen]
    out = {}
    for name, gen in gens:
        rows = defaultdict(list)
        for w in words:
            R = np.stack(held[w][:a.per]); T = np.stack(train[w][:400]) if train[w] else np.zeros((0,) + R.shape[1:])
            if gen is None:
                G = R[: a.per // 2]; R = R[a.per // 2:]   # real vs real: two halves of new people
            else:
                ks = [keys[c] for c in w]
                ks = [k for j, k in enumerate(ks) if j == 0 or k != ks[j - 1]]
                G = [key_aligned(p, ks) for p in draw(gen, w, keys, random.Random(hash(w) & 0xffff), a.per)]
                G = np.stack([g for g in G if g is not None and np.isfinite(g).all()])
            mean = G.mean(0)
            rows["spread"].append(np.hypot(*(G - mean).transpose(2, 0, 1)).mean())
            dg = dist(G, G); rows["pairwise"].append(dg[np.triu_indices(len(G), 1)].mean())
            rows["precision"].append(inside(G, R, knn_radius(R)))
            rows["recall"].append(inside(R, G, knn_radius(G)))
            rows["copying"].append(np.median(dist(G, T).min(1)) / np.median(dist(R, T).min(1)) if len(T) else float("nan"))
            rows["mean gap"].append(float(np.hypot(*(G.mean(0) - R.mean(0)).T).mean()))
            rows["rough"].append(np.sqrt((np.diff(G, 2, axis=1) ** 2).sum(-1).mean()))
        out[name] = {k: float(np.mean(v)) for k, v in rows.items()}
    keys_ = ["spread", "pairwise", "precision", "recall", "copying", "mean gap", "rough"]
    print(f"  {'':22s}" + "".join(f"{k:>10s}" for k in keys_))
    for name, r in out.items():
        print(f"  {name:22s}" + "".join(f"{r[k]:10.3f}" for k in keys_))
    import os
    import sys
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    import track
    run = track.start("swipe", "en", "diversity", layout="qwerty", data="futo-real",
                      config={"kind": "diversity", "gens": a.gen, "words": words, "per": a.per})
    run.summary.update({f"{n}/{k}": v for n, r in out.items() for k, v in r.items()})
    run.finish()


if __name__ == "__main__":
    main()
