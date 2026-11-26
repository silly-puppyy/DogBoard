#!/usr/bin/env python3
"""Could the packs' trigram model flag agreement errors (blue underlines, "le maison")? Measured before any app code.

    python3 -I grammar_probe.py LANG.arpa LANG-agreement.tsv [lang]

AGREEMENT rows (packs-work/<lang>/<lang>-agreement.tsv): left context, the word written, its other forms (same lemma,
other gender / number / person, "|"), the next word, part of speech. A form is scored by its fit with the two words
before and the word after: log P(form | w-2 w-1) + log P(next | w-1 form), ARPA back-off. A word is flagged when another
form scores at least MARGIN (log10) better.
- false alarms: the written (correct) word flagged;
- detection: one wrong form put in its place, flagged with the written word as the best alternative.
Printed per margin and per part of speech."""
import collections, sys
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)) + '/../data')
from leipzig_sentences import WORD, lower

arpa, rows_path = sys.argv[1], sys.argv[2]
lang = sys.argv[3] if len(sys.argv) > 3 else ''
toks = lambda s: [lower(w, lang).replace('’', "'") for w in WORD.findall(s)]

# FAMILIES=rules LEXICON=<lang.lexicon.tsv>: the other forms come from closed determiner sets and adjective endings checked
# against the word list (what the phone can do without a tagger), for determiners and adjectives only
DETS = {
    'fr': [['le', 'la', 'les'], ['un', 'une', 'des'], ['ce', 'cet', 'cette', 'ces'], ['mon', 'ma', 'mes'], ['ton', 'ta', 'tes'],
           ['son', 'sa', 'ses'], ['notre', 'nos'], ['votre', 'vos'], ['leur', 'leurs'], ['du', 'des'], ['au', 'aux'],
           ['quel', 'quelle', 'quels', 'quelles'], ['aucun', 'aucune'], ['tout', 'toute', 'tous', 'toutes']],
    'es': [['el', 'la', 'los', 'las'], ['un', 'una', 'unos', 'unas'], ['este', 'esta', 'estos', 'estas'], ['ese', 'esa', 'esos', 'esas'],
           ['aquel', 'aquella', 'aquellos', 'aquellas'], ['mi', 'mis'], ['tu', 'tus'], ['su', 'sus'], ['nuestro', 'nuestra', 'nuestros', 'nuestras'],
           ['vuestro', 'vuestra', 'vuestros', 'vuestras'], ['del', 'de'], ['al'], ['otro', 'otra', 'otros', 'otras'],
           ['todo', 'toda', 'todos', 'todas'], ['mucho', 'mucha', 'muchos', 'muchas'], ['poco', 'poca', 'pocos', 'pocas']],
}
ENDINGS = {'fr': [('', 'e', 's', 'es'), ('é', 'ée', 'és', 'ées'), ('eux', 'euse', 'euses'), ('if', 'ive', 'ifs', 'ives'),
                  ('al', 'ale', 'aux', 'ales'), ('el', 'elle', 'els', 'elles'), ('en', 'enne', 'ens', 'ennes'), ('er', 'ère', 'ers', 'ères')],
           'es': [('o', 'a', 'os', 'as'), ('e', 'es'), ('', 'es'), ('or', 'ora', 'ores', 'oras'), ('z', 'ces')]}
import os
RULES = os.environ.get('FAMILIES') == 'rules'
LEX = {l.split('\t')[0].lower() for l in open(os.environ['LEXICON'], encoding='utf-8') if not l.startswith('#')} if RULES else set()


def family(w):
    for d in DETS.get(lang, []):
        if w in d: return [x for x in d if x != w]
    out = set()
    for ends in ENDINGS.get(lang, []):
        for e in ends:
            if e and not w.endswith(e) or (not e and False): continue
            stem = w[:len(w) - len(e)] if e else w
            if len(stem) < 2: continue
            for e2 in ends:
                f = stem + e2
                if f != w and f in LEX: out.add(f)
    return sorted(out)


rows = []
for l in open(rows_path, encoding='utf-8'):
    r = l.rstrip('\n').split('\t')
    if len(r) < 5: continue
    left = (['<s>', '<s>'] + toks(r[0]))[-2:]
    word, alts = lower(r[1], lang), [lower(a, lang) for a in r[2].split('|') if a]
    if RULES:
        if r[4] not in ('DET', 'ADJ'): continue
        given = alts; alts = family(word)
        # the corrupted form: one the dataset lists that the rules also produce (else no detection case)
        alts = sorted(alts, key=lambda a: a not in given)
    nxt = (toks(r[3]) or ['</s>'])[0]
    rows.append((left, word, alts, nxt, r[4]))
vocab = {w for left, word, alts, nxt, _ in rows for w in left + [word, nxt] + alts}

# only the n-grams over the words the rows use
prob, back = {}, {}
order = 0
for line in open(arpa, encoding='utf-8', errors='replace'):
    if line.startswith('\\') and line.strip().endswith('-grams:'): order = int(line[1]); continue
    if not order or not line.strip() or line.startswith('\\'): continue
    p = line.rstrip('\n').split('\t')
    if len(p) < 2: continue
    ws = tuple(p[1].split(' '))
    if len(ws) != order or not all(w in vocab for w in ws): continue
    prob[ws] = float(p[0])
    if len(p) > 2: back[ws] = float(p[2])
UNK = prob.get(('<unk>',), -7.0)


def lp(w, ctx):
    """log10 P(w | ctx) with ARPA back-off."""
    for k in range(len(ctx), -1, -1):
        c = tuple(ctx[len(ctx) - k:])
        if c + (w,) in prob:
            return prob[c + (w,)] + sum(back.get(tuple(ctx[len(ctx) - j:]), 0.0) for j in range(k + 1, len(ctx) + 1))
    return UNK + sum(back.get(tuple(ctx[len(ctx) - j:]), 0.0) for j in range(1, len(ctx) + 1))


def fit(left, form, nxt): return lp(form, left) + lp(nxt, [left[-1], form])


margins = (0.5, 1.0, 1.5, 2.0, 3.0)
fa = collections.Counter(); det = collections.Counter(); n = 0; npos = collections.Counter(); fapos = collections.Counter()
for left, word, alts, nxt, pos in rows:
    if not alts: continue
    n += 1; npos[pos] += 1
    s_word = fit(left, word, nxt)
    best_alt = max(fit(left, a, nxt) for a in alts)
    wrong = alts[0]; s_wrong = fit(left, wrong, nxt)
    others = [x for x in [word] + alts if x != wrong]
    best_other, s_best = max(((x, fit(left, x, nxt)) for x in others), key=lambda t: t[1])
    for m in margins:
        if best_alt - s_word >= m: fa[m] += 1; fapos[(m, pos)] += 1
        if s_best - s_wrong >= m and best_other == word: det[m] += 1
print(f'{n} rows ({dict(npos)})')
for m in margins:
    print(f'margin {m}: false alarms {100 * fa[m] / n:.1f} %, detection (right word suggested) {100 * det[m] / n:.1f} %   '
          + ' '.join(f'{p} FA {100 * fapos[(m, p)] / npos[p]:.1f}' for p in sorted(npos)))
