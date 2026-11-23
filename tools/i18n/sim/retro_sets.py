#!/usr/bin/env python3
"""Look-back test sets (RetroEvalTest): a word typed with a simulated typo (sim_typos7, as make_es_sets.py) and the
word after it typed as meant, so the eval can type both and see whether looking back at the first word fixes it;
controls are both words typed as meant (a correct word the look back changes is broken).

    retro_sets.py HELDOUT.txt LEXICON.tsv typo_model7.json OUT.tsv [N]     (SIM_ROWS as make_es_sets.py)

Columns: kind (typo / control), context (the words before), typed, intended, next word.
"""
import json, os, random, re, sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])
import sim_typos7 as st

WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")


def main():
    held, lex_path, model_path, out = sys.argv[1:5]
    n = int(sys.argv[5]) if len(sys.argv) > 5 else 2000
    rows = os.environ.get('SIM_ROWS', 'qwertyuiop,asdfghjkl,zxcvbnm').split(',')
    st.set_layout(rows, [0.0, 0.25, 0.75])
    lower = {l.split('\t')[0].lower() for l in open(lex_path, encoding='utf-8') if not l.startswith('#')}
    sim = st.Sim(json.load(open(model_path)), lex_path, rows, [0.0, 0.25, 0.75])
    rnd = random.Random(11)
    sents = [l.rstrip('\n').replace('’', "'") for l in open(held, encoding='utf-8')]
    rnd.shuffle(sents)
    typos, controls = [], []
    letters = set(''.join(rows))
    for s in sents:
        if len(typos) >= n and len(controls) >= n: break
        words = WORD.findall(s)
        if len(words) < 4: continue
        i = rnd.randrange(1, len(words) - 1)  # a word before and one after
        w, nxt = words[i], words[i + 1]
        if w.lower() not in lower or nxt.lower() not in lower or len(w) < 2: continue
        # both typed on the layout's letters only (accents and long-press letters are other sets' business)
        if not set(w.lower()) <= letters or not set(nxt.lower()) <= letters: continue
        ctx = ' '.join(words[:i])
        if rnd.random() < 0.5:
            if len(controls) < n: controls.append(('control', ctx, w, w, nxt))
            continue
        if len(typos) < n:
            t = sim.typo(w.lower(), rnd)
            if t and t != w.lower(): typos.append(('typo', ctx, t, w, nxt))
    with open(out, 'w', encoding='utf-8') as f:
        for r in typos + controls: f.write('\t'.join(r) + '\n')
    print('typos', len(typos), 'controls', len(controls))


if __name__ == '__main__':
    main()
