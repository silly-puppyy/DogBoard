#!/usr/bin/env python3
"""Does the neural language model make swipe typing more accurate? (tools/swipe/swipe_neural.py)

    swipe_neural.py LM_CKPT cands.tsv     (cands.tsv: SwipeContextEvalTest's DOGBOARD_SWIPE_DUMP)

As iOS does (decompile: the beam uses only the lexicon; all candidates are then scored in one batch by
the language model given the text before, and that score replaces the unigram): each swipe's 10
candidates get ln P(word | the sentence before) from our transformer (tools/lm, v3). Ranking variants,
weights chosen on the first half of the swipes and reported on the second:
  unigram           path + ln P(word)
  trigram           path + w * trigram          (what the keyboard does now)
  neural            path + w * neural
  trigram + neural  path + w1 * trigram + w2 * neural
"""
import itertools
import os
import sys

import torch

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "lm"))
import sentencepiece as spm  # noqa: E402
import caps  # noqa: E402
from student import Config, Student  # noqa: E402


def main():
    ckpt, path = sys.argv[1], sys.argv[2]
    s = torch.load(ckpt, map_location="cuda")
    net = Student(Config(**s["config"])).cuda().eval()
    net.load_state_dict(s["model"])
    sp = spm.SentencePieceProcessor(model_file=os.path.join(s["tokdir"], "sp16k.model"))
    enc = (lambda t: caps.encode(sp, t)) if s.get("caps") else (lambda t: sp.encode(t))

    @torch.no_grad()
    def neural_lp(context, words):
        ctx = [1] + (enc(context) if context.strip() else [])
        seqs = [ctx + enc(w) for w in words]
        T = max(len(q) for q in seqs)
        ids = torch.zeros(len(seqs), T, dtype=torch.long, device="cuda")
        for i, q in enumerate(seqs):
            ids[i, :len(q)] = torch.tensor(q)
        lp = torch.log_softmax(net(ids).float(), -1)
        return [sum(float(lp[i, j - 1, q[j]]) for j in range(len(ctx), len(q))) for i, q in enumerate(seqs)]

    def norm(w):
        return w.lower().replace("'", "").replace("’", "")

    cases = []
    for line in open(path, encoding="utf-8"):
        before, target, cands = line.rstrip("\n").split("\t")
        cs = [c.rsplit(",", 3) for c in cands.split("|") if c]
        if not cs:
            cases.append((target, [], [], [], [], []))
            continue
        words = [c[0] for c in cs]
        cases.append((norm(target), [norm(w) for w in words], [float(c[1]) for c in cs], [float(c[2]) for c in cs],
                      [float(c[3]) for c in cs], neural_lp(before, words)))
    half = len(cases) // 2
    tune, test = cases[:half], cases[half:]

    def acc(items, f):
        t1 = t3 = 0
        for target, words, path, uni, tri, neu in items:
            if not words:
                continue
            order = sorted(range(len(words)), key=lambda i: -f(path[i], uni[i], tri[i], neu[i]))
            t1 += words[order[0]] == target
            t3 += target in [words[i] for i in order[:3]]
        return 100 * t1 / len(items), 100 * t3 / len(items)

    grid = [0.2, 0.35, 0.5, 0.7, 1.0, 1.3, 1.6]
    variants = {
        "unigram": [lambda p, u, t, n: p + u],
        "trigram": [lambda p, u, t, n, w=w: p + w * t for w in grid],
        "neural": [lambda p, u, t, n, w=w: p + w * n for w in grid],
        "trigram + neural": [lambda p, u, t, n, a=a, b=b: p + a * t + b * n for a, b in itertools.product([0.0, 0.2, 0.35, 0.5, 0.7], grid)],
    }
    names = {"trigram": grid, "neural": grid}
    print(f"{len(cases)} swipes; tuned on {len(tune)}, reported on {len(test)}")
    for name, fs in variants.items():
        best = max(range(len(fs)), key=lambda i: acc(tune, fs[i])[0])
        t1, t3 = acc(test, fs[best])
        which = ""
        if name in names:
            which = f" (weight {names[name][best]})"
        elif name == "trigram + neural":
            a, b = list(itertools.product([0.0, 0.2, 0.35, 0.5, 0.7], grid))[best]
            which = f" (trigram {a}, neural {b})"
        print(f"  {name:18s} top-1 {t1:.1f}  top-3 {t3:.1f}{which}", flush=True)


if __name__ == "__main__":
    main()
