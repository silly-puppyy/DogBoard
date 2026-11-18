"""Can the language model be trusted to put back dropped apostrophes? (tools/lm/apostrophe_trust.py)

    apostrophe_trust.py CKPT TATOEBA_SENTENCES [-n 250]

User 2026-11-18: retrocorrecting its -> it's, were -> we're, well -> we'll ... is only worth it if it
almost never changes a word typed right. Text: Tatoeba English (edited, conversational; not in the
model's training data). Pairs where both forms are words. For each pair:
  control: the word written without an apostrophe and correct ("The dog wagged its tail") -- must stay
  error:   the apostrophe form with the apostrophe dropped ("I think its going to rain") -- should change
margin = ln P(apostrophe form + the k words after | text before) - ln P(typed form + the same) with
k = 0 (when the word ends) and k = 3 (hindsight, as a retro fix). A fix happens when margin > threshold;
reported: false alarms (controls changed) against catches (errors fixed), per threshold.
"""
import argparse
import math
import os
import random
import re
import sys

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sentencepiece as spm  # noqa: E402
import caps  # noqa: E402
from student import Config, Student  # noqa: E402

PAIRS = {  # apostrophe form -> the same without the apostrophe (both words)
    "it's": "its", "we're": "were", "we'll": "well", "he'll": "hell", "she'd": "shed", "I'll": "ill",
    "won't": "wont", "can't": "cant", "I'd": "id", "let's": "lets", "we'd": "wed", "she'll": "shell",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("sentences")
    ap.add_argument("-n", type=int, default=250, help="cases per pair and side")
    a = ap.parse_args()
    s = torch.load(a.ckpt, map_location="cuda")
    net = Student(Config(**s["config"])).cuda().eval()
    net.load_state_dict(s["model"])
    sp = spm.SentencePieceProcessor(model_file=os.path.join(s["tokdir"], "sp16k.model"))
    enc = (lambda t: caps.encode(sp, t)) if s.get("caps") else (lambda t: sp.encode(t))

    @torch.no_grad()
    def lp(ctx, conts):
        c = [1] + (enc(ctx) if ctx.strip() else [])
        seqs = [c + enc(x) for x in conts]
        T = max(map(len, seqs))
        ids = torch.zeros(len(seqs), T, dtype=torch.long, device="cuda")
        for i, q in enumerate(seqs):
            ids[i, :len(q)] = torch.tensor(q)
        o = torch.log_softmax(net(ids).float(), -1)
        return [sum(float(o[i, j - 1, q[j]]) for j in range(len(c), len(q))) for i, q in enumerate(seqs)]

    # collect cases from the sentences (each sentence line: id<TAB>lang<TAB>text, or just text)
    want = {k: {"control": [], "error": []} for k in PAIRS}
    rng = random.Random(1)
    lines = open(a.sentences, encoding="utf-8").read().splitlines()
    rng.shuffle(lines)
    for line in lines:
        text = line.split("\t")[-1].replace("’", "'")
        words = text.split(" ")
        for i, w in enumerate(words):
            core = re.sub(r"[^\w']", "", w)
            for apo, plain in PAIRS.items():
                side = "error" if core.lower() == apo.lower() else "control" if core.lower() == plain else None
                if side and len(want[apo][side]) < a.n and i > 0:
                    before = " ".join(words[:i])
                    after = words[i + 1:i + 4]
                    want[apo][side].append((before, after))
        if all(len(v["control"]) >= a.n and len(v["error"]) >= a.n for v in want.values()):
            break
    results = {0: [], 3: []}
    for apo, plain in PAIRS.items():
        for side in ("control", "error"):
            for before, after in want[apo][side]:
                for k in (0, 3):
                    tail = (" " + " ".join(after[:k])) if k and after else ""
                    g, b = lp(before, [apo + tail, plain + tail])
                    results[k].append((apo, side, g - b))
    for k in (0, 3):
        r = results[k]
        ctrl = sorted(m for _, sd, m in r if sd == "control")
        err = [m for _, sd, m in r if sd == "error"]
        print(f"\n== {'when the word ends' if k == 0 else 'with 3 words of hindsight'} (controls {len(ctrl)}, errors {len(err)})")
        print("   threshold  false alarms (right words changed)  catches (dropped apostrophes fixed)")
        for th in (0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0):
            fa = sum(m > th for m in ctrl) / len(ctrl)
            ca = sum(m > th for m in err) / len(err)
            print(f"   {th:5.1f}      {100 * fa:6.2f} %                          {100 * ca:6.1f} %")
        # per pair at the threshold where pooled false alarms first fall to <= 0.5 %
        th = next(t / 4 for t in range(0, 80) if sum(m > t / 4 for m in ctrl) / len(ctrl) <= 0.005)
        print(f"   per pair at threshold {th} (pooled false alarms <= 0.5 %):")
        for apo, plain in PAIRS.items():
            c = [m for p, sd, m in r if p == apo and sd == "control"]
            e = [m for p, sd, m in r if p == apo and sd == "error"]
            if c and e:
                print(f"     {plain:>5s} -> {apo:<7s} false alarms {100 * sum(m > th for m in c) / len(c):5.1f} % (n {len(c)})  "
                      f"catches {100 * sum(m > th for m in e) / len(e):5.1f} % (n {len(e)})")


if __name__ == "__main__":
    main()
