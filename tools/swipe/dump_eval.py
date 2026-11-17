#!/usr/bin/env python3
"""Swipes with their sentence context for SwipeContextEvalTest (tools/swipe/dump_eval.py): FUTO test swipes,
the two words before each (from the record's sentence and word_idx; punctuation stripped), and the
model's log-probs. One JSON object per line.

    dump_eval.py --futo DIR --model best.pt --out swipe-eval.jsonl [-n 3000] [--skip 0]
"""
import argparse
import json
import re

import torch

from model import SwipeNet
from prepare_futo import features

WORD = re.compile(r"[^a-z']")


def clean(w):
    return WORD.sub("", w.lower()).strip("'")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("-n", type=int, default=3000)
    ap.add_argument("--skip", type=int, default=0)
    a = ap.parse_args()
    torch.set_num_threads(2)
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    n = seen = 0
    with open(f"{a.futo}/test.jsonl") as f, open(a.out, "w") as out:
        for line in f:
            d = json.loads(line)
            w = clean(d["word"])
            if not w.isalpha() or not w.isascii() or not d.get("data"):
                continue
            seen += 1
            if seen <= a.skip:
                continue
            before = [clean(x) for x in d["sentence"].split()[:d["word_idx"]]]
            before = [x for x in before if x]
            # a sentence end before this word starts a new sentence
            words = d["sentence"].split()[:d["word_idx"]]
            if words and words[-1][-1:] in ".!?":
                before = []
            with torch.no_grad():
                lp = m(features(d["data"], 0.3).unsqueeze(0))[0]
            out.write(json.dumps({"word": w, "prev": before[-2:], "start": d["word_idx"] == 0 or not before,
                                  "logp": [round(v, 3) for v in lp.flatten().tolist()]}) + "\n")
            n += 1
            if n >= a.n:
                break
    print(f"{n} swipes -> {a.out}")


if __name__ == "__main__":
    main()
