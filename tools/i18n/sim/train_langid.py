#!/usr/bin/env python3
"""Per-word language choice for multilingual typing (notes/design/multilingual.md step 3): one logistic model for every
pair, over features of "this language" against "the other" for the word and the two before it: listed in this word
list minus listed in the other's, and ln P (unigram, from the word lists' counts) here minus there. iOS has a model per
pair (Multilingual-<pair>.model, 18 features); symmetric differences let one set of weights serve any pair.

Trained on mixed text WITH switches inside sentences (the prototype: 71 % right after a switch without them, 85 % with).

    train_langid.py OUT.json TRAIN_PAIRS TEST_PAIRS    pairs as "dirA:a+dirB:b,dirC:c+dirD:d"
"""
import json
import math
import random
import re
import sys

import numpy as np
import unicodedata

# a letter, or a mark belonging to one (Devanagari ि ा ्): Python's \w has no marks, so किताब split into क त ब
# (the Hindi pack's text, 2026-11-23)
L = '(?:[^\\W\\d_]|[' + ''.join(chr(c) for c in range(0x300, 0x10000) if unicodedata.category(chr(c))[0] == 'M') + '])'

WORD = re.compile(L + r"+(?:['’]" + L + r"+)*")
FLOOR = -22.0


def lexicon(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        if line.startswith('#'): continue
        w, _, n = line.rstrip('\n').partition('\t')
        try: d[w.lower()] = d.get(w.lower(), 0) + float(n.split('\t')[0])
        except ValueError: pass
    t = sum(d.values())
    return {w: math.log(n / t) for w, n in d.items()}


def sentences(path, n):
    out = []
    for line in open(path, encoding='utf-8'):
        ws = [w.lower().replace('’', "'") for w in WORD.findall(line)]
        if len(ws) >= 6: out.append(ws)
        if len(out) >= n: break
    return out


def mixed(sa, sb, rnd):
    """Sentences switching language inside: (words, labels 0 = a, 1 = b)."""
    out = []
    for i in range(min(len(sa), len(sb))):
        x, y, lx, ly = (sa[i], sb[i], 0, 1) if i % 2 == 0 else (sb[i], sa[i], 1, 0)
        cx = rnd.randint(2, max(2, len(x) // 2)); cy = rnd.randint(0, max(0, len(y) // 2))
        out.append((x[:cx] + y[cy:], [lx] * cx + [ly] * (len(y) - cy)))
    return out


def feats(words, i, la, lb):
    """Features of "a" against "b" for word i: (listed, ln P) differences for the word and the two before it."""
    f = []
    for j in (0, 1, 2):
        k = i - j
        if k < 0: f += [0.0, 0.0]; continue
        w = words[k]
        pa, pb = la.get(w), lb.get(w)
        f += [(pa is not None) - (pb is not None), ((pa if pa is not None else FLOOR) - (pb if pb is not None else FLOOR)) / 10]
    return f


def data(pairs, n, rnd):
    X, y, since = [], [], []
    for (da, a), (db, b) in pairs:
        la, lb = lexicon(f'{da}/{a}.lexicon.tsv'), lexicon(f'{db}/{b}.lexicon.tsv')
        for words, labels in mixed(sentences(f'{da}/{a}.heldout.txt', n), sentences(f'{db}/{b}.heldout.txt', n), rnd):
            switch = next((k for k in range(1, len(labels)) if labels[k] != labels[k - 1]), None)
            for i in range(len(words)):
                f = feats(words, i, la, lb)
                # both orientations: the model is symmetric (no pair or side favoured)
                X.append(f); y.append(1 - labels[i]); X.append([-v for v in f]); y.append(labels[i])
                s = i - switch if switch is not None and i >= switch else -1
                since += [s, s]
    return np.array(X), np.array(y), np.array(since)


def fit(X, y, steps=400, lr=0.5, l2=1e-4):
    w = np.zeros(X.shape[1])
    for _ in range(steps):
        p = 1 / (1 + np.exp(-(X @ w)))
        w -= lr * (X.T @ (p - y) / len(y) + l2 * w)
    return w


def parse(spec):
    return [tuple(tuple(side.split(':')) for side in pair.split('+')) for pair in spec.split(',')]


def main():
    out, train, test = sys.argv[1], parse(sys.argv[2]), parse(sys.argv[3])
    rnd = random.Random(3)
    X, y, _ = data(train, 6000, rnd)
    w = fit(X, y)
    names = [f'{k}_{j}' for j in ('w0', 'w-1', 'w-2') for k in ('listed', 'lnp')]
    print('weights:', ', '.join(f'{n} {v:+.2f}' for n, v in zip(names, w)))
    report = {}
    for pair in train + test:
        Xt, yt, st = data([pair], 3000, random.Random(5))
        p = (1 / (1 + np.exp(-(Xt @ w)))) > 0.5
        ok = p == yt
        near = (st >= 0) & (st <= 2)
        word_only = (1 / (1 + np.exp(-(Xt[:, :2] @ w[:2])))) > 0.5
        key = f'{pair[0][1]}x{pair[1][1]}' + (' (held out)' if pair in test else '')
        report[key] = {'all': round(100 * ok.mean(), 1), 'after_switch': round(100 * ok[near].mean(), 1),
                       'word_only_after_switch': round(100 * (word_only == yt)[near].mean(), 1)}
        print(key, report[key])
    json.dump({'features': names, 'weights': [round(float(v), 4) for v in w], 'floor': FLOOR, 'report': report},
              open(out, 'w'), indent=1)


if __name__ == '__main__':
    main()
