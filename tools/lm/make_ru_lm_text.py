"""Russian training text for the student A/B (private study; user 2026-11-22: "Russian's LLM learning cases ... maybe
tokenize the case endings differently on purpose"): Leipzig's three Russian 1M-sentence corpora (news 2024, web 2019,
Wikipedia 2021; CC BY) minus every line of the pack's held-out file (ru.heldout.txt: the case test set and the
next-word sets come from it), plus WildChat-1M Russian user sentences (ODC-By) minus the next-word benchmark lines
(predbench/lang/exclude-ru.txt). 20,000 random lines become val.txt.
Writes lm-text/ru/{train.txt, val.txt}.
Usage: make_ru_lm_text.py LEIPZIG_DIR WILDCHAT_DIR HELDOUT EXCLUDE OUT_DIR"""
import os, re, sys, glob, random
import pyarrow.parquet as pq

LEIPZIG, WILD, HELDOUT, EXCLUDE, OUT = sys.argv[1:6]
os.makedirs(OUT, exist_ok=True)
held = {l.strip() for l in open(HELDOUT, encoding='utf-8')}
excl = {l.strip().lower() for l in open(EXCLUDE, encoding='utf-8')}
lines = []
for d in ('rus_news_2024_1M', 'rus-ru_web-public_2019_1M', 'rus_wikipedia_2021_1M'):
    for l in open(glob.glob(os.path.join(LEIPZIG, d, '*-sentences.txt'))[0], encoding='utf-8'):
        s = l.rstrip('\n').split('\t', 1)[-1].strip()
        if s and s not in held: lines.append(s)
print('leipzig', len(lines), flush=True)
SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')
n_wild = 0
for f in sorted(glob.glob(os.path.join(WILD, '*.parquet'))):
    t = pq.read_table(f, columns=['language', 'conversation', 'toxic', 'redacted'])
    for lang, conv, toxic, red in zip(*[t.column(c).to_pylist() for c in ('language', 'conversation', 'toxic', 'redacted')]):
        if lang != 'Russian' or toxic or red: continue
        for turn in conv:
            if turn['role'] != 'user' or turn['language'] != 'Russian': continue
            for s in SPLIT.split(turn['content'] or ''):
                s = s.strip()
                if 3 <= len(s.split()) <= 60 and s.lower() not in excl: lines.append(s); n_wild += 1
print('wildchat', n_wild, flush=True)
random.Random(3).shuffle(lines)
open(os.path.join(OUT, 'val.txt'), 'w', encoding='utf-8').write('\n'.join(lines[:20000]) + '\n')
open(os.path.join(OUT, 'train.txt'), 'w', encoding='utf-8').write('\n'.join(lines[20000:]) + '\n')
print('train', len(lines) - 20000, 'val 20000')
