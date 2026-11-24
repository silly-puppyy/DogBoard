"""Next-word benchmark sets for other languages, as the English ones (predbench human.txt: lowercased sentences,
one per line, 200 of them), for the teacher bake-off against Apple / Gboard (private study; 
"assemble a few benchmarks like we did for English").
  human-<lang>.txt  sentences from WildChat-1M user turns in that language (ODC-By; not redacted, not toxic),
                    5-25 words, no links / code / digits-heavy lines, lowercased, deduplicated
  news-<lang>.txt   held-out Leipzig sentences of the language pack (never used for its n-grams), lowercased
Every chosen line is also written to exclude-<lang>.txt: student training text must drop these lines.
Usage: make_lang_predbench.py WILDCHAT_DIR PACKS_WORK_DIR OUT_DIR"""
import os, re, sys, glob, random, unicodedata
import pyarrow.parquet as pq

WILD, PACKS, OUT = sys.argv[1:4]
LANGS = {'es': 'Spanish', 'fr': 'French', 'ru': 'Russian', 'fi': 'Finnish'}
# PREDBENCH_LANGS="de:German,it:Italian": build only these (the first four's sets stay as they are)
if os.environ.get('PREDBENCH_LANGS'):
    LANGS = dict(x.split(':', 1) for x in os.environ['PREDBENCH_LANGS'].split(','))
N = 200
SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')
BAD = re.compile(r'https?://|www\.|[{}<>=_`#@\\|]|\d{3,}')
os.makedirs(OUT, exist_ok=True)


def letters_share(s):
    letters = sum(1 for c in s if c.isalpha())
    return letters / max(1, len(s.replace(' ', '')))


def clean(s):
    s = unicodedata.normalize('NFC', s.strip().strip('"«»“”'))
    if BAD.search(s) or letters_share(s) < 0.85: return None
    n = len(s.split())
    return s.lower() if 5 <= n <= 25 else None


pools = {l: [] for l in LANGS}
for f in sorted(glob.glob(os.path.join(WILD, '*.parquet'))):
    t = pq.read_table(f, columns=['language', 'conversation', 'toxic', 'redacted'])
    for lang_name, conv, toxic, redacted in zip(*[t.column(c).to_pylist() for c in ('language', 'conversation', 'toxic', 'redacted')]):
        lang = next((k for k, v in LANGS.items() if v == lang_name), None)
        if not lang or toxic or redacted: continue
        for turn in conv:
            if turn['role'] != 'user' or turn['language'] != lang_name: continue
            for sent in SPLIT.split(turn['content'] or ''):
                c = clean(sent)
                if c: pools[lang].append(c)
    print(os.path.basename(f), {l: len(p) for l, p in pools.items()}, flush=True)

rnd = random.Random(11)
for lang in LANGS:
    uniq = sorted(set(pools[lang])); rnd.shuffle(uniq)
    human = uniq[:N]
    held = os.path.join(PACKS, lang, f'{lang}.heldout.txt')
    news = []
    if os.path.exists(held):
        lines = [l.strip() for l in open(held, encoding='utf-8')]
        rnd.shuffle(lines)
        news = [c for c in (clean(l) for l in lines[:20000]) if c][:N]
    for name, rows in (('human', human), ('news', news)):
        if rows: open(os.path.join(OUT, f'{name}-{lang}.txt'), 'w', encoding='utf-8').write('\n'.join(rows) + '\n')
    open(os.path.join(OUT, f'exclude-{lang}.txt'), 'w', encoding='utf-8').write('\n'.join(human + news) + '\n')
    print(lang, 'human pool', len(uniq), '-> human', len(human), 'news', len(news))
