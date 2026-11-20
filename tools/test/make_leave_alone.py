#!/usr/bin/env python3
""""Leave it alone" sets for RealTypoEvalTest (benchmark rework T4-1, 2026-11-20): correctly typed words that
autocorrect must not change, one file per category, typed == intended (the eval's "broken" share is the result).
Sources are public lists on the data drive (never the user's text):
  brands-known    one-word names of organisations / products with a website (Wikidata, CC0) in our lexicon
  brands-unknown  the same, not in our lexicon (the 'Gumroad' case)
  mixed-case      those with a capital inside (YouTube, PayPal, iPhone; the 'DiT' case)
  surnames        2010 US census surnames, title case (public domain)
  british         SCOWL british-only words, common sizes (the 'colour' case)

    tools/test/make_leave_alone.py <data dir> <lexicon.tsv> <out dir> [n=500]"""
import csv, os, random, sys

data, lexicon, out = sys.argv[1:4]
n = int(sys.argv[4]) if len(sys.argv) > 4 else 500
os.makedirs(out, exist_ok=True)
lex = set()
for line in open(lexicon, encoding='utf-8'):
    if not line.startswith('#') and line.strip(): lex.add(line.split('\t')[0].lower())
rnd = random.Random(3)


def letters(w):
    return w.isalpha() and w.isascii() and 2 <= len(w) <= 14


brands = [l.split('\t')[0] for l in open(f'{data}/wikidata2/en.tsv', encoding='utf-8') if not l.startswith('#')]
brands = [b for b in brands if letters(b)]
sets = {
    'brands-known': [b for b in brands if b.lower() in lex],
    'brands-unknown': [b for b in brands if b.lower() not in lex],
    'mixed-case': [b for b in brands if any(c.isupper() for c in b[1:]) and any(c.islower() for c in b)],
}
with open(f'{data}/census/surnames2010/Names_2010Census.csv', encoding='utf-8') as f:
    sets['surnames'] = [r['name'].title() for r in csv.DictReader(f) if letters(r.get('name', ''))][:20000]
british = []
final = f'{data}/scowl/scowl-2020.12.07/final'
for size in (10, 20, 35, 40, 50):
    path = f'{final}/british-words.{size}'
    if os.path.exists(path):
        british += [w.strip() for w in open(path, encoding='latin-1') if letters(w.strip()) and w.strip().islower()]
sets['british'] = british

for name, words in sets.items():
    words = list(dict.fromkeys(words))
    pick = rnd.sample(words, min(n, len(words)))
    with open(f'{out}/leave-{name}.tsv', 'w', encoding='utf-8') as f:
        for i, w in enumerate(pick):
            f.write(f'{name}-{i}\t0\t\t{w}\t{w}\n')
    print(f'{name}: {len(pick)} of {len(words)}')
