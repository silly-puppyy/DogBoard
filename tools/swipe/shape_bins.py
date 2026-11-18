#!/usr/bin/env python3
"""Top-1 on real swipes grouped by keyboard shape (tools/swipe/shape_bins.py): FUTO's main test split and
its 5th collection (QWERTY, English), by the canvas's height / width.

    shape_bins.py --futo DIR --model best.pt --lexicon en.tsv
"""
import argparse
import collections
import json

import numpy as np
import torch

from decode import Trie, beam, load_lexicon
from model import ALPHABET, SwipeNet
from prepare_futo import features


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--lexicon", required=True)
    ap.add_argument("--per", type=int, default=1500)
    a = ap.parse_args()
    torch.set_num_threads(1)
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    lex = load_lexicon(a.lexicon, top=160000)
    floor = min(lp for _, lp in lex)
    groups = collections.defaultdict(list)
    for path, src in ((f"{a.futo}/test.jsonl", "main"), (f"{a.futo}/swipe-5/swipe5.jsonl", "5")):
        with open(path) as f:
            for line in f:
                d = json.loads(line)
                if src == "5" and (d.get("layout") != "qwerty" or d.get("language") != "en" or not isinstance(d.get("data"), list)):
                    continue
                w = d["word"].lower().strip(".,;:!?\"()[]{}").replace("'", "")
                if not (w.isascii() and w.isalpha()) or not d["data"]:
                    continue
                r = d["canvas_height"] / d["canvas_width"]
                b = "0.38-0.44" if r < 0.44 else "0.44-0.55" if r < 0.55 else "0.55-0.70" if r < 0.70 else "0.70+"
                if len(groups[(src, b)]) < a.per:
                    groups[(src, b)].append((features(d["data"], 0.3), w))
    for key in sorted(groups):
        items = groups[key]
        if len(items) < 200:
            continue
        root = Trie.build(lex + [(w, floor) for w in sorted({w for _, w in items} - {w for w, _ in lex})])
        ok = 0
        with torch.no_grad():
            for f, w in items:
                c = beam(m(f.unsqueeze(0))[0].numpy().astype(np.float64), root, ALPHABET, width=100, top=1)
                ok += bool(c) and c[0][0] == w
        print(f"collection {key[0]:4s} keyboard h/w {key[1]:10s} n {len(items):5d} top-1 {100 * ok / len(items):.1f}", flush=True)


if __name__ == "__main__":
    main()
