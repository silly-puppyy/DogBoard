#!/usr/bin/env python3
"""How people vary when swiping the same word (tools/swipe/analyze_words.py): the facts a physical human
swipe model has to reproduce (user 2026-11-17: no stitching of real paths; use the data to see how
humans vary over hundreds of swipes of one word, then simulate).

    analyze_words.py --futo DIR [--min 200] [--words 40] [--out stats.json]

Physical units (units.py "phys": a tenth of the keyboard's width, both ways). For every word with at
least --min swipes in FUTO's train split:
  - each swipe resampled to 64 points by arc length; the word's mean path; spread around it (RMS)
  - PCA of the deviations from the mean path: how much of the variation is a plain shift, a stretch,
    and what's left (bends, corner cutting)
  - offsets at the keys (closest approach in order): their spread, and how offsets at different keys
    of one swipe correlate (one shared aim error, or independent per key?)
  - person vs swipe: offsets averaged per session (FUTO session = one person, one sitting) across all
    their words, against what's left per swipe
  - speed: duration against path length, and whether slower swipes cut fewer corners
"""
import argparse
import json
import math
import statistics as st
from collections import defaultdict

import numpy as np

import units
from stitch import align

N = 64


def resample(pts, n=N):
    p = np.asarray(pts, dtype=np.float64)
    seg = np.hypot(*np.diff(p, axis=0).T)
    cum = np.concatenate([[0.0], np.cumsum(seg)])
    if cum[-1] == 0:
        return np.repeat(p[:1], n, axis=0)
    t = np.linspace(0, cum[-1], n)
    return np.stack([np.interp(t, cum, p[:, 0]), np.interp(t, cum, p[:, 1])], 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--min", type=int, default=200)
    ap.add_argument("--words", type=int, default=40)
    ap.add_argument("--max-swipes", type=int, default=600000)
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    by_word = defaultdict(list)
    n = 0
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if not (w.isascii() and w.isalpha()) or len(w) < 3 or len(d["data"]) < 4:
                continue
            sx, sy = units.scale("phys", layout, d.get("canvas_width"), d.get("canvas_height"))
            by_word[w].append((d["session"], units.points(d["data"], sx, sy), units.keys(layout, sx, sy),
                               d["data"][-1]["t"] - d["data"][0]["t"]))
            n += 1
            if n >= a.max_swipes:
                break
    words = sorted((w for w, v in by_word.items() if len(v) >= a.min), key=lambda w: -len(by_word[w]))[:a.words]
    print(f"{n} swipes read; {len(words)} words with >= {a.min} swipes: {' '.join(words[:20])} ...")

    report = {}
    shift_share, stretch_share, rest_share, rms_all = [], [], [], []
    key_sd, key_corr_adjacent, key_corr_ends = [], [], []
    sess_offsets = defaultdict(list)   # session -> per-swipe mean key offset (x, y)
    swipe_offsets = []                 # (session, mean offset)
    speed_rows = []                    # (path length / ideal, duration per ideal length)
    for w in words:
        items = by_word[w]
        res = np.stack([resample(p) for _, p, _, _ in items])               # (S, 64, 2)
        mean = res.mean(0)
        dev = res - mean
        rms = float(np.sqrt((dev ** 2).sum(-1).mean()))
        rms_all.append(rms)
        # how much of the deviation is a shift (same at every point) or a stretch (scale about the centre)
        shift = dev.mean(1, keepdims=True)
        rest = dev - shift
        c = mean - mean.mean(0)
        scale = (rest * c).sum((1, 2)) / max((c * c).sum(), 1e-9)          # least-squares uniform stretch
        rest2 = rest - scale[:, None, None] * c
        tot = (dev ** 2).sum()
        shift_share.append(float((np.broadcast_to(shift, dev.shape) ** 2).sum() / tot))
        stretch_share.append(float(((scale[:, None, None] * c) ** 2).sum() / tot))
        rest_share.append(float((rest2 ** 2).sum() / tot))
        # offsets at the keys
        offs = []
        for sess, p, keys, dur in items:
            ks = [keys[ch] for ch in w]
            picks = align(ks, p)
            o = np.array([[p[q][0] - k[0], p[q][1] - k[1]] for q, k in zip(picks, ks)])
            offs.append(o)
            m = o.mean(0)
            swipe_offsets.append((sess, m))
            sess_offsets[sess].append(m)
            ideal = sum(math.dist(u, v) for u, v in zip(ks, ks[1:])) or 1e-6
            real = sum(math.dist(u, v) for u, v in zip(p, p[1:]))
            speed_rows.append((real / ideal, dur / ideal))
        offs = np.stack(offs)                                                # (S, K, 2)
        key_sd.append(float(offs.std(0).mean()))
        K = offs.shape[1]
        cx = [np.corrcoef(offs[:, i, 0], offs[:, i + 1, 0])[0, 1] for i in range(K - 1)]
        key_corr_adjacent.append(float(np.nanmean(cx)))
        key_corr_ends.append(float(np.corrcoef(offs[:, 0, 0], offs[:, -1, 0])[0, 1]))
        report[w] = dict(n=len(items), rms=rms, key_sd=key_sd[-1])

    # person vs swipe: variance of session means (sessions with >= 10 swipes) against the residual
    big = {s: np.array(v) for s, v in sess_offsets.items() if len(v) >= 10}
    between = np.var(np.stack([v.mean(0) for v in big.values()]), axis=0).sum()
    within = np.mean([np.var(v, axis=0).sum() for v in big.values()])
    lr = np.array(speed_rows)
    slow = lr[:, 1] > np.median(lr[:, 1])
    out = {
        "words": len(words), "swipes": int(sum(len(by_word[w]) for w in words)),
        "path_rms_median": float(np.median(rms_all)),
        "variance_share_shift": float(np.median(shift_share)),
        "variance_share_stretch": float(np.median(stretch_share)),
        "variance_share_rest": float(np.median(rest_share)),
        "key_offset_sd_median": float(np.median(key_sd)),
        "key_offset_corr_adjacent_x": float(np.nanmedian(key_corr_adjacent)),
        "key_offset_corr_first_last_x": float(np.nanmedian(key_corr_ends)),
        "person_share_of_mean_offset_variance": float(between / (between + within)),
        "sessions_used": len(big),
        "length_ratio_fast": float(np.median(lr[~slow, 0])), "length_ratio_slow": float(np.median(lr[slow, 0])),
        "ms_per_unit_median": float(np.median(lr[:, 1])),
    }
    for k, v in out.items():
        print(f"  {k:40s} {v:.3f}" if isinstance(v, float) else f"  {k:40s} {v}")
    if a.out:
        json.dump({"summary": out, "words": report}, open(a.out, "w"), indent=1)


if __name__ == "__main__":
    main()
