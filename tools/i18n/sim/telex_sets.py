#!/usr/bin/env python3
"""Vietnamese test sets typed in Telex (notes/research/language-families.md 36): the held-out text and the word list are
spelled the Telex way (việt -> vieetj, được -> dduowcj; the pack's rules.json "spellings", as TypedSpellings does), the
usual typo simulator (make_es_sets.py) runs on that, and each case's context and target are mapped back to Vietnamese.
So a case is what a Telex typist's fingers produce (typos included) and the word they meant.

    telex_sets.py PACK_DIR OUT_DIR [N]      (PACK_DIR: vi.heldout.txt, vi.lexicon.tsv, rules.json)
"""
import json
import os
import re
import subprocess
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
WORD = re.compile(r"[^\W\d_]+")


def speller(rules):
    """TypedSpellings.spelled in Python: end marks (tones) off the letters, their keys appended; letters replaced."""
    sp = rules.get('spellings', [])
    letters = sorted([s for s in sp if s.get('letter') and s.get('typed')], key=lambda s: -len(s['letter']))
    marks = [s for s in sp if s.get('mark') and s.get('typed') and s.get('at') == 'end']

    def spelled(word):
        w = unicodedata.normalize('NFD', word.lower())
        suffix = ''
        for m in marks:
            if m['mark'] in w: w = w.replace(m['mark'], ''); suffix += m['typed']
        w = unicodedata.normalize('NFC', w)
        for l in letters: w = w.replace(l['letter'], l['typed'])
        return w + suffix
    return spelled


def main():
    pack, out = sys.argv[1], sys.argv[2]
    n = sys.argv[3] if len(sys.argv) > 3 else '2000'
    os.makedirs(out, exist_ok=True)
    rules = json.load(open(f'{pack}/rules.json', encoding='utf-8'))
    spelled = speller(rules)
    back = {}  # Telex spelling -> the most frequent word spelled so
    counts = {}
    with open(f'{out}/vi.lexicon.tsv', 'w', encoding='utf-8') as f:
        f.write('#dogboard-lexicon 1\n')
        for line in open(f'{pack}/vi.lexicon.tsv', encoding='utf-8'):
            if line.startswith('#'): continue
            parts = line.rstrip('\n').split('\t')
            w, c = parts[0], float(parts[1]) if len(parts) > 1 else 1.0
            t = spelled(w)
            if not t.isascii(): continue  # a letter Telex doesn't cover
            if c > counts.get(t, -1): counts[t] = c; back[t] = w
        for t, c in counts.items(): f.write(f'{t}\t{int(c)}\n')
    with open(f'{out}/vi.heldout.txt', 'w', encoding='utf-8') as f:
        for i, line in enumerate(open(f'{pack}/vi.heldout.txt', encoding='utf-8')):
            if i >= 200000: break
            f.write(WORD.sub(lambda m: spelled(m.group(0)), line.lower()))
    rows = ','.join(rules['letter_rows']['rows'])
    subprocess.run([sys.executable, '-I', f'{HERE}/make_es_sets.py', f'{out}/vi.heldout.txt', f'{out}/vi.lexicon.tsv',
                    f'{HERE}/typo_model7.json', out, n],
                   env=dict(os.environ, SIM_ROWS=rows, SIM_PREFIX='vi', SIM_LETTERS='abcdefghijklmnopqrstuvwxyz'),
                   cwd=HERE, check=True, stdout=subprocess.DEVNULL)
    # context and target back in Vietnamese; the typed text stays as typed
    for kind in ('typos', 'controls'):
        p = f'{out}/vi-{kind}.tsv'
        if not os.path.exists(p): continue
        rows_out = []
        for line in open(p, encoding='utf-8'):
            r = line.rstrip('\n').split('\t')
            if len(r) < 5: continue
            r[2] = ' '.join(back.get(w, w) for w in r[2].split(' '))
            if r[4] not in back: continue
            r[4] = back[r[4]]
            rows_out.append(r)
        with open(p, 'w', encoding='utf-8') as f:
            for r in rows_out: f.write('\t'.join(r) + '\n')
        print(kind, len(rows_out))


if __name__ == '__main__':
    main()
