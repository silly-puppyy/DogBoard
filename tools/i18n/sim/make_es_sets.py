"""Spanish test sets from held-out Leipzig sentences (ids ending in 0, never in the models), in the eval format
id<TAB>pos<TAB>context<TAB>typed<TAB>intended.

    python3 make_es_sets.py HELDOUT.txt es.lexicon.tsv typo_model7.json OUTDIR [N]

es-typos.tsv           one simulated typo per sentence (sim_typos7, fitted on real English phone typing; finger
                       errors don't depend on the language) on the Spanish layout (ñ right of l); real-word slips
                       come from the Spanish lexicon.
es-accents-clear.tsv   an accented word typed without its accents, where the bare form is not a word ("tambien").
es-accents-twin.tsv    the same where the bare form is also a word ("esta" for "está"): only context can tell.
es-controls.tsv        correct words typed correctly, including bare twins meant as typed ("esta" for "esta"):
                       anything changed here is a correct word broken.
"""
import json, random, re, sys, unicodedata

sys.path.insert(0, __file__.rsplit('/', 1)[0])
import sim_typos7 as st

WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")


def fold(w):
    return ''.join(c for c in unicodedata.normalize('NFD', w) if not unicodedata.combining(c))


def main():
    held, lex_path, model_path, out = sys.argv[1:5]
    n = int(sys.argv[5]) if len(sys.argv) > 5 else 2000
    import os
    rows = os.environ.get('SIM_ROWS', 'qwertyuiop,asdfghjklñ,zxcvbnm').split(',')
    prefix = os.environ.get('SIM_PREFIX', 'es')
    st.set_layout(rows, [0.0, 0.25, 0.75])
    lexchars = {c for l in open(lex_path, encoding='utf-8') if not l.startswith('#') for c in l.split('\t')[0].lower()}
    # long-press reaches only the language's own letters (SIM_LETTERS, from CLDR): a foreign name's ä in the Czech
    # lexicon let slips type "sprävedlnosti"
    if os.environ.get('SIM_LETTERS'): lexchars &= set(os.environ['SIM_LETTERS'])
    def base(c):  # a letter not on the layout is typed on its base key (long press): é on e, œ on o
        b = fold(c.replace('œ', 'o').replace('æ', 'a'))[:1]
        return b if b in st.POS else None
    for c in lexchars:  # accented letters sit on their base key (long press)
        if c not in st.POS and base(c): st.POS[c] = st.POS[base(c)]
    lex = {l.split('\t')[0] for l in open(lex_path, encoding='utf-8') if not l.startswith('#')}
    lower = {w.lower() for w in lex}
    sim = st.Sim(json.load(open(model_path)), lex_path, rows, [0.0, 0.25, 0.75])
    for c in lexchars:
        if c not in sim.pos and base(c) and base(c) in sim.pos: sim.pos[c] = sim.pos[base(c)]
    rnd = random.Random(7)
    # the keyboard types the straight apostrophe: a target written "c’è" counted "c'è" as a miss (Italian, 2026-11-23)
    sents = [l.rstrip('\n').replace('’', "'") for l in open(held, encoding='utf-8')]
    rnd.shuffle(sents)
    sets = {k: [] for k in ('typos', 'accents-clear', 'accents-twin', 'controls')}
    for sid, s in enumerate(sents):
        if all(len(v) >= n for v in sets.values()):
            break
        words = WORD.findall(s)
        if len(words) < 3:
            continue
        i = rnd.randrange(len(words))
        w = words[i]
        if w.lower() not in lower or len(w) < 2:
            continue
        ctx = ' '.join(words[:i])
        bare = fold(w)
        # an accent drop: not for letters with their own key (ñ in Spanish, å ä ö in Finnish)
        # (only accented letters count: every Arabic or Georgian letter is non-ASCII and on the rows, 2026-11-22)
        # half the accentable words go to the accent sets, the others stay candidates for typos and controls: Greek writes
        # almost every word of more than one syllable with a tonos, so its typo / control sets were 75 % words of up to
        # three letters (the text: 39 %), 2026-11-23
        if bare != w and not any(c in ''.join(rows) and fold(c) != c for c in w.lower()) and rnd.random() < 0.5:
            k = 'accents-twin' if bare.lower() in lower else 'accents-clear'
            if len(sets[k]) < n:
                sets[k].append((sid, i, ctx, bare, w))
                continue
        if len(sets['controls']) < n and rnd.random() < 0.5:
            sets['controls'].append((sid, i, ctx, w, w))
            continue
        if len(sets['typos']) < n:
            t = sim.typo(w.lower(), rnd)
            if t and t != w.lower():
                sets['typos'].append((sid, i, ctx, t, w))
    for k, rows in sets.items():
        with open(f'{out}/{prefix}-{k}.tsv', 'w', encoding='utf-8') as f:
            for r in rows:
                f.write('\t'.join(map(str, r)) + '\n')
        print(k, len(rows), rows[:3])


if __name__ == '__main__':
    main()
