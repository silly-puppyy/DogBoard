#!/usr/bin/env python3
"""Mixed-language test sets for multilingual typing (notes/design/multilingual.md): held-out sentences of two languages
joined so the language switches inside a sentence (the first part of one, the rest of another), then make_es_sets.py's
typo / control sets over the union of both word lists (it takes only listed targets: the union keeps both languages'
words). Every case gets a sixth column: the target word's language, and a seventh: words since the switch (0 = the
first word after it).

    mixed_sets.py A_DIR A_LANG B_DIR B_LANG OUT_DIR [N]   (env SIM_ROWS: the entry's layout rows)
"""
import json
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


def sentences(path, n):
    out = []
    for line in open(path, encoding='utf-8'):
        line = line.strip().replace('’', "'")
        if len(WORD.findall(line)) >= 6: out.append(line)
        if len(out) >= n: break
    return out


def main():
    a_dir, a, b_dir, b, out = sys.argv[1:6]
    n = int(sys.argv[6]) if len(sys.argv) > 6 else 2000
    os.makedirs(out, exist_ok=True)
    rnd = random.Random(11)
    sa = sentences(f'{a_dir}/{a}.heldout.txt', 6 * n)
    sb = sentences(f'{b_dir}/{b}.heldout.txt', 6 * n)
    lines, meta = [], []
    for i in range(min(len(sa), len(sb))):
        first, second = (sa[i], sb[i]) if i % 2 == 0 else (sb[i], sa[i])
        la, lb = (a, b) if i % 2 == 0 else (b, a)
        wa, wb = first.split(), second.split()
        cut_a = rnd.randint(2, max(2, len(wa) // 2)); cut_b = rnd.randint(0, max(0, len(wb) // 2))
        head, tail = wa[:cut_a], wb[cut_b:]
        lines.append(' '.join(head + tail))
        # the language of each word position (by WORD tokens, as make_es_sets counts them)
        n_head = len(WORD.findall(' '.join(head)))
        meta.append((la, lb, n_head))
    held = f'{out}/mixed.heldout.txt'
    with open(held, 'w', encoding='utf-8') as f:
        for l in lines: f.write(l + '\n')
    # the union word list (first spelling wins its count)
    union, seen = f'{out}/union.lexicon.tsv', set()
    with open(union, 'w', encoding='utf-8') as f:
        f.write('#dogboard-lexicon 1\n')
        for d, l in ((a_dir, a), (b_dir, b)):
            for line in open(f'{d}/{l}.lexicon.tsv', encoding='utf-8'):
                if line.startswith('#'): continue
                w = line.split('\t', 1)[0]
                if w not in seen: seen.add(w); f.write(line if line.endswith('\n') else line + '\n')
    env = dict(os.environ, SIM_PREFIX='mixed')
    subprocess.run([sys.executable, '-I', f'{HERE}/make_es_sets.py', held, union, f'{HERE}/typo_model7.json', out, str(n)],
                   env=env, cwd=HERE, check=True, stdout=subprocess.DEVNULL)
    # the language of each case's target, and how far after the switch it is; make_es_sets numbers the sentences after
    # shuffling them with Random(7) (its first draw): the same shuffle of the indices maps its numbers back
    order = list(range(len(lines)))
    random.Random(7).shuffle(order)
    for kind in ('typos', 'controls', 'accents-clear', 'accents-twin'):
        p = f'{out}/mixed-{kind}.tsv'
        if not os.path.exists(p): continue
        rows = [l.rstrip('\n').split('\t') for l in open(p, encoding='utf-8')]
        with open(p, 'w', encoding='utf-8') as f:
            for r in rows:
                line_no, word_no = int(r[0]), int(r[1])
                la, lb, n_head = meta[order[line_no]]
                lang, since = (la, -1) if word_no < n_head else (lb, word_no - n_head)
                f.write('\t'.join(r[:5] + [lang, str(since)]) + '\n')
    print(json.dumps({'sentences': len(lines), 'union_words': len(seen)}))


if __name__ == '__main__':
    main()
