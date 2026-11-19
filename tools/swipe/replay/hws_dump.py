#!/usr/bin/env python3
"""How We Swipe swipes in whole sentences, with the words before each and v4's log-probs, in dump_eval.py's format
(tools/swipe/replay/hws_dump.py), for SwipeContextEvalTest: tunes the swipe context weight on sloppier, other
people's swipes than FUTO's own test set (2026-11-19: the app's 0.7, tuned on FUTO's, let the network's shape
preference beat common words: see -> se, talked -> taped).

    hws_dump.py --model best.pt --out hws-eval.jsonl [-n 3000]
    hws_dump.py --futo --out hws-futo.jsonl     # FUTO's encoder instead (compare_futo.py; run with venv-executorch)
"""
import argparse, json, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import torch
from model import SwipeNet
from prepare_futo import resample
from replay import hws

ap = argparse.ArgumentParser(); ap.add_argument("--model"); ap.add_argument("--futo", action="store_true"); ap.add_argument("--out", required=True); ap.add_argument("-n", type=int, default=3000)
a = ap.parse_args()
torch.set_num_threads(1)
if a.futo:
    import json as _j
    from compare_futo import D, Futo
    fu = Futo(_j.load(open(f"{D}/swipe/futo/swipe-5/layouts/qwerty.json")))
else:
    m = SwipeNet(); m.load_state_dict(torch.load(a.model, map_location="cpu")); m.eval()
sw, _, sent = hws(a.n, True)
with open(a.out, "w") as out:
    before = []
    for i, ((w, pts), sid) in enumerate(zip(sw, sent)):
        if i == 0 or sent[i - 1] != sid: before = []
        if a.futo:
            lp = torch.from_numpy(fu.logp([p[0] for p in pts], [p[1] for p in pts], [p[2] for p in pts]))
            out.write(json.dumps({"word": w, "prev": before[-2:], "start": not before, "before": " ".join(before),
                                  "logp": [round(v, 3) for v in lp.flatten().tolist()]}) + "\n")
            before.append(w); continue
        xy = resample([(x * 10, y * 3) for x, y, _ in pts], 0.3)
        f = torch.tensor([(x, y, (xy[k+1][0] if k+1 < len(xy) else x) - x, (xy[k+1][1] if k+1 < len(xy) else y) - y) for k, (x, y) in enumerate(xy)], dtype=torch.float32)
        with torch.no_grad(): lp = m(f.unsqueeze(0))[0]
        out.write(json.dumps({"word": w, "prev": before[-2:], "start": not before, "before": " ".join(before),
                              "logp": [round(v, 3) for v in lp.flatten().tolist()]}) + "\n")
        before.append(w)
print(f"{len(sw)} swipes -> {a.out}")
