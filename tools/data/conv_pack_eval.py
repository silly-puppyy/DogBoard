#!/usr/bin/env python3
"""Next-word top-1 / top-3 of two packs' KenLM models (old text vs old text + conversational slice) on the same held-out sets.

    python3 -I conv_pack_eval.py LANG OLD_DIR NEW_DIR OUT_DIR

OLD_DIR / NEW_DIR hold train.txt and <lang>.arpa (auto_pack.py's folders); OUT_DIR gets test-<set>.txt and eval.txt. Sets
(pack tokens, 2 to 40 words, never a line of either training text): news = random sentences of NEW_DIR/<lang>.heldout.txt
(Leipzig ids ending in 0), tatoeba = sentences with ids ending in 0, wildchat / oasst2 / aya = local user-turn sets
(conv-sources/local), at most 6,000 each. Scoring: conv_pilot_eval.py (exact back-off ranking over the ARPA files)."""
import os, random, subprocess, sys
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here); sys.path.insert(0, os.path.join(here, '..', 'i18n'))
from leipzig_sentences import WORD, SENTENCE_END, lower
import auto_pack

lang, old, new, out = sys.argv[1:5]
os.makedirs(out, exist_ok=True)
D = auto_pack.DATA
letters = {c for c in auto_pack.letters_of(lang) if c.islower() or not c.isupper()} | {"'"}
toks = lambda s: [lower(w, lang).replace('’', "'") for w in WORD.findall(s)]
rng = random.Random(7)

def lines_of(text):
    for part in SENTENCE_END.split(text):
        w = toks(part)
        if w: yield w

raw = {}
raw['news'] = [l.rstrip('\n') for l in open(f'{new}/{lang}.heldout.txt', encoding='utf-8', newline='\n')]
tat = f'{auto_pack.TATOEBA}/{auto_pack.LANGS[lang]["tatoeba"]}_sentences.tsv'
if os.path.exists(tat):
    raw['tatoeba'] = [t[-1] for t in (l.rstrip('\n').split('\t') for l in open(tat, encoding='utf-8', newline='\n')) if len(t) >= 3 and t[0].endswith('0')]
for src in ('wildchat', 'oasst2', 'aya-in'):
    f = f'{D}/conv-sources/local/{src}-{lang}.tsv'
    if os.path.exists(f): raw[src] = [l.rstrip('\n').partition('\t')[2] for l in open(f, encoding='utf-8', newline='\n')]

train = set(); 
for d in (old, new):
    for l in open(f'{d}/train.txt', encoding='utf-8', newline='\n'): train.add(l.rstrip('\n'))
sets = []
for name, ss in raw.items():
    rng.shuffle(ss); got = []; dup = 0
    for s in ss:
        for w in lines_of(s):
            if not 2 <= len(w) <= 40: continue
            if name != 'news' and not set(''.join(w)) <= letters: continue
            line = ' '.join(w)
            if line in train: dup += 1; continue
            got.append(line)
        if len(got) >= 6000: break
    if len(got) < 300: print('skip', name, len(got)); continue
    open(f'{out}/test-{name}.txt', 'w', encoding='utf-8').write('\n'.join(got) + '\n')
    sets += ['--set', f'{name}={out}/test-{name}.txt']; print('set', name, len(got), 'lines;', dup, 'left out (also in a training text)', flush=True)
r = subprocess.run(['taskset', '-c', os.environ.get('PACK_CPUS', '8-13'), 'nice', sys.executable, '-I', f'{here}/conv_pilot_eval.py'] + sets + [f'{old}/{lang}.arpa', f'{new}/{lang}.arpa'],
                   capture_output=True, text=True)
open(f'{out}/eval.txt', 'w').write(r.stdout + r.stderr)
print(r.stdout, r.stderr[-500:])
