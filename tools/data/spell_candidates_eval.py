#!/usr/bin/env python3
"""Key-independent spelling candidates, measured: for a typed word the lexicon lacks, the dictionary words within two
letter edits (insert, delete, substitute, swap neighbours; a letter moved two places is two edits: "rethorical" ->
"rhetorical"), most frequent first within the fewest edits. Gboard offers such chips when the cursor returns into an
unknown word (emulator, 2026-11-25: rethorical -> rhetorical / historical / electrical); our touch decoder never
reaches words whose letters are out of order.

    tools/data/spell_candidates_eval.py LEXICON.tsv TYPOS.tsv [N_LEXICON=100000] [N_CASES=3000]
TYPOS.tsv: sentence, index, context, typed, intended (the predbench sets). Cases: typed != intended and typed not a
word; sampled with seed 7. Prints how often the intended word is first / in the top 3, by edit distance."""
import random, sys
from collections import Counter, defaultdict

lex_path, typos_path = sys.argv[1], sys.argv[2]
n_lex = int(sys.argv[3]) if len(sys.argv) > 3 else 100000
n_cases = int(sys.argv[4]) if len(sys.argv) > 4 else 3000

freq = {}
for line in open(lex_path, encoding='utf-8'):
    if line.startswith('#'): continue
    w, _, n = line.rstrip('\n').partition('\t')
    w = w.lower()
    if w.isalpha(): freq[w] = freq.get(w, 0) + int(n or 0)
known = set(freq)
top = [w for w, _ in sorted(freq.items(), key=lambda x: -x[1])[:n_lex]]


def deletes(w, d=2):
    out, edge = {w}, {w}
    for _ in range(d):
        edge = {e[:i] + e[i + 1:] for e in edge for i in range(len(e))}
        out |= edge
    return out


index = defaultdict(list)
for w in top:
    for k in deletes(w): index[k].append(w)


def osa(a, b):
    """Edits between a and b: insert, delete, substitute, swap neighbours (optimal string alignment)."""
    d = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i in range(len(a) + 1): d[i][0] = i
    for j in range(len(b) + 1): d[0][j] = j
    for i in range(1, len(a) + 1):
        for j in range(1, len(b) + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] != b[j - 1]))
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    return d[-1][-1]


def candidates(t, k=3):
    seen = {w for key in deletes(t) for w in index.get(key, ())}
    scored = [(osa(t, w), -freq[w], w) for w in seen]
    return [w for dist, _, w in sorted(s for s in scored if s[0] <= 2)[:k]]


cases = []
for line in open(typos_path, encoding='utf-8'):
    r = line.rstrip('\n').split('\t')
    if len(r) < 5: continue
    t, want = r[3].lower(), r[4].lower()
    if t != want and t.isalpha() and want.isalpha() and t not in known and want in known: cases.append((t, want))
random.Random(7).shuffle(cases)
cases = cases[:n_cases]

by = Counter(); first = Counter(); top3 = Counter()
for t, want in cases:
    d = min(osa(t, want), 3)
    c = candidates(t)
    by[d] += 1
    if c[:1] == [want]: first[d] += 1
    if want in c: top3[d] += 1
total = len(cases)
print(f'{total} typed non-words (intended word in the lexicon), lexicon top {n_lex:,}')
for d in (1, 2, 3):
    lab = {1: '1 edit', 2: '2 edits', 3: '3+ edits'}[d]
    if by[d]: print(f'  {lab}: {by[d]} ({100 * by[d] / total:.1f} %), intended first {100 * first[d] / by[d]:.1f} %, in top 3 {100 * top3[d] / by[d]:.1f} %')
print(f'  all: first {100 * sum(first.values()) / total:.1f} %, top 3 {100 * sum(top3.values()) / total:.1f} %')
for t in sys.argv[5:]: print(t, '->', candidates(t.lower()))
