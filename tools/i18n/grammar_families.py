#!/usr/bin/env python3
"""Word families for the grammar check, mined from a pack's own data, no per-language lists (2026-11-26: the hand
lists in GrammarCheck.kt for fr es it pt don't scale to every pack).

- Endings: suffix swaps (up to 3 letters, the stem at least 3) that recur across at least MIN_STEMS different stems of the
  word list (fr "" <-> "e", "s" <-> "es"; es "o" <-> "a"): a word's family is the words its stem makes with them.
- Short words: the TOP frequent words of at most 6 letters, paired when 1-2 edits apart and found after the same words
  (cosine of their left-context counts in the pack's bigrams >= MIN_COS): le / la / les, un / une, ce / cette.

    grammar_families.py PACK_DIR LANG OUT.tsv      writes "word<TAB>form|form|..." """
import collections, math, sys

MIN_STEMS, TOP_WORDS, TOP_SHORT, MIN_COS, MIN_COUNT = 150, 60000, 300, 0.5, 1000
d, lang, out = sys.argv[1:4]
count = {}
for l in open(f'{d}/{lang}.lexicon.tsv', encoding='utf-8'):
    if l.startswith('#'): continue
    w, c = l.rstrip('\n').split('\t')[:2]
    w = w.lower()
    if w.isalpha(): count[w] = max(count.get(w, 0), int(c))
words = sorted(count, key=lambda w: -count[w])[:TOP_WORDS]
known = {w for w in words if count[w] >= MIN_COUNT}

# endings
by_stem = collections.defaultdict(list)
for w in words:
    for k in range(0, 4):
        if len(w) - k >= 3: by_stem[w[:len(w) - k]].append(w[len(w) - k:])
pair_stems = collections.Counter()
for stem, sufs in by_stem.items():
    sufs = sorted(set(sufs))
    for i in range(len(sufs)):
        for j in range(i + 1, len(sufs)): pair_stems[(sufs[i], sufs[j])] += 1
pairs = {p for p, n in pair_stems.items() if n >= MIN_STEMS}
alt = collections.defaultdict(set)
for a, b in pairs: alt[a].add(b); alt[b].add(a)
fam = collections.defaultdict(set)
for w in known:
    for k in range(0, 4):
        if len(w) - k < 3: continue
        stem, s = w[:len(w) - k], w[len(w) - k:]
        for s2 in alt.get(s, ()):
            f = stem + s2
            if f in known and f != w: fam[w].add(f)

# short words by edit distance and left contexts
def edits(a, b):
    p = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        q = [i]
        for j, cb in enumerate(b, 1): q.append(min(p[j] + 1, q[j - 1] + 1, p[j - 1] + (ca != cb)))
        p = q
    return p[-1]
short = [w for w in words if len(w) <= 6][:TOP_SHORT]
left = collections.defaultdict(collections.Counter)
S = set(short)
for l in open(f'{d}/{lang}.bigrams.tsv', encoding='utf-8'):
    if l.startswith('#'): continue
    a, b, c = l.rstrip('\n').split('\t')
    if b.lower() in S: left[b.lower()][a.lower()] += int(c)
def cos(a, b):
    x, y = left[a], left[b]
    num = sum(v * y[k] for k, v in x.items() if k in y)
    den = math.sqrt(sum(v * v for v in x.values()) * sum(v * v for v in y.values()))
    return num / den if den else 0.0
for i, a in enumerate(short):
    for b in short[i + 1:]:
        if edits(a, b) <= 2 and a[0] == b[0] and cos(a, b) >= MIN_COS: fam[a].add(b); fam[b].add(a)

with open(out, 'w', encoding='utf-8') as f:
    for w in sorted(fam): f.write(w + '\t' + '|'.join(sorted(fam[w])) + '\n')
print(lang, len(pairs), 'ending swaps,', len(fam), 'words with a family; e.g.',
      {w: sorted(fam.get(w, ()))[:6] for w in {'fr': ['le', 'un', 'ce', 'petit', 'grande', 'est'], 'es': ['el', 'la', 'un', 'este', 'rojo', 'casa'],
                                               'it': ['il', 'la', 'un', 'questo', 'rosso', 'casa'], 'pt': ['o', 'a', 'um', 'este', 'vermelho', 'casa']}.get(lang, [])})
