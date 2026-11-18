#!/usr/bin/env python3
"""Describe every real swipe by the physical finger's numbers, then look at (and sample) that space (tools/swipe/fit_each.py).

    fit_each.py fit   --futo DIR --params stroke-v1.json --out each.jsonl [-n 2000] [--workers 2]
    fit_each.py stats --futo DIR --params stroke-v1.json --each each.jsonl [--png out.png]

User 2026-11-18: "try to create a model that can describe each curve then fit random curves to see the sample
space". physics.py mode "stroke" made deterministic (no motor noise; who["fixed"]) and fitted to one real swipe at a
time by CMA-ES. Per swipe:
  motion   finger stiffness (omega) and damping (zeta), Fitts timing (a, b), stroke overlap, start dwell, run-on
  aims     each key's own aim point (dx, dy, key widths)
  strokes  each key-to-key stroke's bow (share of its length, + = left of travel) and hesitation (where, how long)
Loss: key-aligned shape distance (mean_paths alignment, key widths) + 0.5 x distance at equal times (16 ms grid over
the real swipe's duration; holds the speed profile and the pauses). The result per swipe (numbers + what is left
over) is one JSON line, so the space can be studied, modelled and sampled; stats prints its distribution,
correlations, principal axes and the leftover by stretch.
"""
import argparse
import json
import math
import random
from multiprocessing import Pool

import numpy as np

import units
from mean_paths import key_aligned
from physics import FingerParams, _stroke

G = ["omega", "zeta", "fitts_a", "fitts_b", "overlap", "dwell", "follow"]


def route_keys(word, keys):
    ks = [keys[c] for c in word]
    return [k for i, k in enumerate(ks) if i == 0 or k != ks[i - 1]]


def decode(x, nk, base):
    """Fit space -> the finger's numbers (motion dict, aims offsets [nk, 2], bows [nk-1], hesitations [(u, ms)])."""
    m = dict(omega=math.exp(x[0]), zeta=float(np.clip(math.exp(x[1]), 0.1, 3.0)), fitts_a=math.exp(x[2]),
             fitts_b=math.exp(x[3]), overlap=float(1 / (1 + math.exp(-x[4])) * 0.9), dwell=math.exp(x[5]),
             follow=abs(x[6]))
    o = 7
    aims = np.asarray(x[o:o + 2 * nk]).reshape(nk, 2)
    o += 2 * nk
    bows = list(x[o:o + nk - 1])
    o += nk - 1
    hes = [(float(1 / (1 + math.exp(-x[o + 2 * i]))), float(150 * max(0.0, x[o + 2 * i + 1])))
           for i in range(nk - 1)]
    return m, aims, bows, hes


def simulate(x, ks, base):
    nk = len(ks)
    m, aims, bows, hes = decode(x, nk, base)
    p = base.__class__(**{**base.__dict__, **{k: m[k] for k in ("zeta", "fitts_a", "fitts_b", "overlap", "dwell")},
                          "noise": 0.0, "mode": "stroke"})
    who = {"speed": 1.0, "omega": m["omega"], "fixed": {
        "aims": [(k[0] + a[0], k[1] + a[1]) for k, a in zip(ks, aims)], "follow": m["follow"],
        "bows": bows + [0.0], "hes": hes + [(0.0, 0.0)]}}
    return _stroke("x" * nk, {}, random.Random(0), p, who)


def at_times(path, grid):
    t = np.asarray([q[2] for q in path]); xy = np.asarray([(q[0], q[1]) for q in path])
    return np.stack([np.interp(grid, t, xy[:, 0]), np.interp(grid, t, xy[:, 1])], 1)


def fit_one(job):
    import cma
    word, xy, ts, base_d, evals = job[:5]
    base = FingerParams(**base_d)
    ks = [tuple(k) for k in job[5]]
    real = np.asarray(xy)
    target = key_aligned([tuple(q) for q in xy], ks)
    if target is None or not np.isfinite(real).all():
        return None
    t = np.asarray(ts, float) - ts[0]
    grid = np.arange(0, t[-1] + 1e-6, 16.0)
    real_t = np.stack([np.interp(grid, t, real[:, 0]), np.interp(grid, t, real[:, 1])], 1)
    nk = len(ks)
    x0 = [math.log(base.omega), math.log(base.zeta), math.log(base.fitts_a), math.log(base.fitts_b),
          math.log(max(base.overlap, 0.02) / 0.9 / (1 - min(base.overlap, 0.88) / 0.9)), math.log(max(base.dwell, 5)), base.follow]
    x0 += [0.0] * (2 * nk) + [0.0] * (nk - 1) + [0.0, -0.3] * (nk - 1)

    def parts(x):
        sim = simulate(x, ks, base)
        a = key_aligned([(q[0], q[1]) for q in sim], ks)
        if a is None:
            return 9.0, 9.0
        shape = float(np.hypot(*(a - target).T).mean())
        timing = float(np.hypot(*(at_times(sim, grid) - real_t).T).mean())
        return shape, timing

    def loss(x):
        s, tm = parts(x)
        return s + 0.5 * tm
    es = cma.CMAEvolutionStrategy(x0, 0.3, {"maxfevals": evals * len(x0), "verbose": -9, "seed": 1})
    es.optimize(loss)
    x = list(es.result.xbest)
    s, tm = parts(x)
    m, aims, bows, hes = decode(x, nk, base)
    sim = simulate(x, ks, base)
    a = key_aligned([(q[0], q[1]) for q in sim], ks)
    lens = [float(math.hypot(ks[i + 1][0] - ks[i][0], ks[i + 1][1] - ks[i][1])) for i in range(nk - 1)]
    dirs = [float(math.atan2(ks[i + 1][1] - ks[i][1], ks[i + 1][0] - ks[i][0])) for i in range(nk - 1)]
    return dict(word=word, motion=m, aims=aims.tolist(), bows=[float(b) for b in bows], hes=hes, stroke_len=lens,
                stroke_dir=dirs, shape_err=s, time_err=tm, dur=float(t[-1]),
                left=np.hypot(*(a - target).T).tolist() if a is not None else None)


def cmd_fit(a, keys, sx, sy):
    base = json.load(open(a.params))
    base["mode"] = "stroke"
    jobs = []
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}")
            if not (w.isalpha() and 3 <= len(w) <= 8 and all(c in keys for c in w)):
                continue
            ks = route_keys(w, keys)
            if len(ks) < 3:
                continue
            jobs.append((w, [(q["x"] * sx, q["y"] * sy) for q in d["data"]], [q["t"] for q in d["data"]], base, a.evals,
                         ks, d["session"]))
            if len(jobs) >= a.n:
                break
    done = 0
    with open(a.out, "w") as out, Pool(a.workers) as pool:
        for job, r in zip(jobs, pool.imap(fit_one, jobs, chunksize=4)):
            if r is None:
                continue
            r["session"] = job[6]
            out.write(json.dumps(r) + "\n")
            done += 1
            if done % 100 == 0:
                out.flush()
                print(f"{done} fitted", flush=True)


class Sampler:
    """prepare_synth.py's --human each:EACH.jsonl[,PARAMS.json]: new swipes drawn from the fitted space (the sample
    space the user asked for). Per swipe, the motion numbers of one real fitted swipe (resampled whole, so any links
    between them stay); per key its aim offset from real ones at the same position (first / middle / last); per
    stroke a bend from real strokes of similar length (in key widths, the measured size does not scale with length),
    a hesitation from real strokes (17.5 % > 40 ms in the first fits); the motor noise of the fitted finger on top
    (fit_stroke.py's stroke-v1.json)."""

    def __init__(self, spec):
        each, _, params = spec.partition(",")
        rows = []
        for line in open(each):
            try:
                rows.append(json.loads(line.strip("\0\n")))
            except json.JSONDecodeError:
                pass
        self.rows = rows
        self.motion = [r["motion"] for r in rows]
        self.aims = {k: [] for k in ("first", "mid", "last")}
        self.bows = {k: [] for k in range(4)}
        self.hes = []
        for r in rows:
            q = r["aims"]
            self.aims["first"].append(q[0]); self.aims["last"].append(q[-1]); self.aims["mid"] += q[1:-1]
            for b, L, h in zip(r["bows"], r["stroke_len"], r["hes"]):
                self.bows[self._bin(L)].append(b * L)
                self.hes.append(h)
        self.base = FingerParams(**json.load(open(params))) if params else FingerParams()

    @staticmethod
    def _bin(L):
        return 0 if L < 1.5 else 1 if L < 3 else 2 if L < 5 else 3

    def path(self, word, centres, rng):
        ks = route_keys(word, centres)
        if len(ks) < 2:
            x, y = ks[0]
            return [(x, y, 0.0), (x + rng.gauss(0, 0.05), y + rng.gauss(0, 0.05), 24.0)]
        # one real fitted swipe gives the motion, the first and last aims and the pauses together (the fit trades
        # them against each other: drawn apart, the run-on came out twice the real one); middle aims are its own,
        # cycled; bends come from real strokes of a similar length (a bend belongs to the stroke, not the person)
        r = rng.choice(self.rows)
        m = r["motion"]
        n = len(ks)
        own = r["aims"]
        mids = own[1:-1] or [rng.choice(self.aims["mid"])]
        offs = [own[0]] + [mids[i % len(mids)] for i in range(n - 2)] + [own[-1]]
        aims = [(k[0] + o[0], k[1] + o[1]) for k, o in zip(ks, offs)]
        bows, hes = [], []
        for i in range(n - 1):
            L = math.hypot(aims[i + 1][0] - aims[i][0], aims[i + 1][1] - aims[i][1]) or 1.0
            bows.append(rng.choice(self.bows[self._bin(L)]) / L)
            hes.append(tuple(r["hes"][i % len(r["hes"])]))
        p = self.base.__class__(**{**self.base.__dict__, **{k: m[k] for k in ("zeta", "fitts_a", "fitts_b", "overlap", "dwell")},
                                   "mode": "stroke"})
        who = {"speed": 1.0, "omega": m["omega"], "fixed": {"aims": aims, "follow": m["follow"], "bows": bows + [0.0],
                                                           "hes": hes + [(0.0, 0.0)]}}
        return _stroke("x" * n, {}, rng, p, who)

    def paths(self, words, centres, rng):
        return [[(x, y) for x, y, _ in self.path(w, centres, rng)] for w in words]


def cmd_stats(a):
    rows = []
    for line in open(a.each):
        try:
            rows.append(json.loads(line.strip("\0\n")))
        except json.JSONDecodeError:
            pass  # a line cut by a crash (2026-11-18: the machine froze mid-run)
    import os
    import sys
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    import track
    run = track.start("swipe", "en", "each-" + os.path.basename(a.each).removesuffix(".jsonl"), layout="qwerty",
                      data="futo-real", config={"kind": "per-swipe-fit", "n": len(rows)})
    run.summary.update({"shape_err_mean": float(np.mean([r["shape_err"] for r in rows])),
                        "time_err_mean": float(np.mean([r["time_err"] for r in rows])),
                        **{f"motion_median/{k}": float(np.median([r["motion"][k] for r in rows])) for k in G}})
    run.finish()
    print(f"{len(rows)} swipes fitted; leftover shape {np.mean([r['shape_err'] for r in rows]):.3f} key widths "
          f"(median {np.median([r['shape_err'] for r in rows]):.3f}), at equal times {np.mean([r['time_err'] for r in rows]):.3f}")
    M = np.array([[r["motion"][k] for k in G] for r in rows])
    L = np.column_stack([np.log(M[:, :4]), M[:, 4], np.log(np.maximum(M[:, 5], 1)), M[:, 6]])
    print("\nmotion (median, 10-90 %):")
    for i, k in enumerate(G):
        print(f"  {k:9s} {np.median(M[:, i]):8.3f}  [{np.percentile(M[:, i], 10):8.3f} .. {np.percentile(M[:, i], 90):8.3f}]")
    C = np.corrcoef(L.T)
    print("\ncorrelations (log space) above 0.3:")
    for i in range(len(G)):
        for j in range(i + 1, len(G)):
            if abs(C[i, j]) > 0.3:
                print(f"  {G[i]} ~ {G[j]}: {C[i, j]:+.2f}")
    Z = (L - L.mean(0)) / (L.std(0) + 1e-9)
    ev, vec = np.linalg.eigh(np.cov(Z.T))
    order = np.argsort(ev)[::-1]
    print("\nprincipal axes (share of variance: main loadings):")
    for k in order[:4]:
        top = sorted(zip(G, vec[:, k]), key=lambda kv: -abs(kv[1]))[:3]
        print(f"  {ev[k] / ev.sum():.2f}: " + ", ".join(f"{n} {v:+.2f}" for n, v in top))
    bows = np.concatenate([r["bows"] for r in rows]); lens = np.concatenate([r["stroke_len"] for r in rows])
    dirs = np.concatenate([r["stroke_dir"] for r in rows])
    print(f"\nbows: sd {bows.std():.3f}; by stroke length " + ", ".join(
        f"{lo}-{hi} keys sd {bows[(lens >= lo) & (lens < hi)].std():.3f}" for lo, hi in ((0, 2), (2, 4), (4, 10)) if ((lens >= lo) & (lens < hi)).sum() > 20))
    right = np.cos(dirs) > 0.7; left = np.cos(dirs) < -0.7
    print(f"  mean bow going right {bows[right].mean():+.3f}, going left {bows[left].mean():+.3f} (+ = left of travel)")
    by_person = {}
    for r in rows:
        by_person.setdefault(r["session"], []).extend(r["bows"])
    pm = [np.mean(v) for v in by_person.values() if len(v) >= 10]
    print(f"  people's own mean bow: sd {np.std(pm):.3f} over {len(pm)} people (a stroke's own sd {bows.std():.3f})")
    hes = np.array([h for r in rows for h in r["hes"]])
    long = hes[:, 1] > 40
    print(f"\nhesitations > 40 ms: {long.mean() * 100:.1f} % of strokes; median {np.median(hes[long, 1]) if long.any() else 0:.0f} ms, "
          f"where (0 start .. 1 end) median {np.median(hes[long, 0]) if long.any() else 0:.2f}")
    aims = [np.asarray(r["aims"]) for r in rows]
    first = np.array([q[0] for q in aims]); mid = np.concatenate([q[1:-1] for q in aims]); last = np.array([q[-1] for q in aims])
    print("\naim offsets (mean dx, dy; sd dx, dy):")
    for n, q in (("first key", first), ("middle keys", mid), ("last key", last)):
        print(f"  {n:11s} {q[:, 0].mean():+.3f} {q[:, 1].mean():+.3f}  sd {q[:, 0].std():.3f} {q[:, 1].std():.3f}")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, axs = plt.subplots(2, 4, figsize=(16, 7))
        for ax, i in zip(axs.flat, range(len(G))):
            ax.hist(M[:, i], bins=50, color="C0"); ax.set_title(G[i])
        ax = axs.flat[7]
        ax.scatter(lens + np.random.uniform(-0.1, 0.1, len(lens)), bows, s=2, alpha=0.3); ax.set_title("bow vs stroke length")
        plt.tight_layout(); plt.savefig(a.png, dpi=70)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=("fit", "stats"))
    ap.add_argument("--futo", required=True)
    ap.add_argument("--params", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--each", default="")
    ap.add_argument("-n", type=int, default=2000)
    ap.add_argument("--evals", type=int, default=60, help="search steps per fitted number")
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--png", default="")
    a = ap.parse_args()
    if a.cmd == "stats":
        return cmd_stats(a)
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    sx, sy = units.scale("phys", layout, 1.0, 0.42)
    keys = units.keys(layout, sx, sy)
    cmd_fit(a, keys, sx, sy)


if __name__ == "__main__":
    main()
