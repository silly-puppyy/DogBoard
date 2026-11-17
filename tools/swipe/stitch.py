"""Human swipes stitched from real motion (tools/swipe/stitch.py): a human model for any layout and
language (user 2026-11-17: "a good enough human model to port to unseen keyboard layouts and
languages"). The parametric model (human.py) trained a decoder to ~80% top-1 on real swipes, level
with a geometric decoder, 11 points behind real data: averages don't carry people's hooks, loops,
double-letter wiggles and uneven curves. This keeps them.

Library (built from real swipes, FUTO train split): each swipe is aligned to its word's keys (in
order, least total distance); the path between two consecutive keys is one PIECE, stored in that
gap's own frame: u along the key-to-key line (0 at the first key, 1 at the second), v sideways in key
widths. Pieces are filed by gap length (key widths) and the turns the word makes at both ends. Also
kept: the lead-in before the first key and the run-on after the last, in the frame of the first / last
gap, and pieces for repeated letters (gap length 0: a frame from the travel direction).

A new swipe: for each gap of the new word's keys, a piece from the same bucket (nearest bucket if
empty), mapped into the new gap's frame (u scaled to the new length, v kept in key widths), joined
end to end (each piece shifted so it starts where the last ended, the shift spread over the piece).
Nothing depends on QWERTY: only key centres in key units.
"""
import math
import pickle
import random

LEN_BINS = (0.0, 0.6, 1.2, 2.0, 3.0, 4.5, 6.5, 99)
TURN_BINS = (0, 45, 110, 181)  # degrees: straight, bend, sharp; -1 = word end


def _bin(x, edges):
    for i in range(len(edges) - 1):
        if x < edges[i + 1]:
            return i
    return len(edges) - 2


def _turn(a, b, c):
    v1 = (b[0] - a[0], b[1] - a[1]); v2 = (c[0] - b[0], c[1] - b[1])
    n1, n2 = math.hypot(*v1), math.hypot(*v2)
    if n1 == 0 or n2 == 0:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))))


def _turn_bin(keys, i):
    """Turn at key i (between gap i-1 and gap i); -1 at the word's ends."""
    if i <= 0 or i >= len(keys) - 1:
        return -1
    return _bin(_turn(keys[i - 1], keys[i], keys[i + 1]), TURN_BINS)


def _frame(a, b, fallback):
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = math.hypot(dx, dy)
    if L < 1e-6:
        dx, dy = fallback
        n = math.hypot(dx, dy) or 1.0
        return (dx / n, dy / n), 0.0
    return (dx / L, dy / L), L


def align(keys, pts):
    """Path index for each key, in order, least total distance (as compare_paths.py)."""
    m = len(pts)
    cost = [math.dist(pts[q], keys[0]) for q in range(m)]
    back = []
    for k in keys[1:]:
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
    return picks[::-1]


def _to_frame(pts, origin, d, L):
    """Points into a gap's frame: u along (in units of the gap length, or key widths if L == 0), v sideways."""
    s = L if L > 0 else 1.0
    return [(((x - origin[0]) * d[0] + (y - origin[1]) * d[1]) / s, -(x - origin[0]) * d[1] + (y - origin[1]) * d[0]) for x, y in pts]


def _from_frame(uv, origin, d, L):
    s = L if L > 0 else 1.0
    return [(origin[0] + u * s * d[0] - v * d[1], origin[1] + u * s * d[1] + v * d[0]) for u, v in uv]


class Library:
    def __init__(self):
        self.pieces = {}   # (len bin, turn in, turn out) -> [uv list]
        self.leads = []    # lead-in before the first key, in the first gap's frame (key widths)
        self.runs = []     # run-on after the last key, in the last gap's frame (key widths)

    def add(self, word, path, centres):
        keys = [centres[c] for c in word]
        if len(keys) < 2 or len(path) < 4:
            return
        picks = align(keys, path)
        travel = (keys[-1][0] - keys[0][0], keys[-1][1] - keys[0][1])
        for i in range(len(keys) - 1):
            a, b = keys[i], keys[i + 1]
            d, L = _frame(a, b, travel if math.hypot(*travel) > 0 else (1.0, 0.0))
            seg = path[picks[i]:picks[i + 1] + 1]
            if len(seg) < 2:
                seg = [path[picks[i]], path[picks[i + 1]]]
            key = (_bin(L, LEN_BINS), _turn_bin(keys, i), _turn_bin(keys, i + 1))
            # the piece runs from the real path's point at key a to its point at key b; stored
            # relative to the keys, so the offsets at the keys come along
            self.pieces.setdefault(key, []).append(_to_frame(seg, a, d, L))
        d0, _ = _frame(keys[0], keys[1], (1.0, 0.0))
        self.leads.append(_to_frame(path[:picks[0] + 1], keys[0], d0, 0.0))
        d1, _ = _frame(keys[-2], keys[-1], (1.0, 0.0))
        self.runs.append(_to_frame(path[picks[-1]:], keys[-1], d1, 0.0))

    def save(self, f):
        pickle.dump((self.pieces, self.leads, self.runs), open(f, "wb"))

    @staticmethod
    def load(f):
        lib = Library()
        lib.pieces, lib.leads, lib.runs = pickle.load(open(f, "rb"))
        return lib

    def _pick(self, key, rng):
        if key in self.pieces:
            return rng.choice(self.pieces[key])
        # nearest bucket: same length bin first, then neighbours
        lb, ti, to = key
        for dl in (0, 1, -1, 2, -2, 3, -3):
            for k in ((lb + dl, ti, to), (lb + dl, -1 if ti != -1 else 0, to), (lb + dl, ti, -1 if to != -1 else 0)):
                if k in self.pieces:
                    return rng.choice(self.pieces[k])
        return rng.choice(rng.choice(list(self.pieces.values())))

    def path(self, word, centres, rng):
        """[(x, y)] for word on a keyboard with these key centres (key units)."""
        keys = [centres[c] for c in word]
        if len(keys) == 1:
            keys = keys * 2
        travel = (keys[-1][0] - keys[0][0], keys[-1][1] - keys[0][1])
        out = []
        d0, _ = _frame(keys[0], keys[1], (1.0, 0.0))
        out.extend(_from_frame(rng.choice(self.leads), keys[0], d0, 0.0)[:-1])
        for i in range(len(keys) - 1):
            a, b = keys[i], keys[i + 1]
            d, L = _frame(a, b, travel if math.hypot(*travel) > 0 else (1.0, 0.0))
            uv = self._pick((_bin(L, LEN_BINS), _turn_bin(keys, i), _turn_bin(keys, i + 1)), rng)
            seg = _from_frame(uv, a, d, L)
            if out:
                # start where the path is; spread the jump over the piece (its end stays its own)
                sx, sy = out[-1][0] - seg[0][0], out[-1][1] - seg[0][1]
                n = len(seg) - 1 or 1
                seg = [(x + sx * (1 - j / n), y + sy * (1 - j / n)) for j, (x, y) in enumerate(seg)]
                seg = seg[1:]
            out.extend(seg)
        d1, _ = _frame(keys[-2], keys[-1], (1.0, 0.0))
        run = _from_frame(rng.choice(self.runs), keys[-1], d1, 0.0)
        sx, sy = out[-1][0] - run[0][0], out[-1][1] - run[0][1]
        out.extend((x + sx, y + sy) for x, y in run[1:])
        return out


def build(jsonl, layout, n, out, mode="grid"):
    import json
    import units
    lib = Library()
    k = 0
    with open(jsonl) as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
            if not (w.isascii() and w.isalpha()) or len(w) < 2:
                continue
            sx, sy = units.scale(mode, layout, d.get("canvas_width"), d.get("canvas_height"))
            lib.add(w, units.points(d["data"], sx, sy), units.keys(layout, sx, sy))
            k += 1
            if k >= n:
                break
    lib.save(out)
    print(f"{k} swipes, {sum(len(v) for v in lib.pieces.values())} pieces in {len(lib.pieces)} buckets -> {out}")


if __name__ == "__main__":
    import argparse
    import json
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=100000)
    ap.add_argument("--units", default="grid", choices=("grid", "phys", "key"))
    a = ap.parse_args()
    lay = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    build(f"{a.futo}/train.jsonl", lay, a.n, a.out, a.units)
