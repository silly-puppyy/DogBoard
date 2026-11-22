"""Gender / number agreement test sets for Spanish and French (private study; for the language-model A/B, user
2026-11-22: "French and Spanish's LLM learning genders"). From a pack's HELD-OUT sentences: spaCy tags each word
(es_core_news_sm / fr_core_news_sm, used offline only, never shipped: GPL / LGPL); adjectives, determiners and
participles marked for gender or number become a case, their rivals the same stem with the other endings that are
lexicon words (es o / a / os / as, fr - / e / s / es): "nuevo" vs nueva / nuevos / nuevas before "modelo".
Writes <pack>/<lang>-agreement.tsv: context, word, rivals (|), next word, part of speech (3000 cases).
Usage: make_agreement_sets.py PACKS_WORK_DIR"""
import os, sys, random, collections, spacy

random.seed(5)
for lang, model in [('es', 'es_core_news_sm'), ('fr', 'fr_core_news_sm')]:
    d = os.path.join(sys.argv[1], lang)
    nlp = spacy.load(model, disable=['parser', 'ner', 'lemmatizer'])
    lex = {}
    for l in open(os.path.join(d, f'{lang}.lexicon.tsv'), encoding='utf-8'):
        if not l.startswith('#'):
            w, c = l.rstrip('\n').split('\t')[:2]; lex[w.lower()] = max(lex.get(w.lower(), 0), int(c))
    ENDS = ('os', 'as', 'o', 'a') if lang == 'es' else ('es', 'e', 's', '')

    def variants(w):
        for a in ENDS:
            if a and not w.endswith(a): continue
            stem = w[:-len(a)] if a else w
            vs = [stem + b for b in ENDS if stem + b != w and stem + b in lex and lex[stem + b] >= 100]
            if vs: return vs
        return []

    lines = [l.rstrip('\n') for l in open(os.path.join(d, f'{lang}.heldout.txt'), encoding='utf-8')][:60000]
    random.shuffle(lines)
    out = open(os.path.join(d, f'{lang}-agreement.tsv'), 'w', encoding='utf-8'); n = 0; kinds = collections.Counter()
    for doc in nlp.pipe(lines[:20000], batch_size=256):
        toks = list(doc)
        for i, t in enumerate(toks[1:-1], 1):
            pos, morph = t.pos_, t.morph
            ok = pos in ('ADJ', 'DET') or (pos == 'VERB' and 'VerbForm=Part' in str(morph))
            if not ok or not t.is_alpha or len(t.text) < 3 or random.random() > 0.25: continue
            if not (morph.get('Gender') or morph.get('Number')): continue
            nxt = toks[i + 1]
            if t.whitespace_ != ' ' or not nxt.is_alpha: continue
            v = variants(t.text.lower())
            if not v: continue
            rv = random.sample(v, min(3, len(v)))
            if t.text[0].isupper(): rv = [r[0].upper() + r[1:] for r in rv]
            out.write('\t'.join([doc.text[:t.idx].rstrip(), t.text, '|'.join(rv), nxt.text, pos]) + '\n'); n += 1; kinds[pos] += 1
            if n >= 3000: break
        if n >= 3000: break
    print(lang, n, dict(kinds))
