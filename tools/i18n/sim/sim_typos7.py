"""Typo simulator v7 (2026-11-20, after the held-out check validate_sim.py found sim6's misfits): everything is
fitted from the pairs it is given (no hand-set constants), so the same code fits any language's typing data.

Two kinds of typo, told apart in the data by whether a finger can explain them:
- MOTOR: the edit script uses only neighbouring keys (substitution or insertion within NEAR key widths, or a
  doubled letter), omissions and transpositions; the typed word may be a real word by accident ("our" -> "pur",
  "this" -> "is"). sim6 fitted its key distances and positions on non-words only, which dropped those accidents
  and so most first-letter typos of short words (real 20 % first letter changed vs sim6 10 %).
- SLIP: a real word a finger can't explain (a far jump: "at" -> "in", "be" -> "to", "show" -> "know"). sim6's
  slips had to keep the first two letters; v7's come from any frequent word within 2 edits (SymSpell deletes
  index), by the fitted kind mix, frequency^0.5 weighted.
Also fixed: a transposition at the last letter (or in a 2-letter word) swapped nothing.

    fit(rows, lexicon) -> model dict (rows: typed, intended)      Sim(model, lexicon).typo(word, rnd)"""
import collections, json, math, os, random, sys

HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, 'fit_typo_model.py')).read().split('\npairs = ')[0])  # ROWS, OFFSET, POS, dist, ops
from sim_typos import by_distance
from sim_typos2 import dl

def set_layout(rows, offset):
    """The layout the data was typed on (fit) / the distances are measured on: rows of keys and each row's indent in
    key widths (QWERTY default; Finnish: ['qwertyuiopå', 'asdfghjklöä', 'zxcvbnm'], [0, 0, 2])."""
    global ROWS, OFFSET, POS
    ROWS, OFFSET = list(rows), list(offset)
    POS = {c: (i + OFFSET[r], float(r)) for r, row in enumerate(ROWS) for i, c in enumerate(row)}


NEAR = 1.2  # key widths: a direct neighbour (1.0, or 1.1 across a row's stagger)
TOP_WORDS = 60000  # slip targets: the most frequent lexicon words


def offset(meant, hit):
    """Where key [hit] lies from key [meant], (dx in half key widths rounded, dy in rows): rows are taller than keys
    are wide, so a slip sideways is likelier than one a row away at the same distance."""
    (x1, y1), (x2, y2) = POS[meant], POS[hit]
    return round((x2 - x1) * 2) / 2, int(round(y2 - y1))


BINS = [(3, 'short'), (5, '4-5'), (7, '6-7'), (9, '8-9'), (99, '10+')]


def bucket(w):
    """Edit mix, count and positions are fitted per length bin (short words' typos are mostly substitutions; long
    words' errors fall less often on the last letter: Finnish, fitted on English, put 37 % there vs 29 % real with
    one 'long' bin, 2026-11-20)."""
    return next(name for top, name in BINS if len(w) <= top)


def lexicon_words(path, top=None):
    out = []
    for l in open(path, encoding='utf-8'):
        if l.startswith('#'): continue
        w, f = l.rstrip('\n').split('\t')[:2]
        out.append((w.lower(), int(f)))
        if top and len(out) >= top: break
    return out


def motor_explained(script, w):
    for op, i, ch in script:
        if op == 'substitute' and (dist(ch[0], ch[1]) or 99) > NEAR: return False
        if op == 'insert':
            near = [dist(ch, w[k]) for k in (i - 1, i) if 0 <= k < len(w) and dist(ch, w[k]) is not None]
            if not near or min(near) > NEAR: return False
    return True


def slip_kind(t, w):
    d = len(t) - len(w)
    k = 'subst' if d == 0 and dl(w, t) == 1 else 'omit' if d == -1 else 'insert' if d == 1 else 'multi'
    return k


def fit(rows, lexicon):
    lex = set(w for w, _ in lexicon_words(lexicon))
    by_len = collections.defaultdict(lambda: [0, 0, 0, 0])  # length -> [typos, word swaps, words, finger real words]
    op_count = collections.Counter(); where = collections.Counter(); sub_d = collections.Counter(); ins_d = collections.Counter()
    n_ops = collections.Counter(); b_ops = collections.Counter(); b_n = collections.Counter(); b_where = collections.Counter()
    sub_off = collections.Counter(); ins_off = collections.Counter(); kinds = collections.Counter(); finger_real_kinds = collections.Counter()
    b_real_kinds = collections.Counter()
    for typed, want in rows:
        t, w = typed.lower(), want.lower()
        if not (t.isalpha() and w.isalpha()): continue
        L = min(len(w), 12)
        by_len[L][2] += 1
        if t == w: continue
        by_len[L][0] += 1
        script = ops(t, w)
        if t in lex and not motor_explained(script, w):  # a word swap: a real word no finger explains
            by_len[L][1] += 1; kinds[slip_kind(t, w)] += 1
            continue
        if t in lex:  # a finger typo that is a real word: the bias redraw's targets
            by_len[L][3] += 1; finger_real_kinds[slip_kind(t, w)] += 1; b_real_kinds[bucket(w), slip_kind(t, w)] += 1
            # the finger model learns from non-words only: these are the bias redraw's job (learning from both counted
            # their omissions twice: non-word typos shortened 22 % vs 14 % real, 2026-11-20; SIM7_FIT=all for the old way)
            if os.environ.get('SIM7_FIT') != 'all': continue
        n_ops[len(script)] += 1; b_n[bucket(w), len(script)] += 1
        for op, i, ch in script:
            op_count[op] += 1; b_ops[bucket(w), op] += 1
            rel = 'first' if i == 0 else 'last' if i >= len(w) - 1 else 'middle'
            where[(op, rel)] += 1; b_where[bucket(w), op, rel] += 1
            if op == 'substitute' and dist(ch[0], ch[1]) is not None:
                sub_d[dist(ch[0], ch[1])] += 1; sub_off[offset(ch[0], ch[1])] += 1
            if op == 'insert':
                near = [dist(ch, w[k]) for k in (i - 1, i) if 0 <= k < len(w) and dist(ch, w[k]) is not None]
                if near: ins_d[min(near)] += 1
                offs = [offset(w[k], ch) for k in (i - 1, i) if 0 <= k < len(w) and w[k] in POS and ch in POS]
                if offs: ins_off[min(offs, key=lambda o: abs(o[0]) + abs(o[1]))] += 1
    total = sum(op_count.values())
    return {
        'layout': {'rows': ROWS, 'offset': OFFSET},
        'error_rate_by_length': {str(L): v[0] / v[2] for L, v in sorted(by_len.items()) if v[2] >= 200},
        'swap_share_by_length': {str(L): v[1] / v[0] for L, v in sorted(by_len.items()) if v[0] >= 100},
        'finger_real_word_by_length': {str(L): v[3] / (v[0] - v[1]) for L, v in sorted(by_len.items()) if v[0] >= 100},
        'bias_by_length': {},  # calibrate()
        'finger_real_kinds': {k: v / sum(finger_real_kinds.values()) for k, v in finger_real_kinds.items()},
        'finger_real_kinds_by_bucket': {b: {k: v / max(1, sum(x for (bb, _), x in b_real_kinds.items() if bb == b))
                                            for (bb, k), v in b_real_kinds.items() if bb == b} for _, b in BINS},
        'bias_accept': {},  # calibrate(): per edit kind, so the redrawn real words have the real kind mix
        'slip_kinds': {k: v / sum(kinds.values()) for k, v in kinds.items()},
        'edits_per_typo': {str(k): v / sum(n_ops.values()) for k, v in sorted(n_ops.items())},
        'op_share': {k: v / total for k, v in op_count.items()},
        'op_share_by_bucket': {b: {op: v / sum(x for (bb, _), x in b_ops.items() if bb == b) for (bb, op), v in b_ops.items() if bb == b}
                               for _, b in BINS},
        'edits_per_typo_by_bucket': {b: {str(k): v / sum(x for (bb, _), x in b_n.items() if bb == b) for (bb, k), v in b_n.items() if bb == b}
                                     for _, b in BINS},
        'op_position': {f'{op}/{rel}': v / op_count[op] for (op, rel), v in where.items()},
        'substitution_distance': {str(k): v / sum(sub_d.values()) for k, v in sorted(sub_d.items())},
        # the key hit relative to the key meant: (columns across in key widths, rows down), any grid layout
        'substitution_offset': {f'{dx} {dy}': v / sum(sub_off.values()) for (dx, dy), v in sub_off.items()},
        'insertion_offset': {f'{dx} {dy}': v / sum(ins_off.values()) for (dx, dy), v in ins_off.items()},
        'op_position_by_bucket': {b: {f'{op}/{rel}': v / sum(x for (bb, o2, _), x in b_where.items() if bb == b and o2 == op)
                                      for (bb, op, rel), v in b_where.items() if bb == b} for _, b in BINS},
        'insertion_distance': {str(k): v / sum(ins_d.values()) for k, v in sorted(ins_d.items())},
    }


def calibrate(model, rows, lexicon, rnd=None, cap=40000):
    """Lexical bias per length: how much more often real finger typos land on a real word than the finger model's
    do on the same words, (real - motor) / (1 - motor); that share of finger typos is redrawn until a real word."""
    rnd = rnd or random.Random(3)
    sim = Sim(model, lexicon)
    lex = set(w for w, _ in lexicon_words(lexicon))
    words = [w.lower() for t, w in rows if t.lower() != w.lower() and t.isalpha() and w.isalpha()]
    rnd.shuffle(words)
    hit = collections.defaultdict(lambda: [0, 0]); motor_kinds = collections.Counter()
    b_hit = collections.defaultdict(lambda: [0, 0]); b_motor_kinds = collections.Counter()
    for w in words[:cap]:
        for _ in range(20):
            t = sim.motor(w, rnd)
            if t != w: break
        # only finger-explained real words count (what the bias redraw makes and what finger_real_word_by_length measures)
        real = t in lex and motor_explained(ops(t, w), w)
        h = hit[str(min(len(w), 12))]; h[0] += 1; h[1] += real
        bh = b_hit[bucket(w)]; bh[0] += 1; bh[1] += real
        if real: motor_kinds[slip_kind(t, w)] += 1; b_motor_kinds[bucket(w), slip_kind(t, w)] += 1
    share = {}
    for L, real in model['finger_real_word_by_length'].items():
        n, k = hit.get(L, (0, 0))
        motor = k / n if n else 0.0
        share[L] = max(0.0, (real - motor) / (1 - motor)) if motor < 1 else 0.0
    model['motor_real_word_by_length'] = {L: hit[L][1] / hit[L][0] for L in hit if hit[L][0]}
    model['bias_by_length'] = share
    # the kinds finger_real() draws (before reweighting) vs the real finger real-word kinds: one weight per kind
    cond = collections.Counter()
    for w in words[:4000]:
        cs = sim.finger_real(w, rnd) and sim.real_cache[w]
        tot = sum(p for _, p in cs) if cs else 0
        for c, p in (cs or []): cond[slip_kind(c, w)] += p / tot
    total = sum(cond.values()) or 1
    # the redraw supplies the EXCESS real words: plain finger typos already land on words by accident (mostly
    # substitutions), so its kind mix B = (q T - (1 - b) r M) / b, with T the real kind mix, M the accidents' mix,
    # q the real share of finger typos that are words, r the finger model's, b the redraw share (pooled over lengths)
    n_all = sum(h[0] for h in hit.values()) or 1
    r = sum(h[1] for h in hit.values()) / n_all
    q = sum(model['finger_real_word_by_length'].get(L, 0) * h[0] for L, h in hit.items()) / n_all
    b = max(1e-9, (q - r) / (1 - r))
    m_tot = sum(motor_kinds.values()) or 1
    need = {k: max(0.0, q * t - (1 - b) * r * motor_kinds.get(k, 0) / m_tot) / b for k, t in model['finger_real_kinds'].items()}
    nt = sum(need.values()) or 1
    model['real_kind_weight'] = {k: (v / nt) / max(1e-9, cond.get(k, 0) / total) for k, v in need.items() if cond.get(k, 0) > 0}
    # the same per length bin (short words' real-word slips are mostly substitutions, long words' mostly drops)
    b_cond = collections.defaultdict(collections.Counter)
    for w in words[:8000]:
        cs = sim.finger_real(w, rnd) and sim.real_cache[w]
        tot = sum(p for _, p in cs) if cs else 0
        for c, p in (cs or []): b_cond[bucket(w)][slip_kind(c, w)] += p / tot
    by_bucket = {}
    for _, bk in BINS:
        n_b, k_b = b_hit.get(bk, (0, 0))
        target = model['finger_real_kinds_by_bucket'].get(bk) or {}
        if n_b < 200 or not target or not b_cond[bk]: continue
        r_b = k_b / n_b
        lens = [(L, h) for L, h in hit.items() if bucket('x' * int(L)) == bk]
        q_b = sum(model['finger_real_word_by_length'].get(L, 0) * h[0] for L, h in lens) / max(1, sum(h[0] for _, h in lens))
        b_b = max(1e-9, (q_b - r_b) / (1 - r_b))
        mk = sum(v for (bb, _), v in b_motor_kinds.items() if bb == bk) or 1
        need_b = {k: max(0.0, q_b * t - (1 - b_b) * r_b * b_motor_kinds.get((bk, k), 0) / mk) / b_b for k, t in target.items()}
        nb = sum(need_b.values()) or 1; cb = sum(b_cond[bk].values()) or 1
        by_bucket[bk] = {k: (v / nb) / max(1e-9, b_cond[bk].get(k, 0) / cb) for k, v in need_b.items() if b_cond[bk].get(k, 0) > 0}
    model['real_kind_weight_by_bucket'] = by_bucket
    return model


def by_offset(pos, c, table, rnd):
    """A key at an offset from c drawn from the fitted (dx, dy) table (the nearest key to the target point)."""
    if c not in pos or not table: return c
    key = rnd.choices(list(table), weights=list(table.values()))[0]
    dx, dy = (float(v) for v in key.split())
    if dx == 0 and dy == 0: return c
    x, y = pos[c]; tx, ty = x + dx, y + dy
    best = min((k for k in pos if k != c), key=lambda k: (pos[k][0] - tx) ** 2 + (pos[k][1] - ty) ** 2)
    return best


class Sim:
    def __init__(self, model, lexicon, rows=None, offset=None):
        """[rows] / [offset]: the layout to type on (any language), default the one the model was fitted on."""
        self.m = model
        rows = rows or model['layout']['rows']
        offset = offset or (model['layout']['offset'] + [1.25] * max(0, len(rows) - 3))
        self.pos = {c: (i + offset[r], float(r)) for r, row in enumerate(rows) for i, c in enumerate(row)}
        words = [(w, f) for w, f in lexicon_words(lexicon, TOP_WORDS) if w.isalpha()]
        self.freq = dict(words)
        self.index = collections.defaultdict(list)  # a word with up to 2 letters deleted -> words
        for w, _ in words:
            for d in self._deletes(w): self.index[d].append(w)
        self.cache = {}
        self.real_cache = {}
        self.lexicon_all = set(w for w, _ in lexicon_words(lexicon))

    def finger_real(self, w, rnd):
        """A real word one finger slip away from [w], drawn by the finger model's probability of that slip (the
        finger model conditioned on landing on a word: no rejection sampling, which failed often and fell back to a
        non-word, 2026-11-20). None when no slip makes a word."""
        if w not in self.real_cache:
            m = self.m; b = bucket(w)
            share = m.get('op_share_by_bucket', {}).get(b) or m['op_share']
            positions = m.get('op_position_by_bucket', {}).get(b) or m['op_position']
            sub_t, ins_t = m['substitution_offset'], m['insertion_offset']
            n_mid = max(1, len(w) - 2)
            def pos_p(op, i):
                rel = 'first' if i == 0 else 'last' if i >= len(w) - 1 else 'middle'
                p = positions.get(f'{op}/{rel}', 0.0)
                return p / n_mid if rel == 'middle' else p
            cands = collections.Counter()
            for i, ch in enumerate(w):
                cands[w[:i] + w[i + 1:]] += share.get('omit', 0) * pos_p('omit', i)
                if i + 1 < len(w) and w[i] != w[i + 1]:
                    cands[w[:i] + w[i + 1] + w[i] + w[i + 2:]] += share.get('transpose', 0) * pos_p('transpose', i)
                if ch not in POS: continue
                for k in POS:
                    if k == ch: continue
                    o = offset(ch, k); key = f'{o[0]} {o[1]}'
                    if key in sub_t: cands[w[:i] + k + w[i + 1:]] += share.get('substitute', 0) * pos_p('substitute', i) * sub_t[key]
                    if key in ins_t:  # k typed next to ch (before or after it)
                        cands[w[:i] + k + w[i:]] += share.get('insert', 0) * pos_p('insert', i) * ins_t[key] / 2
                        cands[w[:i + 1] + k + w[i + 1:]] += share.get('insert', 0) * pos_p('insert', min(i + 1, len(w) - 1)) * ins_t[key] / 2
            self.real_cache[w] = [(c, p) for c, p in cands.items()
                                  if p > 0 and c != w and c in self.lexicon_all and motor_explained(ops(c, w), w)]
        cs = self.real_cache[w]
        if not cs: return None
        # the kind mix of real people's real-word slips (more dropped letters than the finger model's own: calibrate())
        kw = (self.m.get('real_kind_weight_by_bucket') or {}).get(bucket(w)) or self.m.get('real_kind_weight') or {}
        weights = [p * kw.get(slip_kind(c, w), 1.0) for c, p in cs]
        return rnd.choices([c for c, _ in cs], weights=weights)[0] if sum(weights) > 0 else None

    @staticmethod
    def _deletes(w):
        out = {w}
        for _ in range(2):
            out |= {x[:i] + x[i + 1:] for x in out for i in range(len(x)) if len(x) > 1}
        return out

    def motor(self, word, rnd):
        m, w = self.m, list(word.lower())
        b = bucket(w)
        ept = m.get('edits_per_typo_by_bucket', {}).get(b) or m['edits_per_typo']
        share = m.get('op_share_by_bucket', {}).get(b) or m['op_share']
        positions = m.get('op_position_by_bucket', {}).get(b) or m['op_position']
        n = 1 if rnd.random() < ept.get('1', 0.82) else 2
        for _ in range(n):
            if len(w) < 2: break
            op = rnd.choices(list(share), weights=list(share.values()))[0]
            rels = {k.split('/')[1]: v for k, v in positions.items() if k.startswith(op + '/')} or \
                {k.split('/')[1]: v for k, v in m['op_position'].items() if k.startswith(op + '/')}
            rel = rnd.choices(list(rels), weights=list(rels.values()))[0]
            i = 0 if rel == 'first' else len(w) - 1 if rel == 'last' else rnd.randrange(1, max(2, len(w) - 1))
            i = min(i, len(w) - 1)
            if op == 'substitute': w[i] = by_offset(self.pos, w[i], m['substitution_offset'], rnd)
            elif op == 'omit': del w[i]
            elif op == 'insert':
                src = w[i]
                ch = src if rnd.random() < m['insertion_distance'].get('0.0', 0) else by_offset(self.pos, src, m['insertion_offset'], rnd)
                w.insert(i + (1 if rel == 'last' else 0), ch)
            elif op == 'transpose':
                i = min(i, len(w) - 2)  # the pair starting at the last letter is the last two (sim6 swapped nothing)
                w[i], w[i + 1] = w[i + 1], w[i]
        return ''.join(w)

    def slip(self, w, rnd):
        if w not in self.cache:
            seen = set(); by = collections.defaultdict(list)
            for d in self._deletes(w):
                for c in self.index.get(d, ()):
                    if c != w and c not in seen and dl(w, c) <= 2 and not motor_explained(ops(c, w), w):
                        seen.add(c); by[slip_kind(c, w)].append(c)
            self.cache[w] = by
        by = self.cache[w]
        kinds = self.m['slip_kinds']
        k = rnd.choices(list(kinds), weights=list(kinds.values()))[0]
        cs = by.get(k) or [c for v in by.values() for c in v]
        if not cs: return None
        return rnd.choices(cs, weights=[self.freq[c] ** 0.5 for c in cs])[0]

    def typo(self, word, rnd):
        """One typo (drawn until the word changes, as noisy_sim): a word swap, a finger typo redrawn until it is a
        real word (lexical bias), or a plain finger typo, by the fitted shares for the word's length."""
        w = word.lower(); L = str(min(len(w), 12))
        t = None
        if rnd.random() < self.m['swap_share_by_length'].get(L, 0.0):
            t = self.slip(w, rnd)
        if t is None and rnd.random() < self.m['bias_by_length'].get(L, 0.0):
            t = self.finger_real(w, rnd)
        for _ in range(20):
            if t and t != w: break
            t = self.motor(w, rnd)
        if not t or t == w: return word
        return t[0].upper() + t[1:] if word[:1].isupper() else t


if __name__ == '__main__':
    # sim_typos7.py fit pairs.tsv lexicon.tsv model.json
    rows = [l.rstrip('\n').split('\t') for l in open(sys.argv[2], encoding='utf-8')]
    pairs = [(r[3], r[4]) for r in rows if len(r) == 5]
    model = calibrate(fit(pairs, sys.argv[3]), pairs, sys.argv[3])
    json.dump(model, open(sys.argv[4], 'w'), indent=1)
    print(json.dumps({k: model[k] for k in ('slip_share_by_length', 'slip_kinds', 'op_share')}, indent=1))
