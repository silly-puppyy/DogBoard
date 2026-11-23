"""Simulated typos from the fitted model (fit_typo_model.py's typo_model.json, learned from real phone
typing), for any language: the keyboard layout comes as rows of keys (default QWERTY; e.g.
AZERTY "azertyuiop,qsdfghjklm,wxcvbn"), substitutions and insertions pick a key at a distance drawn
from the real distance distribution. Per word: an error with the real rate for its length; 1 or 2
edits; edit type and position (first / middle / last letter) as measured.
Two uses:
  sim_typos.py pairs typo_model.json real_pairs.tsv out.tsv  -> same intended words, simulated typed
     words (validation: run RealTypoEvalTest on both)
  sim_typos.py text typo_model.json in.txt out.txt [rows]    -> a text with simulated typos
Seeded (stable)."""
import sys, json, math, random, re

def load(path, rows=None):
    m = json.load(open(path))
    rows = rows.split(',') if rows else m['layout']['rows']
    offset = m['layout']['offset'] + [1.25] * max(0, len(rows) - 3)
    pos = {c: (i + offset[r], float(r)) for r, row in enumerate(rows) for i, c in enumerate(row)}
    return m, pos

def by_distance(pos, c, dist_table, rnd):
    """A key whose distance from c follows the measured distribution (nearest bin with keys)."""
    if c not in pos: return c
    bins = [(float(k), v) for k, v in dist_table.items() if float(k) > 0]
    r = rnd.random() * sum(v for _, v in bins); target = bins[-1][0]
    for d, v in bins:
        r -= v
        if r <= 0: target = d; break
    x, y = pos[c]
    keys = sorted((abs(math.hypot(x - pos[k][0], y - pos[k][1]) - target), k) for k in pos if k != c)
    best = keys[0][0]
    return rnd.choice([k for e, k in keys if e <= best + 0.15])

def typo(word, m, pos, rnd):
    w = list(word.lower())
    L = str(min(len(w), 12))
    rate = m['error_rate_by_length'].get(L, 0.0)
    if len(w) < 2 or rnd.random() >= rate: return word
    n = 1 if rnd.random() < m['edits_per_typo'].get('1', 0.82) else 2
    for _ in range(n):
        if len(w) < 2: break
        op = rnd.choices(list(m['op_share']), weights=list(m['op_share'].values()))[0]
        rels = {k.split('/')[1]: v for k, v in m['op_position'].items() if k.startswith(op + '/')}
        rel = rnd.choices(list(rels), weights=list(rels.values()))[0]
        i = 0 if rel == 'first' else len(w) - 1 if rel == 'last' else rnd.randrange(1, max(2, len(w) - 1))
        i = min(i, len(w) - 1)
        if op == 'substitute': w[i] = by_distance(pos, w[i], m['substitution_distance'], rnd)
        elif op == 'omit': del w[i]
        elif op == 'insert':
            src = w[i]; ch = by_distance(pos, src, m['insertion_distance'], rnd) if rnd.random() > m['insertion_distance'].get('0.0', 0) else src
            w.insert(i + (1 if rel == 'last' else 0), ch)
        elif op == 'transpose' and i + 1 < len(w): w[i], w[i + 1] = w[i + 1], w[i]
    out = ''.join(w)
    return out[0].upper() + out[1:] if word[:1].isupper() and out else out

if __name__ == '__main__':
    mode, model = sys.argv[1], sys.argv[2]
    m, pos = load(model, sys.argv[5] if len(sys.argv) > 5 else None)
    rnd = random.Random(17)
    if mode == 'pairs':
        with open(sys.argv[4], 'w', encoding='utf-8') as out:
            for line in open(sys.argv[3], encoding='utf-8'):
                s, i, ctx, typed, want = line.rstrip('\n').split('\t')
                out.write(f'{s}\t{i}\t{ctx}\t{typo(want, m, pos, rnd)}\t{want}\n')
    else:
        WORD = re.compile(r'([^A-Za-zÀ-ÿ]*)([A-Za-zÀ-ÿ]+)([^A-Za-zÀ-ÿ]*)')
        with open(sys.argv[4], 'w', encoding='utf-8') as out:
            for line in open(sys.argv[3], encoding='utf-8'):
                words = []
                for tok in line.split():
                    mm = WORD.fullmatch(tok)
                    words.append(mm.group(1) + typo(mm.group(2), m, pos, rnd) + mm.group(3) if mm else tok)
                out.write(' '.join(words) + '\n')
