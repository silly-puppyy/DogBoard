#!/usr/bin/env python3
"""FUTO's swipe encoder vs ours on the same real swipes, same decoder (tools/swipe/compare_futo.py; run with
keyboard-data/venv-executorch, which has torch and the ExecuTorch runtime).

    compare_futo.py hws [-n 3000]                 # How We Swipe (neither model trained on it)
    compare_futo.py futo-test [-n 3000]           # FUTO's own test set (both trained on FUTO's training set)
    compare_futo.py layout german de              # swipe-5, the held-out half's people (layout_split.py's split)

User 2026-11-19: "how do we do vs FUTO and Gboard, can't we extract their models?" FUTO Keyboard 0.1.30 ships its
swipe models (assets/futo-swipe) under the FUTO Model Weights License 1.0: any use, including commercial, with a
visible "powered by FUTO Swipe" notice; this benchmark only reads them from the data drive (not in the repo). The
encoder (honorable_sturgeon, 635K params) is layout-agnostic: a path resampled to 64 points by time in the
keyboard's [0,1] frame plus the layout's key centres -> per-step log-probs over the keys + blank. Both models'
outputs go through our lexicon beam (decode.beam, width 48, unigram prior, no context), so the comparison is the
networks. Ours: v4 (grid units) on QWERTY sets; the per-layout CVAE models (physical units) on other layouts.
"""
import json
import os
import random
import sys

import numpy as np
import torch

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from decode import Trie, beam, load_lexicon  # noqa: E402
from model import SwipeNet  # noqa: E402
from prepare_futo import features  # noqa: E402

D = "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data"
EN = os.path.join(HERE, "../../app/src/main/assets/dogboard/en.lexicon.tsv")
FUTO_ENCODER = f"{D}/swipe/futo-swipe-model/honorable_sturgeon/model_fp32.pte"


def resample64(px, py, pt, T=64):
    """FUTO's resampling (their README): uniform 60 Hz by time, then T points."""
    x, y, t = map(np.asarray, (px, py, pt))
    t = t - t[0]
    if t[-1] > 1e-3:
        n60 = max(2, round(t[-1] / (1000.0 / 60.0)) + 1)
        tt = np.linspace(0.0, t[-1], n60)
        x, y = np.interp(tt, t, x), np.interp(tt, t, y)
    idx = np.linspace(0, len(x) - 1, T)
    return np.stack([np.interp(idx, np.arange(len(x)), x), np.interp(idx, np.arange(len(y)), y)], 0).astype(np.float32)


class Futo:
    def __init__(self, layout):
        from executorch.runtime import Runtime
        self.m = Runtime.get().load_program(FUTO_ENCODER).load_method("forward")
        keys = sorted(layout["keys"], key=lambda k: k["letter"])
        self.letters = "".join(k["letter"] for k in keys)
        self.keys = torch.zeros(1, 64, 2); self.mask = torch.zeros(1, 64, dtype=torch.bool)
        for i, k in enumerate(keys):
            self.keys[0, i] = torch.tensor([k["cx"], k["cy"]]); self.mask[0, i] = True

    def logp(self, px, py, pt):
        out = self.m.execute((torch.from_numpy(resample64(px, py, pt)[None]), self.keys, self.mask))[0].numpy()[0]
        n = len(self.letters)
        # ours: [blank, letters...]; theirs: [keys 0..63, blank]
        return np.concatenate([out[:, -1:], out[:, :n]], 1).astype(np.float64)


def clean(w):
    return w.lower().strip(".,;:!?\"()[]{}“”‘’„").replace("'", "").replace("’", "")


def score(name, items, logp_fn, root, alphabet):
    t1 = t10 = 0
    for w, rec in items:
        c = [x for x, _ in beam(logp_fn(rec), root, alphabet, width=48, top=10)]
        t1 += bool(c) and clean(c[0]) == w; t10 += w in [clean(x) for x in c]
    print(f"  {name:28s} top-1 {100 * t1 / len(items):5.1f}  top-10 {100 * t10 / len(items):5.1f}  (n {len(items)})", flush=True)


def main():
    mode = sys.argv[1]
    n = int(sys.argv[sys.argv.index("-n") + 1]) if "-n" in sys.argv else 3000
    torch.set_num_threads(1)
    if mode in ("hws", "futo-test"):
        lay = json.load(open(f"{D}/swipe/futo/swipe-5/layouts/qwerty.json"))
        if mode == "hws":
            sys.path.insert(0, os.path.join(HERE, "replay"))
            from replay import hws
            sw, _, _ = hws(n, True)
            # hws(): qwerty.json 0..1 units plus ms -> records
            items = [(w, {"x": [p[0] for p in pts], "y": [p[1] for p in pts], "t": [p[2] for p in pts]}) for w, pts in sw]
        else:
            rows = [json.loads(l) for l in open(f"{D}/swipe/futo/test.jsonl")]
            rows = [d for d in rows if (w := clean(d.get("word", ""))) and w.isascii() and w.isalpha() and d.get("data")]
            random.Random(7).shuffle(rows)
            items = [(clean(d["word"]), {"x": [q["x"] for q in d["data"]], "y": [q["y"] for q in d["data"]], "t": [q["t"] for q in d["data"]]}) for d in rows[:n]]
        lex = load_lexicon(EN, top=400000); root = Trie.build(lex)
        fu = Futo(lay)
        net = SwipeNet(); net.load_state_dict(torch.load(f"{D}/swipe/runs/v4-sizes/best.pt", map_location="cpu")); net.eval()
        def ours(r):
            f = features([{"x": x, "y": y} for x, y in zip(r["x"], r["y"])], 0.3, 10.0, 3.0)
            with torch.no_grad():
                return net(f.unsqueeze(0))[0].numpy().astype(np.float64)
        print(f"{mode}:")
        score("FUTO encoder", items, lambda r: fu.logp(r["x"], r["y"], r["t"]), root, fu.letters)
        score("DogBoard v4", items, ours, root, "abcdefghijklmnopqrstuvwxyz")
        return
    lay_name, lang = sys.argv[2], sys.argv[3]
    lay = json.load(open(f"{D}/swipe/futo/swipe-5/layouts/{lay_name}.json"))
    letters = {k["letter"] for k in lay["keys"]}
    rows = []
    for line in open(f"{D}/swipe/futo/swipe-5/swipe5.jsonl"):
        d = json.loads(line)
        if d.get("layout") != lay_name or (d.get("lang") or d.get("language")) != lang or not isinstance(d.get("data"), list) or not d["data"]:
            continue
        w = clean(d["word"])
        if w and all(c in letters for c in w) and len(w) <= 24:
            rows.append(d)
    people = sorted({d["session"] for d in rows}); random.Random(17).shuffle(people)
    test_people = set(people[: len(people) // 2])  # as layout_split.py
    items = [(clean(d["word"]), d) for d in rows if d["session"] in test_people]
    lexpath = EN if lang == "en" else f"{D}/swipe/lexicons/{lang}.tsv"
    alphabet = "".join(sorted(letters))
    lex = load_lexicon(lexpath, top=160000, letters=None if alphabet == "abcdefghijklmnopqrstuvwxyz" else alphabet)
    root = Trie.build(lex + [(w, min(lp for _, lp in lex)) for w in sorted({w for w, _ in items} - {w for w, _ in lex})])
    fu = Futo(lay)
    print(f"{lay_name}-{lang}, held-out half's people:")
    score("FUTO encoder", items, lambda d: fu.logp([q["x"] for q in d["data"]], [q["y"] for q in d["data"]], [q["t"] for q in d["data"]]), root, fu.letters)
    for tag in ("cvae2", "cvae2ft"):
        p = f"{D}/swipe/runs/{tag}-{lay_name}-{lang}/best.pt"
        if not os.path.exists(p):
            continue
        net = SwipeNet(letters=len(alphabet)); net.load_state_dict(torch.load(p, map_location="cpu")); net.eval()
        def ours(d, net=net):
            f = features(d["data"], 0.3, 10.0, 10.0 * d["canvas_height"] / d["canvas_width"])
            with torch.no_grad():
                return net(f.unsqueeze(0))[0].numpy().astype(np.float64)
        score(f"DogBoard {tag}", items, ours, root, alphabet)


if __name__ == "__main__":
    main()
