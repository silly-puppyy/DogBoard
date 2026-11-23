"""Student training text for one language (private study; the Russian recipe, make_ru_lm_text.py, for any
language): Leipzig 1M-sentence corpora (CC BY) minus every line of the pack's held-out file (the test sets come from
it), plus WildChat-1M user sentences in that language (ODC-By) minus the next-word benchmark lines
(predbench/lang/exclude-<lang>.txt). 20,000 random lines become val.txt.
Usage: make_lang_lm_text.py WILDCHAT_LANGUAGE LEIPZIG_DIR CORPORA(comma-separated) WILDCHAT_DIR HELDOUT EXCLUDE OUT_DIR
       e.g. make_lang_lm_text.py Spanish .../leipzig spa_news_2024_1M,spa_news_2023_1M,spa_wikipedia_2021_1M ...
Writes OUT_DIR/{train.txt, val.txt}."""
import os, re, sys, glob, random
import pyarrow.parquet as pq

NAME, LEIPZIG, CORPORA, WILD, HELDOUT, EXCLUDE, OUT = sys.argv[1:8]
os.makedirs(OUT, exist_ok=True)
held = {l.strip() for l in open(HELDOUT, encoding='utf-8')}
excl = {l.strip().lower() for l in open(EXCLUDE, encoding='utf-8')}
lines = []
for d in CORPORA.split(','):
    for l in open(glob.glob(os.path.join(LEIPZIG, d, '*-sentences.txt'))[0], encoding='utf-8'):
        s = l.rstrip('\n').split('\t', 1)[-1].strip()
        if s and s not in held: lines.append(s)
print('leipzig', len(lines), flush=True)
SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')
n_wild = 0
for f in sorted(glob.glob(os.path.join(WILD, '*.parquet'))):
    t = pq.read_table(f, columns=['language', 'conversation', 'toxic', 'redacted'])
    for lang, conv, toxic, red in zip(*[t.column(c).to_pylist() for c in ('language', 'conversation', 'toxic', 'redacted')]):
        if lang != NAME or toxic or red: continue
        for turn in conv:
            if turn['role'] != 'user' or turn['language'] != NAME: continue
            for s in SPLIT.split(turn['content'] or ''):
                s = s.strip()
                if 3 <= len(s.split()) <= 60 and s.lower() not in excl: lines.append(s); n_wild += 1
print('wildchat', n_wild, flush=True)
random.Random(3).shuffle(lines)
open(os.path.join(OUT, 'val.txt'), 'w', encoding='utf-8').write('\n'.join(lines[:20000]) + '\n')
open(os.path.join(OUT, 'train.txt'), 'w', encoding='utf-8').write('\n'.join(lines[20000:]) + '\n')
print('train', len(lines) - 20000, 'val 20000')
