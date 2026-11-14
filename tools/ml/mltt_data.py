#!/usr/bin/env python3
"""Training data for DogBoard's neural tap-typing model (our MLTT; notes/research/neural-autocorrect-plan.md).

    tools/ml/mltt_data.py UNIGRAMS LEXICON OUT.npz [--samples 2000000] [--seed 1]

Each sample is a word typed with simulated finger error, read as the nearest key per tap (the
net's input, as iOS feeds its MLTT), with the word itself as the target:
- words: lexicon words (a-z and apostrophe) drawn by FineWeb frequency ** 0.5 (flattened, so
  rarer words are seen too);
- typing speed: a time between taps drawn per word (0.12-0.5 s); faster widens the spread as
  GaussianTouch does (2.2 → 3.3 mm across, 3.2 → 4.5 mm down from slow to fast, times a per-word
  hand factor 0.2-0.8: real taps scatter less than the likelihood spread) and makes slips likelier;
- velocity: a fast tap falls a little short of its key along the move from the previous key
  (undershoot drawn per word, 0-20 % of that move, more when faster; OUR modelling assumption,
  not measured);
- keys and the nearest-key choice mirror QwertyGeometry (375 x 216 dp) and GaussianTouch
  (6.3 dp/mm, typing interval 0.3 s);
- typing slips the beam search models too: an extra tap on a neighbouring key, a missed letter,
  two letters swapped (3 / 2 / 2 % per word when slow, up to twice that when fast).
An apostrophe in a word has no tap (typed from the symbol keys): it is left out of the input and
kept in the target. Symbols: input 1-26 = a-z (0 = padding); target 1-26 = a-z, 27 = apostrophe
(0 = CTC blank).
"""
import argparse
import math
import random
import sys

import numpy as np

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_START = [0.0, 0.5, 1.28 + 0.2133]
W, H = 375.0, 216.0
PPMM = 6.3


def keys():
    unit, rh = W / 10, H / 4
    out = {}
    for r, row in enumerate(ROWS):
        for i, c in enumerate(row):
            out[c] = ((ROW_START[r] + i + 0.5) * unit, (r + 0.5) * rh)
    return out


KEYS = keys()
LETTERS = sorted(KEYS)
CX = np.array([KEYS[c][0] for c in LETTERS])
CY = np.array([KEYS[c][1] for c in LETTERS])


def touch_params():
    # GaussianTouch.params(medianKeyHalfWidth = 18.75, typingInterval = 0.3)
    s = min(max(18.75 / 15.0, 1.0), 4.0)
    p = s ** 0.66
    f = min(1.0, (0.35 - 0.3) / 0.2)
    sx = p * 13.86 + (p * 20.79 - p * 13.86) * f
    sy = p * 20.16 + (p * 28.35 - p * 20.16) * f
    return -1.0 / (4 * sx * sx), -1.0 / (4 * sy * sy)


CXC, CYC = touch_params()


def nearest(x, y):
    """The key with the highest likelihood (GaussianTouch.logLikelihood, no plateau at this size)."""
    ll = CXC * (x - CX) ** 2 + CYC * (y - CY) ** 2
    return LETTERS[int(np.argmax(ll))]


def neighbours(c):
    x, y = KEYS[c]
    d = [(math.hypot(KEYS[k][0] - x, (KEYS[k][1] - y) * 0.8), k) for k in LETTERS if k != c]
    return [k for _, k in sorted(d)[:6]]


NEIGH = {c: neighbours(c) for c in LETTERS}


def type_word(word, rng):
    letters = [c for c in word if c in KEYS]
    if not letters:
        return None
    interval = rng.uniform(0.12, 0.5)
    fast = min(1.0, max(0.0, (0.35 - interval) / 0.2))  # GaussianTouch's speed factor
    hand = rng.uniform(0.2, 0.8)
    sx = (2.2 + 1.1 * fast) * hand * PPMM
    sy = (3.2 + 1.3 * fast) * hand * PPMM
    undershoot = rng.uniform(0.0, 0.1) + 0.1 * fast
    slip = 1.0 + fast
    seq = list(letters)
    r = rng.random()
    if r < 0.03 * slip:  # an extra tap next to a letter
        i = rng.randrange(len(seq))
        seq.insert(i + rng.randint(0, 1), rng.choice(NEIGH[seq[i]]))
    elif r < 0.05 * slip and len(seq) > 2:  # a letter missed
        del seq[rng.randrange(len(seq))]
    elif r < 0.07 * slip and len(seq) > 2:  # two letters swapped
        i = rng.randrange(len(seq) - 1)
        seq[i], seq[i + 1] = seq[i + 1], seq[i]
    out = []
    prev = None
    for c in seq:
        x, y = KEYS[c]
        if prev is not None:  # falls short along the move from the previous key
            x -= undershoot * (x - prev[0])
            y -= undershoot * (y - prev[1])
        out.append(nearest(x + rng.gauss(0, sx), y + rng.gauss(0, sy)))
        prev = KEYS[c]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("unigrams")
    ap.add_argument("lexicon")
    ap.add_argument("out")
    ap.add_argument("--samples", type=int, default=2_000_000)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--max-len", type=int, default=20)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    lex = set()
    with open(args.lexicon, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                w = line.split("\t")[0].lower().replace("’", "'")
                if all(c in KEYS or c == "'" for c in w) and 2 <= len(w) <= args.max_len:
                    lex.add(w)
    counts = {}
    with open(args.unigrams, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, c = line.rstrip("\n").split("\t")
            w = w.lower()
            if w in lex:
                counts[w] = counts.get(w, 0) + int(c)
    words = sorted(counts)
    weights = np.array([counts[w] for w in words], dtype=np.float64) ** 0.5
    weights /= weights.sum()
    print(f"{len(words)} words with counts", file=sys.stderr)

    picks = np.random.default_rng(args.seed).choice(len(words), size=args.samples, p=weights)
    L = args.max_len + 2
    x = np.zeros((args.samples, L), dtype=np.int8)
    xlen = np.zeros(args.samples, dtype=np.int8)
    y = np.zeros((args.samples, args.max_len), dtype=np.int8)
    ylen = np.zeros(args.samples, dtype=np.int8)
    n = 0
    for k in picks:
        w = words[k]
        taps = type_word(w, rng)
        if not taps or len(taps) > L:
            continue
        x[n, : len(taps)] = [ord(c) - 96 for c in taps]
        xlen[n] = len(taps)
        t = [27 if c == "'" else ord(c) - 96 for c in w]
        y[n, : len(t)] = t
        ylen[n] = len(t)
        n += 1
        if n % 200_000 == 0:
            print(f"{n} samples", file=sys.stderr)
    np.savez_compressed(args.out, x=x[:n], xlen=xlen[:n], y=y[:n], ylen=ylen[:n])
    print(f"{n} samples -> {args.out}", file=sys.stderr)


if __name__ == "__main__":
    main()
