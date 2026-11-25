"""Scores for the bilingual-recipe experiment (private study) from student_nwp_lang.py TSVs (sentence, position, target, top-3 | separated):
  bi_score.py acc  T.tsv [T.tsv ...]      top-1 / top-3 (%) and number of predictions for each
  bi_score.py split T.tsv                  the same by the target word's language: only English's list / only Spanish's / in both or neither
  bi_score.py lang T.tsv REQUESTED(en|es)  the language of the top-3 words: share in the requested language's lexicon, only in the
                                           other's lexicon, in neither (the language-control test; full lexicons, not the top-40k lists)
Word lists: each pack's lexicon, top 40,000 words, minus every word of the other language's lexicon (make_codeswitch_set.py's rule)."""
import sys, os, collections
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE); sys.path.insert(0, os.path.join(HERE, '..', 'i18n'))


def rows(path):
    for l in open(path, encoding='utf-8'):
        p = l.rstrip('\n').split('\t')
        if len(p) >= 4: yield p[2].lower(), [w.lower() for w in p[3].split('|') if w]


def lists():
    import bilingual_switch as bs
    from make_codeswitch_set import words_of
    en_all, es_all = words_of(bs.EN_LEX), words_of(bs.ES_LEX)
    return words_of(bs.EN_LEX, 40000) - es_all, words_of(bs.ES_LEX, 40000) - en_all


def acc(rs):
    n = len(rs); t1 = sum(1 for t, p in rs if p and p[0] == t); t3 = sum(1 for t, p in rs if t in p)
    return n, 100 * t1 / max(n, 1), 100 * t3 / max(n, 1)


if __name__ == '__main__':
    mode = sys.argv[1]
    if mode == 'acc':
        for f in sys.argv[2:]:
            n, a, b = acc(list(rows(f))); print(f'{os.path.basename(f)}\tn {n}\ttop1 {a:.1f}\ttop3 {b:.1f}')
    elif mode == 'split':
        oen, oes = lists(); g = collections.defaultdict(list)
        for t, p in rows(sys.argv[2]): g['en' if t in oen else 'es' if t in oes else 'other'].append((t, p))
        for k in ('en', 'es', 'other'):
            n, a, b = acc(g[k]); print(f'target {k}\tn {n}\ttop1 {a:.1f}\ttop3 {b:.1f}')
    elif mode == 'lang':
        import bilingual_switch as bs
        from make_codeswitch_set import words_of
        en, es = words_of(bs.EN_LEX), words_of(bs.ES_LEX); req = sys.argv[3]; mine, other = (en, es) if req == 'en' else (es, en); c = collections.Counter()
        for t, p in rows(sys.argv[2]):
            for w in p: c['req' if w in mine else 'other' if w in other else 'neither'] += 1
        tot = sum(c.values()); print(f'requested {req}: in requested lexicon {100 * c["req"] / tot:.1f} %\tonly in the other\'s {100 * c["other"] / tot:.1f} %\tneither {100 * c["neither"] / tot:.1f} %\t(n {tot})')
