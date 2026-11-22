"""English clitics as tokens of their own (tokenizer A/B, user 2026-11-22: "put <possessive> as a token, then depending
on the word add s' or 's: do they help or is it more code for nothing"). Applied after caps.fold, at the id level as
caps.CAP is: a word's clitic is cut off before SentencePiece sees the text, and its id goes after the word's last piece.

The sp16k tokenizer split them into fragments shared with everything else ("cat's" -> ▁cat ' s, "don't" -> ▁don ' t,
"cats'" -> ▁cats '): the model spelled each clitic piece by piece, and the possessive 's was the same pieces as the
's of "it's".

  T1 (forms): <'s> <s'> <n't> <'re> <'ve> <'ll> <'d> <'m>: "cat's" = cat <'s>, "cats'" = cats <s'>, "don't" = do <n't>
              (Penn style: "won't" = wo <n't>, "can't" = ca <n't>; the text is the pieces joined, so it is exact).
  T2 (possessive): as T1, but 's and s' are one <poss> token, written ' after a word ending in s, else 's ("cats'",
              "cat's"; "James's" comes back as "James'": the one lossy case, rare).
Only ASCII and ’ apostrophes; a word that is all clitic ("'s") is left alone."""
import re

SYMBOLS = {'T1': ["<'s>", "<s'>", "<n't>", "<'re>", "<'ve>", "<'ll>", "<'d>", "<'m>"],
           'T2': ["<poss>", "<n't>", "<'re>", "<'ve>", "<'ll>", "<'d>", "<'m>"]}
FIRST_ID = 5  # after <pad> <s> </s> <unk> <cap>: user-defined symbols take ids in order
_CLITIC = re.compile(r"^(.*?[a-z0-9])(n't|'s|'re|'ve|'ll|'d|'m|')$")


def symbols(variant):
    return SYMBOLS[variant]


def split(word, variant):
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
    """(text with clitics cut off, per-word symbol or None) for a caps-folded line."""
    words, syms = [], []
    for w in text.split():
        s, c = split(w, variant)
        words.append(s); syms.append(c)
    return ' '.join(words), syms


def with_clitics(ids, syms, sp, sym_ids, starts):
    """Insert each word's clitic id after its last piece (before the next word's first piece)."""
    out, k = [], -1
    for t in ids:
        if starts[t]:
            if 0 <= k < len(syms) and syms[k]: out.append(sym_ids[syms[k]])
            k += 1
        out.append(t)
    if 0 <= k < len(syms) and syms[k]: out.append(sym_ids[syms[k]])
    return out


def render(word, sym):
    """The text of a word and its clitic (T2's <poss>: ' after a word ending in s, else 's)."""
    if sym == '<poss>': return word + ("'" if word.endswith('s') else "'s")
    if sym == "<s'>": return word + "'"
    return word + sym[1:-1]
