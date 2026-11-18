#!/usr/bin/env python3
"""A per-layout swipe model on that layout's real swipes (tools/swipe/eval_layout_model.py).

    eval_layout_model.py --model best.pt --layout clearflow.json --test clearflow-en.pt --lexicon en.tsv [-n 0] [--name RUN]

The model's letters are the layout's (sorted); the test set comes from prepare_layouts.py (same units
as the model was trained in); decoding as evaluate.py (160k most frequent words of the language plus
the test words, beam 100, no context). Results go to the model's wandb run.
"""
import argparse
import json
import os
import random
import sys

import numpy as np
import torch

from decode import Trie, beam, load_lexicon
from model import SwipeNet

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import track  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--test", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("-n", type=int, default=0)
    ap.add_argument("--lang", default="en")
    ap.add_argument("--name", default="")
    ap.add_argument("--misses", default="", help="write each missed word (truth, top-1) to this file")
    a = ap.parse_args()
    torch.set_num_threads(1)
    lay = json.load(open(a.layout))
    letters = "".join(sorted(k["letter"] for k in lay["keys"]))
    m = SwipeNet(letters=len(letters))
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    lex = load_lexicon(a.lexicon, top=160000, letters=None if letters == "abcdefghijklmnopqrstuvwxyz" else letters)
    items = torch.load(a.test)["items"]
    if a.n:
        random.Random(7).shuffle(items)
        items = items[:a.n]
    floor = min(lp for _, lp in lex)
    have = {w for w, _ in lex}
    root = Trie.build(lex + [(w, floor) for w in sorted({w for _, w in items} - have)])
    t1 = t3 = t10 = 0
    misses = []
    by_len = {}  # word length bucket -> [right, total] (2026-11-18: German fell behind; long compounds?)
    with torch.no_grad():
        for f, w in items:
            c = [x for x, _ in beam(m(f.unsqueeze(0))[0].numpy().astype(np.float64), root, letters, width=100, top=10)]
            t1 += c[:1] == [w]; t3 += w in c[:3]; t10 += w in c
            b = by_len.setdefault(min(len(w), 13) // 3 * 3, [0, 0])
            b[0] += c[:1] == [w]; b[1] += 1
            if a.misses and c[:1] != [w]:
                misses.append(f"{w}\t{c[0] if c else ''}\t{w in c}")
    if a.misses:
        open(a.misses, "w").write("\n".join(misses) + "\n")
    n = len(items)
    r = {"top1": 100 * t1 / n, "top3": 100 * t3 / n, "top10": 100 * t10 / n, "n": n}
    tag = f"{lay['name'] if 'name' in lay else os.path.basename(a.layout)[:-5]}-real"
    print(f"{tag}: n {n}, top-1 {r['top1']:.2f}, top-3 {r['top3']:.2f}, top-10 {r['top10']:.2f}", flush=True)
    print("  top-1 by word length: " + ", ".join(f"{k}-{k + 2 if k < 12 else '+'}: {100 * v[0] / v[1]:.1f} (n {v[1]})"
                                                 for k, v in sorted(by_len.items())), flush=True)
    for k, v in by_len.items():
        r[f"top1_len{k}"] = 100 * v[0] / v[1]
    run = track.start("swipe", a.lang, a.name or os.path.basename(os.path.dirname(os.path.abspath(a.model))),
                      layout=os.path.basename(a.layout)[:-5])
    for k, v in r.items():
        run.summary[f"eval/{tag}_{k}"] = v
    run.log({f"eval/{tag}_{k}": v for k, v in r.items()})
    run.finish()


if __name__ == "__main__":
    main()
