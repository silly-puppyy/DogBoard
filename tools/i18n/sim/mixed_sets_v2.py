#!/usr/bin/env python3
"""Mixed-language test sets shaped like real code-switching (notes/design/multilingual.md): most sentences in one
language; insertions (one or two content words of the matrix sentence replaced by their translation: nouns, verbs,
adjectives, as bilinguals insert; function words stay); alternations at a clause boundary (comma or conjunction).
mixed_sets.py switched at random mid-sentence points, the least natural pattern.

    mixed_sets_v2.py A_DIR A B_DIR B PAIRS.tsv OUT_DIR [N]     PAIRS: wiktionary_pairs.py (A word <TAB> B word <TAB> pos)

Each case gets the target word's language (column 6) and the words since the last switch (column 7, -1 = no switch
yet). Translations: Wiktionary (kaikki.org, CC BY-SA).
"""
import os
import random
import re
import subprocess
import sys
import unicodedata

# a letter, or a mark belonging to one (Devanagari ि ा ्): Python's \w has no marks, so किताब split into क त ब
# (the Hindi pack's text, 2026-11-23)
L = '(?:[^\\W\\d_]|[' + ''.join(chr(c) for c in range(0x300, 0x10000) if unicodedata.category(chr(c))[0] == 'M') + '])'

WORD = re.compile(L + r"+(?:['’]" + L + r"+)*")
HERE = os.path.dirname(os.path.abspath(__file__))
CLAUSE = re.compile(r",\s+|\s+(?:and|but|because|so|y|pero|porque|que|et|mais|parce)\s+")
SHARES = {'mono': 0.5, 'insert': 0.3, 'alternate': 0.2}


def sentences(path, n):
    out = []
    for line in open(path, encoding='utf-8'):
        line = line.strip().replace('’', "'")
        if len(WORD.findall(line)) >= 6: out.append(line)
        if len(out) >= n: break
    return out


def lexicon(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        if line.startswith('#'): continue
        w, _, n = line.rstrip('\n').partition('\t')
        try: d[w.lower()] = d.get(w.lower(), 0) + float(n.split('\t')[0])
        except ValueError: pass
    return d


def main():
    a_dir, a, b_dir, b, pairs_path, out = sys.argv[1:7]
    n = int(sys.argv[7]) if len(sys.argv) > 7 else 2000
    os.makedirs(out, exist_ok=True)
    rnd = random.Random(13)
    la, lb = lexicon(f'{a_dir}/{a}.lexicon.tsv'), lexicon(f'{b_dir}/{b}.lexicon.tsv')
    # translations both ways, only words each language lists (the most frequent B word for an A word, and back)
    a2b, b2a = {}, {}
    for line in open(pairs_path, encoding='utf-8'):
        wa, wb, _ = line.rstrip('\n').split('\t')
        if wa in la and wb in lb:
            if lb[wb] > lb.get(a2b.get(wa, ''), 0): a2b[wa] = wb
            if la[wa] > la.get(b2a.get(wb, ''), 0): b2a[wb] = wa
    top_a = set(sorted(la, key=la.get, reverse=True)[:300]); top_b = set(sorted(lb, key=lb.get, reverse=True)[:300])
    sa = sentences(f'{a_dir}/{a}.heldout.txt', 6 * n); sb = sentences(f'{b_dir}/{b}.heldout.txt', 6 * n)
    lines, labels, kinds = [], [], []
    for i in range(min(len(sa), len(sb))):
        matrix, other, lm, lo, table, top = (sa[i], sb[i], a, b, a2b, top_a) if i % 2 == 0 else (sb[i], sa[i], b, a, b2a, top_b)
        kind = rnd.choices(list(SHARES), weights=list(SHARES.values()))[0]
        if kind == 'insert':
            words = matrix.split(' ')
            # content words with a translation; never the matrix language's function words
            spots = [k for k, w in enumerate(words) if w.lower() in table and w.lower() not in top]
            if not spots: kind = 'mono'
            else:
                chosen = set(rnd.sample(spots, min(len(spots), rnd.choice([1, 1, 2]))))
                out_words, lab = [], []
                for k, w in enumerate(words):
                    t = table[w.lower()] if k in chosen else w
                    if k in chosen and w[:1].isupper(): t = t[:1].upper() + t[1:]
                    out_words.append(t)
                    lab += [lo if k in chosen else lm] * len(WORD.findall(t))
                lines.append(' '.join(out_words)); labels.append(lab); kinds.append(kind); continue
        if kind == 'alternate':
            cm = [m.end() for m in CLAUSE.finditer(matrix)]; co = [m.end() for m in CLAUSE.finditer(other)]
            if not cm or not co: kind = 'mono'
            else:
                head = matrix[:rnd.choice(cm)]; tail = other[rnd.choice(co):]
                lines.append(head + tail); labels.append([lm] * len(WORD.findall(head)) + [lo] * len(WORD.findall(tail))); kinds.append(kind); continue
        lines.append(matrix); labels.append([lm] * len(WORD.findall(matrix))); kinds.append('mono')
    held = f'{out}/mixed.heldout.txt'
    with open(held, 'w', encoding='utf-8') as f:
        for l in lines: f.write(l + '\n')
    union, seen = f'{out}/union.lexicon.tsv', set()
    with open(union, 'w', encoding='utf-8') as f:
        f.write('#dogboard-lexicon 1\n')
        for d, l in ((a_dir, a), (b_dir, b)):
            for line in open(f'{d}/{l}.lexicon.tsv', encoding='utf-8'):
                if line.startswith('#'): continue
                w = line.split('\t', 1)[0]
                if w not in seen: seen.add(w); f.write(line if line.endswith('\n') else line + '\n')
    subprocess.run([sys.executable, '-I', f'{HERE}/make_es_sets.py', held, union, f'{HERE}/typo_model7.json', out, str(n)],
                   env=dict(os.environ, SIM_PREFIX='mixed'), cwd=HERE, check=True, stdout=subprocess.DEVNULL)
    order = list(range(len(lines)))
    random.Random(7).shuffle(order)  # make_es_sets' first draw: its sentence numbering
    for kind in ('typos', 'controls', 'accents-clear', 'accents-twin'):
        p = f'{out}/mixed-{kind}.tsv'
        if not os.path.exists(p): continue
        rows = [l.rstrip('\n').split('\t') for l in open(p, encoding='utf-8')]
        with open(p, 'w', encoding='utf-8') as f:
            for r in rows:
                lab = labels[order[int(r[0])]]; k = int(r[1])
                if k >= len(lab): continue
                last = next((j for j in range(k, 0, -1) if lab[j] != lab[j - 1]), None)
                f.write('\t'.join(r[:5] + [lab[k], str(k - last if last is not None else -1)]) + '\n')
    from collections import Counter
    print(dict(Counter(kinds)), 'translations', len(a2b), len(b2a))


if __name__ == '__main__':
    main()
