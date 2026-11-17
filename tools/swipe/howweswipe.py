#!/usr/bin/env python3
"""How We Swipe (Leiva et al. 2021, MIT) -> a held-out test set for bench.py (tools/swipe/howweswipe.py).

    howweswipe.py --zip swipelogs.zip --layout qwerty.json --out hws.pt [-n 3000]

Other people, another phone and keyboard than FUTO's, so a test the model never trained near. Logs:
touch events in keyboard pixels (360 x 215). The key positions aren't given, so they are learned:
the mean start of swipes by first letter and the mean end by last letter, fitted (least squares,
x and y scaled separately) to FUTO's key units. Swipes marked as errors (is_err) are left out.
"""
import argparse
import io
import json
import random
import zipfile

import numpy as np
import torch

from prepare_futo import resample


def swipes(zf, names):
    for name in names:
        rows = io.TextIOWrapper(zf.open(name), encoding="utf-8").read().splitlines()[1:]
        cur, word, bad = [], None, False
        for r in rows:
            f = r.split()
            if len(f) < 12:
                continue
            ev, x, y, w, err = f[4], float(f[5]), float(f[6]), f[10], f[11] == "1"
            if ev == "touchstart":
                cur, word, bad = [(x, y)], w.lower(), err
            elif cur:
                cur.append((x, y))
                if ev == "touchend":
                    if not bad and word.isascii() and word.isalpha() and len(word) >= 2:
                        yield word, cur
                    cur = []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=3000)
    a = ap.parse_args()
    lay = json.load(open(a.layout))
    units = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
    zf = zipfile.ZipFile(a.zip)
    names = sorted(n for n in zf.namelist() if n.endswith(".log"))
    random.Random(5).shuffle(names)
    data = []
    for word, pts in swipes(zf, names):
        data.append((word, pts))
        if len(data) >= a.n * 4:
            break
    # learn the key positions: swipe starts by first letter, ends by last letter
    acc = {}
    for word, pts in data:
        for c, p in ((word[0], pts[0]), (word[-1], pts[-1])):
            acc.setdefault(c, []).append(p)
    letters = [c for c in units if len(acc.get(c, [])) >= 20]
    px = np.array([np.median(np.array(acc[c]), axis=0) for c in letters])
    ux = np.array([units[c] for c in letters])
    sx = np.polyfit(px[:, 0], ux[:, 0], 1)
    sy = np.polyfit(px[:, 1], ux[:, 1], 1)
    resid = np.hypot(np.polyval(sx, px[:, 0]) - ux[:, 0], np.polyval(sy, px[:, 1]) - ux[:, 1])
    print(f"keys learned from {len(letters)} letters: x = {sx[0]:.4f} px + {sx[1]:.3f}, y = {sy[0]:.4f} px + {sy[1]:.3f}; "
          f"median residual {np.median(resid):.2f} key", flush=True)
    random.Random(6).shuffle(data)
    out = []
    for word, pts in data[:a.n]:
        xy = resample([(np.polyval(sx, x), np.polyval(sy, y)) for x, y in pts], 0.3)
        f = [(x, y, (xy[i + 1][0] if i + 1 < len(xy) else x) - x, (xy[i + 1][1] if i + 1 < len(xy) else y) - y)
             for i, (x, y) in enumerate(xy)]
        out.append((torch.tensor(f, dtype=torch.float32), word))
    torch.save(out, a.out)
    print(f"{len(out)} swipes -> {a.out}")


if __name__ == "__main__":
    main()
