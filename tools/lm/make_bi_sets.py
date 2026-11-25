"""Test sets and exclusions for the English + Spanish bilingual recipes (notes/research/bilingual-recipes.md, private study):
  news-en.txt        200 lowercased 5-25 word sentences of the English pack's held-out Leipzig lines (as make_lang_predbench.py's news sets)
  codeswitch.txt     real code-switching: WildChat user turns labelled English or Spanish with >= 3 words only English's list
                     has and >= 3 only Spanish's (make_codeswitch_set.py's test), lowercased, first 30 words, one per line;
                     codeswitch.lang = the turn's WildChat language label (the language token for the model)
  mixed.txt          synthetic switching on held-out lines (bilingual_switch.py: 60 % insertions, 40 % clause alternations);
                     mixed.lang = the matrix language
  exclude-en.txt     every sentence of the English test sets, for the English training text
  exclude-cs.txt     every sentence (lowercased) of every qualifying code-switching turn: training text drops these lines
Usage: make_bi_sets.py OUT_DIR"""
import os, re, sys, glob, random, unicodedata
import pyarrow.parquet as pq
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, '..', 'i18n'))
import bilingual_switch as bs
from make_codeswitch_set import words_of, TOKEN

D = bs.D
PB = '/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench'
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')
BAD = re.compile(r'https?://|www\.|[{}<>=_`#@\\|]|\d{3,}')


def clean(s):
    s = unicodedata.normalize('NFC', s.strip().strip('"«»“”'))
    letters = sum(1 for c in s if c.isalpha())
    if BAD.search(s) or letters / max(1, len(s.replace(' ', ''))) < 0.85: return None
    return s.lower() if 5 <= len(s.split()) <= 25 else None


rnd = random.Random(11)
held = [l.strip() for l in open(f'{D}/packs-work/en-sim/en.heldout.txt', encoding='utf-8')]
rnd.shuffle(held)
news = [c for c in (clean(l) for l in held[:20000]) if c][:200]
open(f'{OUT}/news-en.txt', 'w').write('\n'.join(news) + '\n')
excl = set(news)
for f in ('human.txt', 'human-noisy.txt', 'chat.txt'):
    excl |= {l.strip().lower() for l in open(f'{PB}/{f}', encoding='utf-8')}
open(f'{OUT}/exclude-en.txt', 'w').write('\n'.join(sorted(excl)) + '\n')

# real code-switching
en_all, es_all = words_of(bs.EN_LEX), words_of(bs.ES_LEX)
only_en = words_of(bs.EN_LEX, 40000) - es_all; only_es = words_of(bs.ES_LEX, 40000) - en_all
msgs, cs_excl = [], set()
for f in sorted(glob.glob(f'{D}/hf/wildchat-1m/*.parquet')):
    for batch in pq.ParquetFile(f).iter_batches(columns=['conversation', 'toxic', 'redacted'], batch_size=1000):
        for conv, toxic, red in zip(*[batch.column(i).to_pylist() for i in range(3)]):
            if toxic or red: continue
            for turn in conv or []:
                if turn.get('role') != 'user' or turn.get('language') not in ('English', 'Spanish'): continue
                text = turn.get('content') or ''
                ws = [m.group(1).replace('’', "'").lower() for t in text.split() if (m := TOKEN.match(t))]
                if not 6 <= len(text.split()) <= 200: continue
                if sum(w in only_en for w in ws) < 3 or sum(w in only_es for w in ws) < 3: continue
                for s in SPLIT.split(text):
                    if s.strip(): cs_excl.add(s.strip().lower())
                one = ' '.join(text.split()[:30]).lower()
                if not BAD.search(one): msgs.append((one, turn['language']))
    print(os.path.basename(f), len(msgs), flush=True)
rnd.shuffle(msgs); msgs = list(dict.fromkeys(msgs))[:300]
open(f'{OUT}/codeswitch.txt', 'w').write('\n'.join(m for m, _ in msgs) + '\n')
open(f'{OUT}/codeswitch.lang', 'w').write('\n'.join('en' if l == 'English' else 'es' for _, l in msgs) + '\n')
open(f'{OUT}/exclude-cs.txt', 'w').write('\n'.join(sorted(cs_excl)) + '\n')
print('codeswitch', len(msgs), 'excluded sentences', len(cs_excl))

# synthetic switching on held-out lines (not the ones that built the training text: held-out by construction)
tb = bs.Tables()
src = {'en': [l for l in held if 8 <= len(l.split()) <= 25 and not BAD.search(l)][:3000],
       'es': [l.strip() for l in open(f'{D}/packs-work/es/es.heldout.txt', encoding='utf-8') if 8 <= len(l.split()) <= 25][:3000]}
rnd2 = random.Random(13); rows = []
for i in range(2000):
    lm = 'en' if i % 2 == 0 else 'es'; lo = 'es' if lm == 'en' else 'en'
    a, b = rnd2.choice(src[lm]), rnd2.choice(src[lo])
    out = bs.insert(tb, rnd2, a, lm) if rnd2.random() < 0.6 else bs.alternate(rnd2, a, b)
    if out and out != a: rows.append((out.lower(), lm))
rows = rows[:200]
open(f'{OUT}/mixed.txt', 'w').write('\n'.join(r for r, _ in rows) + '\n')
open(f'{OUT}/mixed.lang', 'w').write('\n'.join(l for _, l in rows) + '\n')
print('mixed', len(rows), 'translations', len(tb.table['es']), len(tb.table['en']))
