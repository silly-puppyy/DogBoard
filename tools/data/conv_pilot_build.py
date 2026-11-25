#!/usr/bin/env python3
"""Builds the KenLM training texts and the held-out sets of the conversational-source pilot (2026-11-25).

    python3 -I conv_pilot_build.py DATA_DIR LANG

Reads  DATA_DIR/packs-work/LANG/train.txt (or rebuilds it from the Leipzig corpora when missing: es), the FineWeb-2
slices DATA_DIR/conv-sources/pilot/LANG/{chatty,control}.txt (conv_fw2_slice.py), the local chat sets and Tatoeba.
Writes DATA_DIR/conv-sources/pilot/LANG/:
  test-<set>.txt   held-out sentences as pack tokens (never in any training text: exact-line overlap removed everywhere)
  A.txt            the pack text (news + Wikipedia [+ web])
  B-<share>.txt    (1-share) x |A| random lines of A + share x |A| chatty lines  (same total size as A)
  C-<share>.txt    the same with random FineWeb-2 lines (control: is it the filter or just more web?)
  D.txt            A + chatty lines (share x |A| / (1-share) more lines: the add-on variant)"""
import glob, os, random, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from conv_common import WORD, lower, SPLIT

LET = {'bg': set('абвгдежзийклмнопрстуфхцчшщъьюяѝ'), 'es': set('abcdefghijklmnopqrstuvwxyzáéíóúüñ')}
ISO3 = {'bg': 'bul', 'es': 'spa'}
SHARE = 0.4


def toks(s, lang):
    return [lower(w, lang).replace('’', "'") for w in WORD.findall(s)]


def ok_line(words, lang):
    return len(words) >= 2 and all(set(w) <= LET[lang] | {"'"} for w in words) 


def read_local(path):
    out = {}
    for l in open(path, encoding='utf-8', newline='\n'):
        tid, _, s = l.rstrip('\n').partition('\t')
        if s: out.setdefault(tid, []).append(s)
    return out


def main():
    data, lang = sys.argv[1:3]
    d = f'{data}/conv-sources/pilot/{lang}'
    rng = random.Random(7)
    # --- held-out human-like sets (raw sentences -> pack tokens)
    sets = {}
    tat = [l.rstrip('\n').split('\t') for l in open(f'{data}/tatoeba/{ISO3[lang]}_sentences.tsv', encoding='utf-8', newline='\n')]
    sets['tatoeba'] = [t[-1] for t in tat if len(t) >= 3 and t[0].endswith('0')]      # ids ending in 0: as every pack's test sets
    for src in ('wildchat', 'oasst2', 'aya-in'):
        f = f'{data}/conv-sources/local/{src}-{lang}.tsv'
        if os.path.exists(f):
            # whole turns split off by turn id: no turn is in two places; every sentence kept (they are test only)
            sets[src] = [s for ss in read_local(f).values() for s in ss]
    # news held-out of the pack (Leipzig ids ending in 0)
    held = f'{data}/packs-work/{lang}/{lang}.heldout.txt'
    sets['news'] = [l.rstrip('\n') for l in open(held, encoding='utf-8', newline='\n')]
    test = {}; test_lines = set()
    for name, ss in sets.items():
        rng.shuffle(ss)
        out = []
        for s in ss:
            w = toks(s, lang)
            if 2 <= len(w) <= 40 and (name == 'news' or set(''.join(w)) <= LET[lang] | {"'"}):
                out.append(' '.join(w))
            if len(out) >= 6000: break
        test[name] = out; test_lines.update(out)
        open(f'{d}/test-{name}.txt', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
        print('test', name, len(out), flush=True)
    # --- training texts
    def clean(lines):
        n = 0
        for l in lines:
            l = l.rstrip('\n')
            w = l.split()
            if ok_line(w, lang) and l not in test_lines: n += 1; yield l
    a_path = f'{data}/packs-work/{lang}/train.txt'
    if not os.path.exists(a_path):
        files = [glob.glob(f'{data}/leipzig/{c}/*-sentences.txt')[0] for c in ('spa_news_2024_1M', 'spa_web_2016_1M', 'spa_wikipedia_2021_1M')]
        a_path = f'{d}/A-raw.txt'
        with open(a_path, 'w', encoding='utf-8') as o:
            subprocess.run([sys.executable, '-I', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'leipzig_sentences.py'),
                            '--heldout', f'{d}/A-heldout.txt', '--lang', lang] + files, stdout=o, check=True)
    A = list(clean(open(a_path, encoding='utf-8', newline='\n')))
    chatty = list(clean(open(f'{d}/chatty.txt', encoding='utf-8', newline='\n')))
    control = list(clean(open(f'{d}/control.txt', encoding='utf-8', newline='\n')))
    rng.shuffle(chatty); rng.shuffle(control); rng.shuffle(A)
    n = len(A); print('A', n, 'chatty', len(chatty), 'control', len(control))
    for share in (0.3, 0.4, 0.5):
        k = int(share * n)
        if len(chatty) < k or len(control) < k: print('not enough conversational lines for share', share); continue
        for name, src in (('B', chatty), ('C', control)):
            with open(f'{d}/{name}-{int(share * 100)}.txt', 'w', encoding='utf-8') as o:
                o.write('\n'.join(A[:n - k] + src[:k]) + '\n')
    k = int(SHARE / (1 - SHARE) * n)
    with open(f'{d}/D.txt', 'w', encoding='utf-8') as o: o.write('\n'.join(A + chatty[:k]) + '\n')
    with open(f'{d}/A.txt', 'w', encoding='utf-8') as o: o.write('\n'.join(A) + '\n')


main()
