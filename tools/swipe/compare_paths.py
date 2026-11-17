#!/usr/bin/env python3
"""Real swipes vs the human model, key by key (tools/swipe/compare_paths.py).

    compare_paths.py --futo DIR [-n 4000]

For each key of a word (repeats merged), the path's closest point to the key centre, assigned in
order (least total distance); its offset split into along / across the direction of travel, binned by key role (first,
middle, last) and the turn the word makes there (straight < 45 deg, bend 45-110, sharp > 110).
Also path length per key gap and how far the path runs past the last key (overshoot).
"""
import argparse
import json
import math
import random
import statistics as st
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from human import path as human_path  # noqa: E402


def keys_of(word, centres):
    ks = [centres[c] for c in word]
    return [k for i, k in enumerate(ks) if i == 0 or k != ks[i - 1]]


def turn(a, b, c):
    v1 = (b[0] - a[0], b[1] - a[1]); v2 = (c[0] - b[0], c[1] - b[1])
    n1, n2 = math.hypot(*v1), math.hypot(*v2)
    if n1 == 0 or n2 == 0:
        return 0.0
    cos = max(-1, min(1, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))
    return math.degrees(math.acos(cos))


def measure(word, pts, centres, out):
    ks = keys_of(word, centres)
    if len(ks) < 2 or len(pts) < 3:
        return
    # keys to path points in order, least total distance (dynamic programming)
    m = len(pts)
    cost = [math.dist(pts[q], ks[0]) for q in range(m)]
    back = []
    for k in ks[1:]:
        best_c, best_q, pref, prefq = [], [], 1e18, 0
        for q in range(m):
            if cost[q] < pref:
                pref, prefq = cost[q], q
            best_c.append(pref + math.dist(pts[q], k)); best_q.append(prefq)
        back.append(best_q); cost = best_c
    q = min(range(m), key=lambda x: cost[x])
    picks = [q]
    for bq in reversed(back):
        q = bq[q]; picks.append(q)
    picks.reverse()
    for i, k in enumerate(ks):
        bj = picks[i]; best = math.dist(pts[bj], k); j = bj
        prev = ks[i - 1] if i else None
        nxt = ks[i + 1] if i + 1 < len(ks) else None
        a, b = (prev or k), (nxt or k)
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy) or 1.0
        ox, oy = pts[bj][0] - k[0], pts[bj][1] - k[1]
        along, across = (ox * dx + oy * dy) / n, (-ox * dy + oy * dx) / n
        if i == 0:
            role = "first"
        elif i == len(ks) - 1:
            role = "last"
        else:
            t = turn(prev, k, nxt)
            role = "mid straight" if t < 45 else "mid bend" if t < 110 else "mid sharp"
        out.setdefault(role, []).append((best, along, across))
    ideal = sum(math.dist(u, v) for u, v in zip(ks, ks[1:]))
    real = sum(math.dist(u, v) for u, v in zip(pts, pts[1:]))
    out.setdefault("_len", []).append(real / ideal)
    # overshoot: how far the path goes beyond its closest point to the last key
    out.setdefault("_over", []).append(sum(math.dist(u, v) for u, v in zip(pts[j:], pts[j + 1:])))


def stats(r):
    """Numbers to fit: miss median/p90 and along/across sd per role; length ratio and overshoot."""
    def q(v, p):
        v = sorted(v); return v[int(p * (len(v) - 1))]
    out = {}
    for role in ("first", "mid straight", "mid bend", "mid sharp", "last"):
        v = r.get(role, [])
        if v:
            d = [x[0] for x in v]
            out[role + " miss50"] = q(d, .5); out[role + " miss90"] = q(d, .9)
            out[role + " along"] = st.pstdev([x[1] for x in v]); out[role + " across"] = st.pstdev([x[2] for x in v])
    for k, name in (("_len", "len"), ("_over", "over")):
        out[name + "50"] = q(r[k], .5); out[name + "90"] = q(r[k], .9)
    out["len10"] = q(r["_len"], .1)
    return out


def summary(name, r):
    def q(v, p):
        v = sorted(v); return v[int(p * (len(v) - 1))]
    print(f"  {name}")
    for role in ("first", "mid straight", "mid bend", "mid sharp", "last"):
        v = r.get(role, [])
        if not v:
            continue
        d = [x[0] for x in v]; al = [x[1] for x in v]; ac = [x[2] for x in v]
        print(f"    {role:13s} n {len(v):5d}  miss median {q(d, .5):.3f} p90 {q(d, .9):.3f}  along mean {st.mean(al):+.3f} sd {st.pstdev(al):.3f}  across sd {st.pstdev(ac):.3f}")
    print(f"    length ratio median {q(r['_len'], .5):.3f} p10 {q(r['_len'], .1):.3f} p90 {q(r['_len'], .9):.3f}   "
          f"overshoot median {q(r['_over'], .5):.3f} p90 {q(r['_over'], .9):.3f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("-n", type=int, default=4000)
    a = ap.parse_args()
    lay = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    centres = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
    real, synth = {}, {}
    rng = random.Random(3)
    n = 0
    with open(f"{a.futo}/dev.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower()
            if not (w.isascii() and w.isalpha()):
                continue
            measure(w, [(p["x"] * 10, p["y"] * 3) for p in d["data"]], centres, real)
            measure(w, [(x, y) for x, y, _ in human_path(w, centres, rng)], centres, synth)
            n += 1
            if n >= a.n:
                break
    summary("real (FUTO dev)", real)
    summary("human model", synth)


if __name__ == "__main__":
    main()
