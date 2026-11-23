"""A typo model learned from real phone typing (Aalto typing37k first attempts, private), to simulate
realistic typos in any language with its keyboard layout. From the typo pairs (typed vs intended,
within 2 edits) and the correct ones: the error rate by word length, the edit types (substitution,
omission, insertion, transposition; doubling / undoubling flagged), where in the word they fall, and
for substitutions and insertions the distance on the QWERTY layout between the key meant and the key
hit (in key widths, with row offsets), so the model carries over to other layouts.
Writes typo_model.json; prints a summary.
Usage: fit_typo_model.py aalto-words-clean.tsv typo_model.json"""
import sys, json, collections, math

ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']
OFFSET = [0.0, 0.25, 0.75]  # row stagger in key widths (phone QWERTY)
POS = {c: (i + OFFSET[r], float(r)) for r, row in enumerate(ROWS) for i, c in enumerate(row)}

def dist(a, b):
    if a not in POS or b not in POS: return None
    (x1, y1), (x2, y2) = POS[a], POS[b]
    return round(math.hypot(x1 - x2, y1 - y2), 1)

def ops(typed, want):
    """One optimal Damerau-Levenshtein edit script turning want into typed: (op, index in want, chars)."""
    n, m = len(want), len(typed)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1): d[i][0] = i
    for j in range(m + 1): d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (want[i - 1] != typed[j - 1]))
            if i > 1 and j > 1 and want[i - 1] == typed[j - 2] and want[i - 2] == typed[j - 1]:
                d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    out, i, j = [], n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and want[i - 1] == typed[j - 1] and d[i][j] == d[i - 1][j - 1]: i, j = i - 1, j - 1; continue
        if i > 1 and j > 1 and want[i - 1] == typed[j - 2] and want[i - 2] == typed[j - 1] and d[i][j] == d[i - 2][j - 2] + 1:
            out.append(('transpose', i - 2, want[i - 2:i])); i, j = i - 2, j - 2; continue
        if i > 0 and j > 0 and d[i][j] == d[i - 1][j - 1] + 1:
            out.append(('substitute', i - 1, want[i - 1] + typed[j - 1])); i, j = i - 1, j - 1; continue
        if i > 0 and d[i][j] == d[i - 1][j] + 1:
            out.append(('omit', i - 1, want[i - 1])); i -= 1; continue
        out.append(('insert', i, typed[j - 1])); j -= 1
    return out

pairs = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8')]
by_len = collections.defaultdict(lambda: [0, 0])  # length -> [words, wrong]
op_count = collections.Counter(); where = collections.Counter(); sub_d = collections.Counter(); ins_d = collections.Counter()
double = collections.Counter(); n_ops = collections.Counter()
for _, _, _, typed, want in pairs:
    t, w = typed.lower(), want.lower()
    L = min(len(w), 12)
    by_len[L][0] += 1
    if t == w: continue
    by_len[L][1] += 1
    script = ops(t, w); n_ops[len(script)] += 1
    for op, i, ch in script:
        op_count[op] += 1
        rel = 'first' if i == 0 else 'last' if i >= len(w) - 1 else 'middle'
        where[(op, rel)] += 1
        if op == 'substitute' and dist(ch[0], ch[1]) is not None: sub_d[dist(ch[0], ch[1])] += 1
        if op == 'insert':
            near = [dist(ch, w[k]) for k in (i - 1, i) if 0 <= k < len(w) and dist(ch, w[k]) is not None]
            if near: ins_d[min(near)] += 1
            if 0 < i <= len(w) and w[i - 1] == ch: double['doubled'] += 1
        if op == 'omit' and ((i > 0 and w[i - 1] == ch) or (i + 1 < len(w) and w[i + 1] == ch)): double['undoubled'] += 1

total_ops = sum(op_count.values())
model = {
    'source': 'Aalto typing37k (Palin et al. 2019) first attempts on phones, no autocorrect; private fit',
    'layout': {'rows': ROWS, 'offset': OFFSET},
    'error_rate_by_length': {L: by_len[L][1] / by_len[L][0] for L in sorted(by_len) if by_len[L][0] >= 200},
    'edits_per_typo': {k: v / sum(n_ops.values()) for k, v in sorted(n_ops.items())},
    'op_share': {k: v / total_ops for k, v in op_count.items()},
    'op_position': {f'{op}/{rel}': v / op_count[op] for (op, rel), v in where.items()},
    'substitution_distance': {str(k): v / sum(sub_d.values()) for k, v in sorted(sub_d.items())},
    'insertion_distance': {str(k): v / sum(ins_d.values()) for k, v in sorted(ins_d.items())},
    'doubling': {k: v / total_ops for k, v in double.items()},
}
json.dump(model, open(sys.argv[2], 'w'), indent=1)
print(f'{sum(v[0] for v in by_len.values()):,} words, {sum(v[1] for v in by_len.values()):,} typos, {total_ops:,} edits')
print('error rate by length:', {L: f'{100 * r:.1f}%' for L, r in model['error_rate_by_length'].items()})
print('edits per typo:', {k: f'{100 * v:.1f}%' for k, v in model['edits_per_typo'].items()})
print('edit types:', {k: f'{100 * v:.1f}%' for k, v in sorted(model['op_share'].items(), key=lambda x: -x[1])})
print('position:', {k: f'{100 * v:.0f}%' for k, v in sorted(model['op_position'].items())})
print('substitution key distance:', {k: f'{100 * v:.1f}%' for k, v in list(model['substitution_distance'].items())[:10]})
print('insertion distance to a neighbouring letter:', {k: f'{100 * v:.1f}%' for k, v in list(model['insertion_distance'].items())[:8]})
print('doubling:', {k: f'{100 * v:.1f}% of edits' for k, v in model['doubling'].items()})
