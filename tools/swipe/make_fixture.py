#!/usr/bin/env python3
"""Test fixture for the Kotlin swipe code (tools/swipe/make_fixture.py): a few real FUTO test swipes with
their raw points, the features prepare_futo makes, the model's log-probs and decode.py's top words over
the whole lexicon. SwipeParityTest checks SwipePath and SwipeBeam against it.

    make_fixture.py --futo DIR --model best.pt --lexicon en.lexicon.tsv --out swipe-fixture.json [-n 12]
"""
import argparse
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
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=12)
    a = ap.parse_args()
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    lex = load_lexicon(a.lexicon, top=10 ** 7)
    root = Trie.build(lex)
    known = {w for w, _ in lex}
    out = []
    with open(f"{a.futo}/test.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower()
            if not (w.isascii() and w.isalpha() and w in known and len(w) >= 3):
                continue
            feats = features(d["data"], 0.3)
            with torch.no_grad():
                lp = m(feats.unsqueeze(0))[0]
            top = [c for c, _ in beam(lp.numpy().astype(np.float64), root, ALPHABET, top=3)]
            out.append({
                "word": w,
                "xs": [round(p["x"] * 10, 5) for p in d["data"]],
                "ys": [round(p["y"] * 3, 5) for p in d["data"]],
                "features": [round(v, 5) for v in feats.flatten().tolist()],
                "logp": [round(v, 4) for v in lp.flatten().tolist()],
                "top": top,
            })
            if len(out) >= a.n:
                break
    json.dump(out, open(a.out, "w"))
    print(f"{len(out)} swipes; top-1 right {sum(o['top'][:1] == [o['word']] for o in out)}")


if __name__ == "__main__":
    main()
