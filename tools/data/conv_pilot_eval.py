#!/usr/bin/env python3
"""Next-word top-1 / top-3 of KenLM 3-gram ARPA models on held-out sentence files (conversational-source pilot).

    python3 -I conv_pilot_eval.py --probe "руски" --probe "часа руски" --set news=test-news.txt --set chat=test-x.txt M1.arpa M2.arpa

Per position of every sentence (pack tokens, one sentence per line) the context is the previous two words, with <s> at
the start, as the app's trigram sees it; the candidates are the trigram successors, the bigram successors and the most frequent
unigrams (as many as needed), scored with the ARPA back-off formula (the ranking is exact). <s> </s> <unk> are never predicted. A target outside the model's vocabulary counts as a miss.
Prints: top-1 and top-3 percent, OOV percent, positions, per set; and the top 5 after each --probe."""
import argparse, collections, heapq, sys


def ctxs(tokens):
    t = ['<s>'] + tokens
    for i in range(1, len(t)):
        yield tuple(t[max(0, i - 2):i]), t[i]


def load(arpa, ctx1, ctx2, last):
    uni = {}; bow1 = {}; bi = collections.defaultdict(list); bow2 = {}; tri = collections.defaultdict(list)
    sec = 0
    with open(arpa, encoding='utf-8', newline='\n') as f:
        for line in f:
            if line[0] == '\\':
                sec = int(line[1]) if line[1].isdigit() else 0
                continue
            p = line.rstrip('\n').split('\t')
            if len(p) < 2: continue
            if sec == 1:
                w = p[1]
                if w not in ('<s>', '</s>', '<unk>'): uni[w] = float(p[0])
                if w in last or w == '<s>': bow1[w] = float(p[2]) if len(p) > 2 else 0.0
            elif sec == 2:
                a, b = p[1].split(' ')
                if a in last and b not in ('</s>', '<unk>'): bi[a].append((b, float(p[0])))
                if (a, b) in ctx2 and len(p) > 2: bow2[(a, b)] = float(p[2])
            elif sec == 3:
                a, b, c = p[1].split(' ')
                if (a, b) in ctx2 and c not in ('</s>', '<unk>'): tri[(a, b)].append((c, float(p[0])))
    return uni, bow1, bi, bow2, tri


def top(model, ctx, k, topuni):
    uni, bow1, bi, bow2, tri = model
    if len(ctx) == 2:
        a, b = ctx
        b2 = bow2.get((a, b), 0.0)
        tr = dict(tri.get((a, b), ()))
    else:
        (b,) = ctx; b2 = 0.0; tr = {}
    scores = dict(tr)
    bl = bi.get(b, ())
    if isinstance(bl, list):                      # sort each word's successors once, best first, with a set for lookups
        bl.sort(key=lambda x: -x[1]); bi[b] = bl = (bl, dict(bl))
    lst, bis = bl if bl else ((), {})
    n = 0
    for w, lp in lst:                             # only the best k successors without a trigram can reach the top k
        if w not in tr:
            scores[w] = b2 + lp; n += 1
            if n >= k: break
    bb = bow1.get(b, 0.0)
    n = 0
    for w in topuni:                              # unigrams without a bigram all score b2 + bb + uni: the first k in unigram order
        if w in bis or w in scores: continue
        scores[w] = b2 + bb + uni[w]; n += 1
        if n >= k: break
    return heapq.nlargest(k, scores, key=scores.get)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--set', action='append', default=[]); ap.add_argument('--probe', action='append', default=[])
    ap.add_argument('arpa', nargs='+')
    a = ap.parse_args()
    tests = {}
    for s in a.set:
        n, _, f = s.partition('=')
        tests[n] = [l.split() for l in open(f, encoding='utf-8', newline='\n') if l.strip()]
    ctx1 = set(); ctx2 = set(); last = set(['<s>'])
    for sents in tests.values():
        for t in sents:
            for c, _ in ctxs(t):
                (ctx2 if len(c) == 2 else ctx1).add(c); last.add(c[-1])
    probes = []
    for p in a.probe:
        w = p.lower().split(); c = tuple(w[-2:]); probes.append((p, c)); (ctx2 if len(c) == 2 else ctx1).add(c); last.add(c[-1])
    for arpa in a.arpa:
        model = load(arpa, ctx1, ctx2, last)
        topuni = sorted(model[0], key=model[0].get, reverse=True)[:3000]
        print('##', arpa, 'vocab', len(model[0]), flush=True)
        for name, sents in tests.items():
            n = h1 = h3 = oov = 0
            cache = {}
            for t in sents:
                for c, w in ctxs(t):
                    n += 1
                    if w not in model[0]: oov += 1; continue
                    if c not in cache: cache[c] = top(model, c, 3, topuni)
                    r = cache[c]
                    h1 += r[:1] == [w]; h3 += w in r
            print(f'{name}\tn={n}\ttop1={100 * h1 / n:.2f}\ttop3={100 * h3 / n:.2f}\toov={100 * oov / n:.2f}', flush=True)
        for p, c in probes:
            print('probe', repr(p), '->', ' / '.join(top(model, c, 5, topuni)), flush=True)


main()
