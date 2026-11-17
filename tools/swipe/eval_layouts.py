#!/usr/bin/env python3
"""Layout-agnostic models on real swipes from layouts and languages they never saw (tools/swipe/eval_layouts.py).

    eval_layouts.py --model best.pt --layouts DIR --en-lexicon en.lexicon.tsv --lexicons DIR [--futo-test test.pt] [-n 2000]

Each prepare_layouts.py set is decoded with its language's lexicon (English: ours; others:
wordfreq_lexicon.py), its 160k most frequent words plus the set's own words (as FUTO's paper setup),
beam 100, no context; the keyboard given to the model is that layout's. Results go to the model's
wandb run (eval/<layout>-<lang>_top1 ...).
"""
import argparse
import glob
import os
import random
import sys

import numpy as np
import torch

from decode import Trie, beam, load_lexicon
from model_la import SwipeNetLA

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import track  # noqa: E402


def run_set(model, items, keys, lex):
    letters = "".join(sorted(keys))
    kt = torch.tensor([keys[c] for c in letters], dtype=torch.float32).unsqueeze(0)
    have = {w for w, _ in lex}
    floor = min(lp for _, lp in lex)
    root = Trie.build(lex + [(w, floor) for w in sorted({w for _, w in items} - have)])
    t1 = t3 = t10 = 0
    with torch.no_grad():
        for f, w in items:
            lp = model(f.unsqueeze(0), kt)[0].numpy().astype(np.float64)
            c = [x for x, _ in beam(lp, root, letters, width=100, top=10)]
            t1 += c[:1] == [w]; t3 += w in c[:3]; t10 += w in c
    n = len(items)
    return {"top1": 100 * t1 / n, "top3": 100 * t3 / n, "top10": 100 * t10 / n, "n": n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--layouts", required=True)
    ap.add_argument("--en-lexicon", required=True)
    ap.add_argument("--lexicons", required=True)
    ap.add_argument("--futo-test", default="")
    ap.add_argument("-n", type=int, default=2000)
    ap.add_argument("--name", default="")
    a = ap.parse_args()
    torch.set_num_threads(1)
    ck = torch.load(a.model, map_location="cpu")
    model = SwipeNetLA(ck["backbone"])
    model.load_state_dict(ck["model"])
    model.eval()
    lexes = {}

    def lexicon(lang):
        if lang not in lexes:
            path = a.en_lexicon if lang == "en" else os.path.join(a.lexicons, f"{lang}.tsv")
            lexes[lang] = load_lexicon(path, top=160000) if os.path.exists(path) else None
        return lexes[lang]
    results = {}
    sets = []
    if a.futo_test:
        import json
        lay = json.load(open(os.path.join(a.layouts, "..", "futo", "swipe-5", "layouts", "qwerty.json")))
        sets.append(("futo-test-qwerty-en", torch.load(a.futo_test), {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}, "en"))
    for f in sorted(glob.glob(os.path.join(a.layouts, "*.pt"))):
        d = torch.load(f)
        sets.append((f"{d['layout']}-{d['lang']}", d["items"], d["keys"], d["lang"]))
    for name, items, keys, lang in sets:
        lex = lexicon(lang)
        if lex is None:
            print(f"{name}: no lexicon for {lang}, skipped", flush=True)
            continue
        items = list(items)
        random.Random(7).shuffle(items)
        items = items[:a.n] if a.n else items
        r = run_set(model, items, keys, lex)
        results.update({f"eval/{name}_{k}": v for k, v in r.items()})
        print(f"{name:22s} n {r['n']:5d}  top-1 {r['top1']:.2f}  top-3 {r['top3']:.2f}  top-10 {r['top10']:.2f}", flush=True)
    run = track.start("swipe", "en", a.name or os.path.basename(os.path.dirname(os.path.abspath(a.model))), layout="any")
    run.log(results)
    for k, v in results.items():
        run.summary[k] = v
    run.finish()


if __name__ == "__main__":
    main()
