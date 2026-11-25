"""English training text for the bilingual recipes (private study), matching make_lang_lm_text.py's Spanish: Leipzig
English corpora (news 2024, web 2018, Wikipedia 2016; CC BY) minus every line of the English pack's held-out file, plus WildChat-1M
English user sentences (3-60 words; ODC-By; not toxic / redacted; a random 300,000 of them, as Spanish has far fewer) minus the
English test sentences (exclude-en.txt) and the code-switching test turns (exclude-cs.txt). 20,000 random lines become val.txt.
The Spanish text (lm-text/es) gets the same code-switching exclusion; its other sources are unchanged.
Usage: make_bi_text.py SETS_DIR OUT_ROOT     writes OUT_ROOT/en-es-en/{train,val}.txt and OUT_ROOT/en-es-es/{train,val}.txt"""
import os, re, sys, glob, random
import pyarrow.parquet as pq

SETS, ROOT = sys.argv[1:3]
D = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data'
held = {l.strip() for l in open(f'{D}/packs-work/en-sim/en.heldout.txt', encoding='utf-8')}
excl = {l.strip() for l in open(f'{SETS}/exclude-en.txt', encoding='utf-8')} | {l.strip() for l in open(f'{SETS}/exclude-cs.txt', encoding='utf-8')}
lines = []
for d in ('eng_news_2024_1M', 'eng-com_web-public_2018_1M', 'eng_wikipedia_2016_1M'):
    for l in open(glob.glob(f'{D}/leipzig/{d}/*-sentences.txt')[0], encoding='utf-8'):
        s = l.rstrip('\n').split('\t', 1)[-1].strip()
        if s and s not in held and s.lower() not in excl: lines.append(s)
print('leipzig', len(lines), flush=True)
SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')
rnd = random.Random(3); wild = []; seen = 0; K = 300_000
for f in sorted(glob.glob(f'{D}/hf/wildchat-1m/*.parquet')):
    for b in pq.ParquetFile(f).iter_batches(columns=['conversation', 'toxic', 'redacted', 'language'], batch_size=1000):
        for conv, toxic, red, lang in zip(*[b.column(i).to_pylist() for i in range(4)]):
            if lang != 'English' or toxic or red: continue
            for turn in conv:
                if turn['role'] != 'user' or turn['language'] != 'English': continue
                for s in SPLIT.split(turn['content'] or ''):
                    s = s.strip()
                    if 3 <= len(s.split()) <= 60 and s.lower() not in excl:  # reservoir sample of K
                        seen += 1
                        if len(wild) < K: wild.append(s)
                        else:
                            j = rnd.randrange(seen)
                            if j < K: wild[j] = s
    print(os.path.basename(f), seen, flush=True)
lines += wild
rnd.shuffle(lines)
os.makedirs(f'{ROOT}/en-es-en', exist_ok=True)
open(f'{ROOT}/en-es-en/val.txt', 'w', encoding='utf-8').write('\n'.join(lines[:20000]) + '\n')
open(f'{ROOT}/en-es-en/train.txt', 'w', encoding='utf-8').write('\n'.join(lines[20000:]) + '\n')
print('en train', len(lines) - 20000, flush=True)
# Spanish: the existing text minus the code-switching test sentences
cs = {l.strip() for l in open(f'{SETS}/exclude-cs.txt', encoding='utf-8')}
os.makedirs(f'{ROOT}/en-es-es', exist_ok=True)
for split in ('train', 'val'):
    n = k = 0
    with open(f'{ROOT}/es/{split}.txt', encoding='utf-8') as f, open(f'{ROOT}/en-es-es/{split}.txt', 'w', encoding='utf-8') as o:
        for l in f:
            n += 1
            if l.strip().lower() in cs: k += 1; continue
            o.write(l)
    print('es', split, n, 'dropped', k)
