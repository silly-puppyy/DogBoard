#!/usr/bin/env python3
"""Real swipes of one layout split by person into a training half and a test half (tools/swipe/layout_split.py).

    layout_split.py --futo DIR --layout german --lang de --out DIR

User 2026-11-19: for layouts with text but little swipe data, is simulated training data good enough? swipe-5 has
1,000-12,000 real swipes per layout; the people are split in two (seeded), so a model can learn from one half's real
swipes and be tested on people it never saw: synthetic only vs real only vs synthetic then fine-tuned on the real
half. Writes train-0.pt and dev.pt (training people; dev = 10 % of them; items (features, word, sy)) and test.pt
(the other people; (features, word)), in prepare_futo.py's format, physical units (y scaled by each swipe's own canvas height / width), the layout's letters.
"""
import argparse
import json
import os
import random

import torch

from prepare_futo import features


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    lay = json.load(open(f"{a.futo}/swipe-5/layouts/{a.layout}.json"))
    letters = {k["letter"] for k in lay["keys"]}
    rows = []
    for line in open(f"{a.futo}/swipe-5/swipe5.jsonl"):
        d = json.loads(line)
        if d.get("layout") != a.layout or (d.get("lang") or d.get("language")) != a.lang or not isinstance(d.get("data"), list) or not d["data"]:
            continue
        w = d["word"].lower().strip(".,;:!?\"()[]{}“”‘’„").replace("'", "").replace("’", "")
        if not w or any(c not in letters for c in w) or len(w) > 24:
            continue
        rows.append((d["session"], w, d))
    people = sorted({s for s, _, _ in rows})
    random.Random(17).shuffle(people)
    test_people = set(people[: len(people) // 2])
    def item(d, w):
        return features(d["data"], 0.3, 10.0, 10.0 * d["canvas_height"] / d["canvas_width"]), w
    # training items keep their vertical scale (uni.py places the layout's keys in the same units)
    train = [item(d, w) + (10.0 * d["canvas_height"] / d["canvas_width"],) for s, w, d in rows if s not in test_people]
    test = [item(d, w) for s, w, d in rows if s in test_people]
    random.Random(5).shuffle(train)
    n_dev = max(50, len(train) // 10)
    os.makedirs(a.out, exist_ok=True)
    torch.save(train[n_dev:], f"{a.out}/train-0.pt")
    torch.save(train[:n_dev], f"{a.out}/dev.pt")
    torch.save({"items": test}, f"{a.out}/test.pt")
    print(f"{a.layout}-{a.lang}: {len(people)} people; training half {len(train) - n_dev} swipes (+{n_dev} dev), "
          f"test half {len(test)} swipes from {len(test_people)} people")


if __name__ == "__main__":
    main()
