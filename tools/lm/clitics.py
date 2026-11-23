"""English clitics as tokens of their own (tokenizer A/B, "put <possessive> as a token, then depending
on the word add s' or 's: do they help or is it more code for nothing"). Applied after caps.fold, at the id level as
caps.CAP is: a word's clitic is cut off before SentencePiece sees the text, and its id goes after the word's last piece.

The sp16k tokenizer split them into fragments shared with everything else ("cat's" -> ▁cat ' s, "don't" -> ▁don ' t,
"cats'" -> ▁cats '): the model spelled each clitic piece by piece, and the possessive 's was the same pieces as the
's of "it's".

  T1 (forms): <'s> <s'> <n't> <'re> <'ve> <'ll> <'d> <'m>: "cat's" = cat <'s>, "cats'" = cats <s'>, "don't" = do <n't>
              (Penn style: "won't" = wo <n't>, "can't" = ca <n't>; the text is the pieces joined, so it is exact).
  T2 (possessive): as T1, but 's and s' are one <poss> token, written ' after a word ending in s, else 's ("cats'",
              "cat's"; "James's" comes back as "James'": the one lossy case, rare).
  T3 (plural):  T2 plus <pl>: a noun's main plural (tools/data/build_plurals.py, AGID; never an add-s rule) is its
              singular + <pl>: "cats" = cat <pl>, "skies" = sky <pl>, "children's" = child <pl> <poss>. Nouns with
              no table entry (fish, sheep, news) stay whole words.
  T4 (article):  T3, and "an" written as "a": the model learns one indefinite article and the CPU writes "an" before
              a vowel sound (CMUdict; capitals read as letter names: "an SEO"). The rule agrees with writers 98.2 %
              of the time on the pilot text, and most disagreements are the writer's ("a image", "a email").
Only ASCII and ’ apostrophes; a word that is all clitic ("'s") is left alone."""
import re

SYMBOLS = {'T1': ["<'s>", "<s'>", "<n't>", "<'re>", "<'ve>", "<'ll>", "<'d>", "<'m>"],
           'T2': ["<poss>", "<n't>", "<'re>", "<'ve>", "<'ll>", "<'d>", "<'m>"],
           'T3': ["<poss>", "<n't>", "<'re>", "<'ve>", "<'ll>", "<'d>", "<'m>", "<pl>"]}
SYMBOLS['T4'] = SYMBOLS['T3']
PLURALS = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/agid/en-plurals.tsv'
_plural_of, _singular_of = {}, {}


def _load_plurals():
    """singular -> main plural and back, from the table (main plural = the first form)."""
    if _plural_of: return
    for line in open(PLURALS, encoding='utf-8'):
        if line.startswith('#'): continue
        f = line.rstrip('\n').split('\t')
        _plural_of[f[0]] = f[1]
    for sg, pl in _plural_of.items():
        _singular_of.setdefault(pl, sg)  # "data" <- datum; a plural two nouns share keeps the first
FIRST_ID = 5  # after <pad> <s> </s> <unk> <cap>: user-defined symbols take ids in order
_CLITIC = re.compile(r"^(.*?[a-z0-9])(n't|'s|'re|'ve|'ll|'d|'m|')$")


def symbols(variant):
    return SYMBOLS[variant]


def split(word, variant):
    """(stem, symbols) for one lowercased word: its clitic and, in T3, a plural, in writing order."""
    if variant == 'T4' and word == 'an': return 'a', []
    stem, sym = _split_clitic(word, variant)
    syms = [sym] if sym else []
    if variant in ('T3', 'T4'):
        _load_plurals()
        sg = _singular_of.get(stem)
        if sg is not None: stem, syms = sg, ['<pl>'] + syms
    return stem, syms


def _split_clitic(word, variant):
    """(stem, clitic symbol or None) for one lowercased word."""
    w = word.replace('’', "'")
    m = _CLITIC.match(w)
    if not m: return word, None
    stem, c = m.group(1), m.group(2)
    if c == "'":
        if not stem.endswith('s'): return word, None  # a closing quote, not a plural possessive
        sym = "<s'>" if variant == 'T1' else '<poss>'
    elif c == "'s":
        sym = "<'s>" if variant == 'T1' else '<poss>'
    else:
        sym = '<' + c + '>'
    return stem, sym


def fold(text, variant):
    """(text with clitics cut off, per-word list of symbols) for a caps-folded line."""
    words, syms = [], []
    for w in text.split():
        s, c = split(w, variant)
        words.append(s); syms.append(c)
    return ' '.join(words), syms


def render(word, syms):
    """The text of a word and its symbols in order (<pl>: the table's main plural; <poss>: ' after a word ending in
    s, else 's)."""
    if isinstance(syms, str): syms = [syms]
    for sym in syms or []:
        if sym == '<pl>': _load_plurals(); word = _plural_of[word]
        elif sym == '<poss>': word += "'" if word.endswith('s') else "'s"
        elif sym == "<s'>": word += "'"
        else: word += sym[1:-1]
    return word
