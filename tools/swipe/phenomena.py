#!/usr/bin/env python3
"""What real swipes do that the finger model doesn't: one number per phenomenon, real vs model (tools/swipe/phenomena.py).

    phenomena.py --futo DIR --params pursuit.json [-n 3000] [--json out.json]

User 2026-11-18: simulate a physical finger; "as you can see there are other things like wobble, drastic veering off
course, slowing down on corners and randomly (to remember the rest of the spelling), maybe hypothesize and extract
more of these". Each phenomenon is measured on real swipes (FUTO, ~60 Hz, grouped by session = one person) and on
the model's swipes for the same words, on one reference keyboard (physical units, h/w 0.42, key width 1).
Strokes are the key-to-key stretches of a swipe (stitch.align: the path index nearest each key in order).

  bow           long strokes (>= 3 keys): the largest sideways deviation from the straight chord, / stroke length
  bow sign      share of a person's long strokes bowing to the same side relative to their direction of travel
                (a thumb pivoting at one side bends strokes one way); per person, then averaged
  bow x dir     correlation of the bow (screen up/down) with the stroke going left vs right (a pivot, not a habit)
  wobble        sideways jitter left after removing a smooth curve (quadratic in progress), RMS, and its zero
                crossings per second (frequency)
  veer          share of strokes straying > 0.8 key widths off the chord
  corner slow   speed at a key where the path turns > 60 degrees, / the swipe's median speed
  pass slow     the same at a key passed nearly straight (< 30 degrees): slowing at keys at all, not corners
  hesitation    stops mid-stroke: speed < 25 % of the median for >= 50 ms, farther than 0.6 keys from both ends
                (recalling the spelling), per swipe
  start dwell   ms before the finger leaves 0.3 keys around the touch-down
  overshoot     at corners: how far the path runs on past the key along the incoming direction (keys)
  run-on        past the last key, along the last stroke's direction (keys)
  speed ~ length  correlation of a stroke's mean speed with its length (longer strokes faster: ballistic)
"""
import argparse
import json
import math
import random
from collections import defaultdict

import numpy as np

import units
from physics import FingerParams, path as fpath
from stitch import align


def smooth(a, k=3):
    if len(a) < k:
        return a
    ker = np.ones(k) / k
    return np.stack([np.convolve(a[:, i], ker, mode="same") for i in range(a.shape[1])], 1)


def measure(xy, t, keys_seq):
    """Phenomenon values for one swipe (lists, so they can be pooled), or None."""
    p = np.asarray(xy, float)
    t = np.asarray(t, float)
    if len(p) < 6 or t[-1] - t[0] <= 0 or not np.isfinite(p).all():
        return None  # an unstable simulated finger (prepare_synth drops these too)
    ks = [k for i, k in enumerate(keys_seq) if i == 0 or k != keys_seq[i - 1]]
    if len(ks) < 2:
        return None
    picks = align(ks, [tuple(q) for q in p])
    ps = smooth(p)
    dt = np.diff(t); dt[dt <= 0] = 1.0
    v = np.hypot(*np.diff(ps, axis=0).T) / dt * 1000.0  # keys / s, between samples
    vmed = float(np.median(v)) if len(v) else 0.0
    if vmed <= 0:
        return None
    out = defaultdict(list)
    # strokes
    for i in range(len(ks) - 1):
        a, b = picks[i], picks[i + 1]
        if b - a < 3:
            continue
        A, B = np.asarray(ks[i]), np.asarray(ks[i + 1])
        L = float(np.hypot(*(B - A)))
        if L < 1.0:
            continue
        d = (B - A) / L
        n = np.array([-d[1], d[0]])
        seg = p[a:b + 1]
        lat = (seg - A) @ n            # sideways, + to the left of travel
        u = np.clip((seg - A) @ d / L, 0, 1)
        out["veer"].append(float(np.abs(lat).max() > 0.8))
        mv = float(np.mean(v[a:b])) if b > a else vmed
        out["stroke_len"].append(L); out["stroke_speed"].append(mv / vmed)
        if L >= 3.0:
            j = int(np.argmax(np.abs(lat)))
            out["bow"].append(float(lat[j] / L))
            out["bow_side"].append(float(np.sign(lat[j])))
            out["bow_screen"].append(float(-lat[j] * d[0] if abs(d[0]) > 0.7 else 0.0))  # + = bulges upward for a rightward stroke
            out["dir_x"].append(float(np.sign(d[0])) if abs(d[0]) > 0.7 else 0.0)
        if len(seg) >= 6 and np.ptp(u) > 0.05:
            try:
                c = np.polyfit(u, lat, 2)
            except np.linalg.LinAlgError:
                continue
            res = lat - np.polyval(c, u)
            out["wobble"].append(float(np.sqrt(np.mean(res ** 2))))
            dur = (t[b] - t[a]) / 1000.0
            if dur > 0:
                out["wobble_hz"].append(float(np.sum(np.diff(np.sign(res)) != 0) / 2 / dur))
        # hesitation inside the stroke
        far = np.minimum(np.hypot(*(p[a:b] - A).T), np.hypot(*(p[a:b] - B).T)) > 0.6
        slow = (v[a:b] < 0.25 * vmed) & far
        run = 0.0; found = 0
        for k, s in enumerate(slow):
            run = run + dt[a + k] if s else 0.0
            if run >= 50.0:
                found = 1; break
        out["hesitation"].append(float(found))
    # keys passed: turn angle and speed there
    for i in range(1, len(ks) - 1):
        u1 = np.asarray(ks[i]) - np.asarray(ks[i - 1]); u2 = np.asarray(ks[i + 1]) - np.asarray(ks[i])
        n1, n2 = np.hypot(*u1), np.hypot(*u2)
        if n1 == 0 or n2 == 0:
            continue
        ang = math.degrees(math.acos(max(-1, min(1, float(u1 @ u2 / (n1 * n2))))))
        q = picks[i]
        lo, hi = max(0, q - 1), min(len(v), q + 2)
        sv = float(np.min(v[lo:hi])) / vmed if hi > lo else 1.0
        if ang > 60:
            out["corner_slow"].append(sv)
            # overshoot: run-on past the key along the incoming direction, before the turn
            d1 = u1 / n1
            after = p[q:min(len(p), q + 12)] - np.asarray(ks[i])
            out["overshoot"].append(float(max(0.0, (after @ d1).max())))
        elif ang < 30:
            out["pass_slow"].append(sv)
    # start dwell
    r0 = np.hypot(*(p - p[0]).T)
    leave = int(np.argmax(r0 > 0.3)) if (r0 > 0.3).any() else len(p) - 1
    out["start_dwell"].append(float(t[leave] - t[0]))
    # run-on past the last key
    dl = np.asarray(ks[-1]) - np.asarray(ks[-2]); dl = dl / (np.hypot(*dl) or 1)
    out["run_on"].append(float(max(0.0, ((p[picks[-1]:] - np.asarray(ks[-1])) @ dl).max())))
    out["duration_per_key"].append(float((t[-1] - t[0]) / len(ks)))
    return out


def summarize(pool, per_person):
    def m(k):
        return float(np.mean(pool[k])) if pool.get(k) else float("nan")
    s = {
        "bow |mean| (/len)": float(np.mean(np.abs(pool["bow"]))) if pool.get("bow") else float("nan"),
        "bow sign consistency": float(np.mean([max(np.mean(np.array(b) > 0), np.mean(np.array(b) < 0)) for b in per_person.values() if len(b) >= 8])) if per_person else float("nan"),
        "bow x direction corr": float(np.corrcoef(pool["bow_screen"], pool["dir_x"])[0, 1]) if len(pool.get("dir_x", [])) > 10 and np.std(pool["dir_x"]) > 0 else float("nan"),
        "wobble RMS (keys)": m("wobble"),
        "wobble Hz": m("wobble_hz"),
        "veer share": m("veer"),
        "corner slow (v/median)": m("corner_slow"),
        "pass slow (v/median)": m("pass_slow"),
        "hesitation share": m("hesitation"),
        "start dwell ms": m("start_dwell"),
        "corner overshoot (keys)": m("overshoot"),
        "run-on (keys)": m("run_on"),
        "ms per key": m("duration_per_key"),
        "speed~length corr": float(np.corrcoef(pool["stroke_len"], pool["stroke_speed"])[0, 1]) if len(pool.get("stroke_len", [])) > 10 else float("nan"),
    }
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--params", required=True)
    ap.add_argument("-n", type=int, default=3000)
    ap.add_argument("--json", default="")
    a = ap.parse_args()
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    sx, sy = units.scale("phys", layout, 1.0, 0.42)
    keys = units.keys(layout, sx, sy)
    params = FingerParams(**json.load(open(a.params)))
    real, sim = defaultdict(list), defaultdict(list)
    bows_real, bows_sim = defaultdict(list), defaultdict(list)
    rng = random.Random(5)
    n = 0
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}")
            if not (w.isalpha() and 3 <= len(w) <= 9 and all(c in keys for c in w)):
                continue
            ks = [keys[c] for c in w]
            mr = measure([(q["x"] * sx, q["y"] * sy) for q in d["data"]], [q["t"] for q in d["data"]], ks)
            if mr is None:
                continue
            for k, vals in mr.items():
                real[k] += vals
            bows_real[d["session"]] += mr.get("bow_side", [])
            # the model, same word; one simulated person per 80 swipes, as the real sessions
            who_key = n // 80
            sp = fpath(w, keys, random.Random(hash((who_key, n)) & 0xffffffff), params)
            ms = measure([(x, y) for x, y, _ in sp], [tt for _, _, tt in sp], ks)
            if ms is not None:
                for k, vals in ms.items():
                    sim[k] += vals
                bows_sim[who_key] += ms.get("bow_side", [])
            n += 1
            if n >= a.n:
                break
    R, S = summarize(real, bows_real), summarize(sim, bows_sim)
    print(f"{n} swipes ({len(bows_real)} people), model {a.params.split('/')[-1]}")
    print(f"  {'phenomenon':26s} {'real':>9s} {'model':>9s}")
    for k in R:
        print(f"  {k:26s} {R[k]:9.3f} {S[k]:9.3f}")
    if a.json:
        json.dump({"real": R, "model": S}, open(a.json, "w"), indent=1)


if __name__ == "__main__":
    main()
