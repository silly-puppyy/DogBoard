"""A model of how people swipe (tools/swipe/human.py): human-like paths for any word, to benchmark swipe
decoders the way the tap benchmarks use a model of tap error ("make a human swipe
model to benchmark against").

Fitted to real swipes (FUTO dev split; key units: x = key widths across, y = rows down). Measured on
20,000 of them: path length 1.065x the key-centre path (p10 0.92 corner cutting, p90 1.27), mean
distance from it 0.18 key, start 0.25 and end 0.36 key from the first and last key centres (ends are
sloppier, starts a little low), about 73 ms per key width (p10 42, p90 152).

Fitted parameters (grid search on 1,500 dev words, log-error over median/p10/p90 of the five
measures): generated median/p10/p90 vs real: length 1.025/0.937/1.131 vs 1.065/0.921/1.270 (loops and
overshoots not modelled yet: its long tail is short), mean distance 0.158/0.081/0.354 vs 0.182/0.103/
0.332, start 0.252/0.089/0.578 vs 0.245/0.093/0.521, end 0.392/0.138/0.970 vs 0.358/0.127/0.850,
ms per key 75/40/134 vs 73/43/152.

h2 (2026-11-17 21:00, fit_human.py on 2,000 dev words, key by key): corner spread grows with the
turn angle, and swipes run on past the last key (median 0.2 key, curling). Error against real key-
level statistics 7.8 -> 3.5: length median/p90 1.057/1.246 vs real 1.066/1.246; sharp-corner miss
0.178/0.590 vs 0.172/0.559; last key 0.182/0.490 vs 0.192/0.480; run-on p90 0.90 vs 0.95. Still off:
sideways spread ~20% too wide, spread along straight runs too small (one sigma for both now).
h1 (the parameters above this note's first fit) is in git history (commit 30229cc).

A swipe: each key's target = its centre + a Gaussian offset (start, middle and end sizes differ); a
Catmull-Rom curve through the targets, pulled towards the chord between neighbours (corner cutting);
a slow sideways wobble; points every few ms along a bell-shaped speed profile.
"""
import math
import random
from dataclasses import dataclass


@dataclass
class HumanParams:
    sigma_start: float = 0.27   # key widths, target offset at the first key
    sigma_mid: float = 0.28     # at the keys in between
    sigma_end: float = 0.29     # at the last key
    start_dy: float = 0.06      # starts land a little low
    cut: float = 0.35           # corner cutting: 0 = through the targets, 1 = straight chords
    wobble: float = 0.12        # amplitude of the slow sideways drift (key widths)
    ms_per_key: float = 73.0    # median speed
    speed_spread: float = 0.45  # log-normal spread of the speed between people / swipes
    sample_ms: float = 8.0      # touch sampling interval
    sloppy_spread: float = 0.45  # people (and swipes) differ in care: a log-normal factor on offsets and wobble
    corner_along: float = 0.3   # extra spread along the travel direction at a key, for a 180-degree turn
    over_median: float = 0.2    # run-on past the last key: median length (key widths)
    over_spread: float = 1.0    # its log-normal spread
    over_curl: float = 0.9      # how much the run-on bends (radians, at most)
    along_ratio: float = 0.0    # > 0: target spread along the travel direction = this x the sideways one
                                # (sideways = the sigma); 0: the older x / y spread (y = 0.8 x)


# named versions, for comparisons (h2 = the defaults)
PRESETS = {
    "h1": HumanParams(sigma_start=0.24, sigma_mid=0.16, sigma_end=0.40, cut=0.20, wobble=0.12,
                      corner_along=0.0, over_median=0.0),
    "h2": HumanParams(),
}


def _catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return tuple(0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                 for a, b, c, d in zip(p0, p1, p2, p3))


def path(word: str, centres: dict, rng: random.Random, p: HumanParams = HumanParams()):
    """[(x, y, t_ms)] for `word` on a keyboard whose key centres (key units) are `centres`."""
    keys = [centres[c] for c in word]
    # repeated letters ("ll") are one target: the finger doesn't move
    keys = [k for i, k in enumerate(keys) if i == 0 or k != keys[i - 1]]
    n = len(keys)
    sl = math.exp(rng.gauss(0, p.sloppy_spread))
    targets = []
    for i, (x, y) in enumerate(keys):
        s = sl * (p.sigma_start if i == 0 else p.sigma_end if i == n - 1 else p.sigma_mid)
        dy = p.start_dy if i == 0 else 0.0
        if p.along_ratio > 0:
            # spread relative to the way the finger travels through the key
            a = keys[max(i - 1, 0)]; b = keys[min(i + 1, n - 1)]
            ux, uy = b[0] - a[0], b[1] - a[1]
            un = math.hypot(ux, uy) or 1.0
            ux, uy = ux / un, uy / un
            al, ac = rng.gauss(0, s * p.along_ratio), rng.gauss(0, s)
            tx, ty = x + al * ux - ac * uy, y + dy + al * uy + ac * ux
        else:
            tx, ty = x + rng.gauss(0, s), y + dy + rng.gauss(0, s * 0.8)
        if 0 < i < n - 1 and p.corner_along:
            # at a turn the finger overshoots or cuts short along its way in (more, the sharper)
            ax, ay = x - keys[i - 1][0], y - keys[i - 1][1]
            bx, by = keys[i + 1][0] - x, keys[i + 1][1] - y
            na, nb = math.hypot(ax, ay) or 1.0, math.hypot(bx, by) or 1.0
            cosang = max(-1.0, min(1.0, (ax * bx + ay * by) / (na * nb)))
            along = rng.gauss(0, sl * p.corner_along * math.acos(cosang) / math.pi)
            tx += along * ax / na; ty += along * ay / na
        targets.append((tx, ty))
    if n == 1:
        targets = [targets[0], (targets[0][0] + rng.gauss(0, 0.05), targets[0][1] + rng.gauss(0, 0.05))]
        n = 2
    # dense curve through the targets; corners cut towards the chord between neighbours
    pts = []
    ext = [targets[0]] + targets + [targets[-1]]
    for i in range(n - 1):
        a, b, c, d = ext[i], ext[i + 1], ext[i + 2], ext[i + 3]
        steps = max(4, int(math.dist(b, c) / 0.05))
        for k in range(steps):
            t = k / steps
            cx, cy = _catmull(a, b, c, d, t)
            lx, ly = b[0] + (c[0] - b[0]) * t, b[1] + (c[1] - b[1]) * t
            w = p.cut * math.sin(math.pi * t) if 0 < i < n - 2 or True else 0.0
            pts.append((cx * (1 - w) + lx * w, cy * (1 - w) + ly * w))
    pts.append(targets[-1])
    # run-on past the last key, bending a little
    if p.over_median > 0 and len(targets) >= 2:
        L = p.over_median * math.exp(rng.gauss(0, p.over_spread))
        dx, dy = targets[-1][0] - targets[-2][0], targets[-1][1] - targets[-2][1]
        heading = math.atan2(dy, dx)
        curl = rng.uniform(-p.over_curl, p.over_curl)
        steps = max(2, int(L / 0.05))
        x, y = targets[-1]
        for k in range(1, steps + 1):
            h = heading + curl * k / steps
            x += math.cos(h) * L / steps; y += math.sin(h) * L / steps
            pts.append((x, y))
    # slow wobble across the direction of travel
    phase, freq = rng.uniform(0, 2 * math.pi), rng.uniform(0.6, 1.4)
    total = sum(math.dist(u, v) for u, v in zip(pts, pts[1:])) or 1e-6
    out, acc = [], 0.0
    for i, (x, y) in enumerate(pts):
        if i:
            acc += math.dist(pts[i - 1], pts[i])
        j = min(i + 1, len(pts) - 1)
        dx, dy = pts[j][0] - pts[max(i - 1, 0)][0], pts[j][1] - pts[max(i - 1, 0)][1]
        norm = math.hypot(dx, dy) or 1.0
        off = sl * p.wobble * math.sin(phase + 2 * math.pi * freq * acc / max(total, 1.0)) * math.sin(math.pi * acc / total)
        out.append((x - dy / norm * off, y + dx / norm * off, acc / total))
    # timing: a bell-shaped speed profile (minimum-jerk position profile) over the whole swipe
    duration = max(60.0, total * p.ms_per_key * math.exp(rng.gauss(0, p.speed_spread)))
    samples = max(2, int(duration / p.sample_ms))
    res, j = [], 0
    for k in range(samples + 1):
        tau = k / samples
        # share of the distance covered: mostly steady with soft ends (a pure minimum-jerk profile
        # lingers at the ends long enough for a keyboard to read a long-press)
        s = 0.7 * tau + 0.3 * (10 * tau ** 3 - 15 * tau ** 4 + 6 * tau ** 5)
        while j < len(out) - 2 and out[j + 1][2] < s:
            j += 1
        a, b = out[j], out[j + 1]
        f = 0 if b[2] == a[2] else (s - a[2]) / (b[2] - a[2])
        res.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, tau * duration))
    # stay on the letter rows (y = rows 0..3 edges): a touch above them would land on the suggestion
    # bar, which measures the bar, not the recogniser (the tap benchmarks clamp the same way)
    return [(min(max(x, 0.02), 9.98), min(max(y, 0.05), 2.95), t) for x, y, t in res]
