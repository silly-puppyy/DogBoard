#!/usr/bin/env python3
"""Touch-noise statistics from the Invisible Mobile Keyboard data (Yoo et al., IJCAI 2021; MIT): scatter around
each typist's own key centres, sentence drift, key-size scaling, speed, movement, undershoot, tails.

    python3 -I tools/data/imk_touch_stats.py IMK_data.csv

Results: notes/research/frequency-data-plan.md "IMK analysis"."""
import csv, sys, math, collections
import numpy as np
csv.field_size_limit(10**8)
rows = list(csv.DictReader(open(sys.argv[1], encoding='utf-8')))
ok = 0; data = []  # (user, device, w, h, sentence, xs, ys, time)
for r in rows:
    xs = [float(v) for v in r['x_list'].split(',') if v]; ys = [float(v) for v in r['y_list'].split(',') if v]
    s = r['sentence']
    if len(xs) != len(s) or len(ys) != len(s): continue
    ok += 1; data.append((r['name'], r['device'], float(r['width']), float(r['height']), s, np.array(xs), np.array(ys), float(r['time'])))
print(f"{len(rows)} sentences, {ok} with one touch per character; users {len({d[0] for d in data})}; devices {collections.Counter(d[1] for d in data).most_common(5)}")
print("screen sizes:", collections.Counter((d[2], d[3]) for d in data).most_common(4))
# units: CSS px ~ 1/160 in on phones → 6.3 px per mm
PPMM = 6.3
def rsd(a):
    a = np.asarray(a); a = a[~np.isnan(a)]
    return 1.4826 * np.median(np.abs(a - np.median(a)))
# per user: centroid per letter
by_user = collections.defaultdict(list)
for d in data: by_user[d[0]].append(d)
res = []  # per tap: user, char, dx, dy, speed(cps), move distance(prev touch), prev char, phrase id, index
cent = {}
for u, ds in by_user.items():
    acc = collections.defaultdict(list)
    for d in ds:
        for c, x, y in zip(d[4], d[5], d[6]):
            if c.isalpha(): acc[c.lower()].append((x, y))
    cent[u] = {c: np.mean(v, axis=0) for c, v in acc.items() if len(v) >= 8}
for pid, d in enumerate(data):
    u, s, xs, ys, t = d[0], d[4], d[5], d[6], d[7]
    cps = len(s) / (t / 1000.0) if t > 0 else float('nan')  # time is in ms
    C = cent[u]
    for i, c in enumerate(s):
        c = c.lower()
        if c not in C: continue
        dx, dy = xs[i] - C[c][0], ys[i] - C[c][1]
        mv = math.hypot(xs[i] - xs[i-1], ys[i] - ys[i-1]) if i > 0 else float('nan')
        pc = s[i-1].lower() if i > 0 else None
        res.append((u, c, dx, dy, cps, mv, pc, pid, i))
dx = np.array([r[2] for r in res]); dy = np.array([r[3] for r in res])
print(f"\nA. scatter around each typist's own key centres ({len(res)} letter taps): SD x {rsd(dx)/PPMM:.2f} mm, y {rsd(dy)/PPMM:.2f} mm (median abs {np.median(abs(dx))/PPMM:.2f} / {np.median(abs(dy))/PPMM:.2f} mm)")
# per-phrase drift: mean residual per phrase
ph = collections.defaultdict(list)
for r in res: ph[r[7]].append((r[2], r[3]))
drift = np.array([np.mean(v, axis=0) for v in ph.values() if len(v) >= 10])
print(f"B. drift of the imagined keyboard per sentence: SD of sentence offsets x {rsd(drift[:,0])/PPMM:.2f} mm, y {rsd(drift[:,1])/PPMM:.2f} mm")
off = {k: np.mean(v, axis=0) for k, v in ph.items()}
jx = np.array([r[2] - off[r[7]][0] for r in res]); jy = np.array([r[3] - off[r[7]][1] for r in res])
print(f"   jitter after removing each sentence's offset: SD x {rsd(jx)/PPMM:.2f} mm, y {rsd(jy)/PPMM:.2f} mm")
# C. absolute vs relative: per user, imagined key pitch (q..p spread / 9) vs jitter SD
pts = []
for u, C in cent.items():
    if 'q' in C and 'p' in C:
        pitch = (C['p'][0] - C['q'][0]) / 9.0
        sel = [k for k, r in enumerate(res) if r[0] == u]
        if len(sel) > 200 and pitch > 0:
            pts.append((pitch, rsd(jx[sel]), rsd(jy[sel])))
pts = np.array(pts)
A = np.vstack([np.ones(len(pts)), pts[:, 0] ** 2]).T
for name, col in (("x", 1), ("y", 2)):
    coef, *_ = np.linalg.lstsq(A, pts[:, col] ** 2, rcond=None)
    sa = math.sqrt(max(coef[0], 0)) / PPMM; k = math.sqrt(max(coef[1], 0))
    r = np.corrcoef(pts[:, 0], pts[:, col])[0, 1]
    print(f"C. {name}: users {len(pts)}, imagined key pitch {np.median(pts[:,0])/PPMM:.1f} mm (range {pts[:,0].min()/PPMM:.1f}-{pts[:,0].max()/PPMM:.1f}); fit σ² = σa² + (k·pitch)²: σa {sa:.2f} mm, k {k:.3f}; corr(pitch, σ) {r:.2f}")
# D. speed: within user, jitter vs sentence speed (terciles)
cps = np.array([r[4] for r in res])
print("D. typing speed across sentences: chars/s median %.2f, 10-90%% %.2f-%.2f" % (np.nanmedian(cps), np.nanpercentile(cps, 10), np.nanpercentile(cps, 90)))
uspeed = {u: np.nanmedian([len(d[4]) / (d[7] / 1000.0) for d in ds if d[7] > 0]) for u, ds in by_user.items()}
v = np.array(list(uspeed.values())); print("   per typist median chars/s: median %.2f, range %.2f-%.2f" % (np.median(v), v.min(), v.max()))
rel = []  # within user: speed relative to the user's median, jitter
for k, r in enumerate(res):
    if not math.isnan(r[4]): rel.append((r[4] / uspeed[r[0]], jx[k], jy[k]))
rel = np.array(rel)
for lo, hi in ((0, 0.85), (0.85, 1.15), (1.15, 9)):
    m = (rel[:, 0] >= lo) & (rel[:, 0] < hi)
    print(f"   sentences at {lo:.2f}-{hi:.2f}× the typist's usual speed: n {m.sum()}, jitter SD x {rsd(rel[m,1])/PPMM:.2f} y {rsd(rel[m,2])/PPMM:.2f} mm")
# E. movement distance from the previous tap
mv = np.array([r[5] for r in res]); m = ~np.isnan(mv)
qs = np.nanpercentile(mv, [0, 25, 50, 75, 100])
for a, b in zip(qs[:-1], qs[1:]):
    s = m & (mv >= a) & (mv <= b)
    print(f"E. moved {a/PPMM:5.1f}-{b/PPMM:5.1f} mm from the previous tap: jitter SD x {rsd(jx[s])/PPMM:.2f} y {rsd(jy[s])/PPMM:.2f} mm")
# F. undershoot: residual along the move direction from the previous key's centroid
along = []
for k, r in enumerate(res):
    u, c, pc = r[0], r[1], r[6]
    if pc and pc in cent[u] and pc != c:
        v = cent[u][c] - cent[u][pc]; n = np.linalg.norm(v)
        if n > 5: along.append((jx[k] * v[0] + jy[k] * v[1]) / n)
along = np.array(along)
print(f"F. along the move from the previous key: mean {along.mean()/PPMM:+.2f} mm, median {np.median(along)/PPMM:+.2f} mm (negative = falls short), SD {rsd(along)/PPMM:.2f} mm, n {len(along)}")
# G. tails: share of taps far outside the core scatter (wrong keys, big slips)
r = np.hypot(jx / rsd(jx), jy / rsd(jy))
for k in (2, 3, 5):
    print(f"G. taps beyond {k} robust SDs: {np.mean(r > k):.1%} (a 2-D Gaussian would give {math.exp(-k*k/2):.1%})")
