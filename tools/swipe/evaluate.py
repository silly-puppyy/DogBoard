#!/usr/bin/env python3
"""The standard swipe evaluation (tools/swipe/evaluate.py): every model scored the same way.

    evaluate.py --model best.pt --lexicon en.lexicon.tsv --prepared DIR [--hws hws.pt] [-n 0]

FUTO's paper setup (arXiv 2606.25247, Table 2): the 162,185 most frequent lexicon words plus the test
set's own words (at the rarest frequency, so every target is reachable), beam 100, no sentence
context; top-1/3/10 on all FUTO test swipes. How We Swipe (held out, other people and keyboard) the
same way with its own words added. -n limits each set (0 = all).
"""
import argparse
import os
import random
import sys
import time

import numpy as np
import torch

from decode import Trie, beam, load_lexicon
from model import ALPHABET, SwipeNet

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import track  # noqa: E402


def score(model, items, lex, label):
    have = {w for w, _ in lex}
    floor = min(lp for _, lp in lex)
    extra = sorted({w for _, w in items} - have)
    root = Trie.build(lex + [(w, floor) for w in extra])
    t1 = t3 = t10 = 0
    t0 = time.time()
    with torch.no_grad():
        for f, w in items:
            c = [x for x, _ in beam(model(f.unsqueeze(0))[0].numpy().astype(np.float64), root, ALPHABET, width=100, top=10)]
            t1 += c[:1] == [w]; t3 += w in c[:3]; t10 += w in c
    n = len(items)
    print(f"{label}: n {n} (+{len(extra)} words added), top-1 {100 * t1 / n:.2f}, top-3 {100 * t3 / n:.2f}, "
          f"top-10 {100 * t10 / n:.2f} ({time.time() - t0:.0f} s)", flush=True)
    return {"top1": 100 * t1 / n, "top3": 100 * t3 / n, "top10": 100 * t10 / n, "n": n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--prepared", required=True, help="folder with FUTO's test.pt (prepare_futo.py)")
    ap.add_argument("--hws", default="")
    ap.add_argument("-n", type=int, default=0)
    ap.add_argument("--lang", default="en")
    ap.add_argument("--layout", default="qwerty")
    ap.add_argument("--name", default="", help="the run to add results to (default: the model's folder name)")
    a = ap.parse_args()
    torch.set_num_threads(1)
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    lex = load_lexicon(a.lexicon, top=162185)
    results = {}
    for label, path in (("FUTO test", f"{a.prepared}/test.pt"), ("How We Swipe", a.hws)):
        if not path:
            continue
        items = torch.load(path)
        if a.n:
            random.Random(7).shuffle(items)
            items = items[:a.n]
        r = score(m, items, lex, label)
        key = "futo_test" if label.startswith("FUTO") else "hws"
        results.update({f"eval/{key}_{k}": v for k, v in r.items()})
    if not a.n:  # only full evaluations go to the run
        run = track.start("swipe", a.lang, a.name or os.path.basename(os.path.dirname(os.path.abspath(a.model))), layout=a.layout)
        run.log(results)
        for k, v in results.items():
            run.summary[k] = v
        run.finish()


if __name__ == "__main__":
    main()
