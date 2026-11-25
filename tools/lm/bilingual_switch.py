"""Code-switching and word replacement for the English + Spanish bilingual-recipe experiment
(notes/research/bilingual-recipes.md, private study). The transformation rules are mixed_sets_v2.py's (imported:
WORD, CLAUSE, lexicon), applied to one line at a time:
  insert     one or two content words of the matrix line replaced by their translation (nouns, verbs, adjectives; the
             matrix language's 300 most frequent words stay), as bilinguals insert words
  alternate  the head of the matrix line up to a clause boundary (comma / conjunction) + the tail of another line of the
             other language, as bilinguals switch clauses
  replace    (recipe 3) every translatable content word replaced with probability P, whatever the position
Translations: Wiktionary pairs (codeswitch/kaikki/es-en.tsv: es word, en word, pos), only words both lexicons list; the most
frequent target word for a source word.
Importable: Tables(), insert(), alternate(), replace()."""
import os, re, sys, random

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'i18n', 'sim'))
from mixed_sets_v2 import WORD, CLAUSE, lexicon

D = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data'
EN_LEX = f'{D}/packs-work/en-US/en.lexicon.tsv'
ES_LEX = f'{D}/packs-work/es/es.lexicon.tsv'
PAIRS = f'{D}/codeswitch/kaikki/es-en.tsv'


class Tables:
    def __init__(self):
        self.lex = {'en': lexicon(EN_LEX), 'es': lexicon(ES_LEX)}
        self.table = {'es': {}, 'en': {}}  # source language -> {word: translation}
        for line in open(PAIRS, encoding='utf-8'):
            es, en, _ = line.rstrip('\n').split('\t')
            if es in self.lex['es'] and en in self.lex['en'] and ' ' not in en and ' ' not in es:
                if self.lex['en'][en] > self.lex['en'].get(self.table['es'].get(es, ''), 0): self.table['es'][es] = en
                if self.lex['es'][es] > self.lex['es'].get(self.table['en'].get(en, ''), 0): self.table['en'][en] = es
        self.top = {l: set(sorted(v, key=v.get, reverse=True)[:300]) for l, v in self.lex.items()}

    def spots(self, words, lang):
        """Indices of words (whitespace split, punctuation allowed around) with a translation, content words only."""
        out = []
        for k, w in enumerate(words):
            m = WORD.search(w)
            if m and m.group(0).lower() in self.table[lang] and m.group(0).lower() not in self.top[lang]: out.append(k)
        return out

    def translate(self, w, lang):
        m = WORD.search(w); t = self.table[lang][m.group(0).lower()]
        if m.group(0)[:1].isupper(): t = t[:1].upper() + t[1:]
        return w[:m.start()] + t + w[m.end():]


def insert(tb, rnd, line, lang):
    """Line with one or two content words translated, or None if it has none."""
    words = line.split(' '); sp = tb.spots(words, lang)
    if not sp: return None
    chosen = set(rnd.sample(sp, min(len(sp), rnd.choice([1, 1, 2]))))
    return ' '.join(tb.translate(w, lang) if k in chosen else w for k, w in enumerate(words))


def alternate(rnd, matrix, other):
    """Head of the matrix line to a clause boundary + tail of the other-language line, or None."""
    cm = [m.end() for m in CLAUSE.finditer(matrix)]; co = [m.end() for m in CLAUSE.finditer(other)]
    if not cm or not co: return None
    return matrix[:rnd.choice(cm)] + other[rnd.choice(co):]


def replace(tb, rnd, line, lang, p):
    """(words, replaced flags): each translatable content word replaced with probability p (a word may become
    several words only if its translation has spaces: pairs with spaces are excluded, so one for one)."""
    words = line.split(' '); sp = set(tb.spots(words, lang)); flags = []
    for k, w in enumerate(words):
        hit = k in sp and rnd.random() < p
        if hit: words[k] = tb.translate(w, lang)
        flags.append(hit)
    return words, flags
