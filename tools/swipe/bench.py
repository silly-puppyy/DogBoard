#!/usr/bin/env python3
"""Swipe benchmark (tools/swipe/bench.py): word accuracy of SwipeNet + lexicon decoding.

    bench.py --model best.pt --lexicon en.lexicon.tsv --data prepared/test.pt [--human dev.jsonl] [-n 3000]

Sets: FUTO's held-out test swipes (real people), and, with --human, swipes the human model (human.py)
makes for the same kind of words. Reports greedy (network alone), top-1 and top-3 with the lexicon.
"""
import argparse
import json
import math
import random
import sys
import time

import numpy as np
import torch

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from decode import Trie, beam, load_lexicon  # noqa: E402
from human import path as human_path  # noqa: E402
from model import ALPHABET, SwipeNet, greedy  # noqa: E402
from prepare_futo import resample  # noqa: E402


def feats_from_xy(xy, spacing=0.3):
    pts = resample(xy, spacing)
    f = [(x, y, (pts[i + 1][0] if i + 1 < len(pts) else x) - x, (pts[i + 1][1] if i + 1 < len(pts) else y) - y)
         for i, (x, y) in enumerate(pts)]
    return torch.tensor(f, dtype=torch.float32)


def run(model, items, root, alpha, label):
    g = t1 = t3 = 0
    t0 = time.time()
    with torch.no_grad():
        for f, w in items:
            lp = model(f.unsqueeze(0))[0]
            g += greedy(lp) == w
            cands = beam(lp.numpy().astype(np.float64), root, ALPHABET, alpha=alpha)
            words = [c for c, _ in cands]
            t1 += bool(words) and words[0] == w
            t3 += w in words[:3]
    n = len(items)
    print(f"{label}: n {n}, greedy {g / n:.3f}, top-1 {t1 / n:.3f}, top-3 {t3 / n:.3f}, {1000 * (time.time() - t0) / n:.1f} ms/swipe", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--human", default="")
    ap.add_argument("--layout", default="")
    ap.add_argument("-n", type=int, default=3000)
    ap.add_argument("--alpha", type=float, default=1.0)
    a = ap.parse_args()
    torch.set_num_threads(2)
    model = SwipeNet()
    model.load_state_dict(torch.load(a.model, map_location="cpu"))
    model.eval()
    lex = load_lexicon(a.lexicon)
    root = Trie.build(lex)
    known = {w for w, _ in lex}
    rng = random.Random(7)
    test = [it for it in torch.load(a.data) if it[1] in known]
    rng.shuffle(test)
    run(model, test[:a.n], root, a.alpha, "FUTO test (real people)")
    if a.human and a.layout:
        lay = json.load(open(a.layout))
        centres = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
        words = []
        with open(a.human) as f:
            for line in f:
                w = json.loads(line)["word"].lower()
                if w.isascii() and w.isalpha() and w in known:
                    words.append(w)
                if len(words) >= a.n:
                    break
        items = [(feats_from_xy([(x, y) for x, y, _ in human_path(w, centres, rng)]), w) for w in words]
        run(model, items, root, a.alpha, "human model (same words as FUTO dev)")


if __name__ == "__main__":
    main()
