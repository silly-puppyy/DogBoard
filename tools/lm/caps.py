"""Capitalisation as a token, as Apple's keyboard LM does it (verified in our unilm port: play.py
encode, CAP = 103; keep-list from coder_params.dat KeepWordsTitleCaseV2): every word is lowercased,
and a word that started with a capital gets a CAP token before its first piece, so "The" and "the"
share one set of statistics and the model learns separately where capitals go (sentence starts,
names). "I", "I'm", "I'll", "I've", "I'd" keep their capital. ALL-CAPS words become CAP + lowercase.
The CAP id is a SentencePiece user-defined symbol ('<cap>', id 4) that never occurs in text: it is
inserted at the id level, before the first piece of each capitalised word (SentencePiece splits on
whitespace, so the k-th piece starting with '▁' begins the k-th word)."""
CAP_SYMBOL = '<cap>'
CAP = 4
KEEP = {"I", "I'm", "I'll", "I've", "I'd", "I’m", "I’ll", "I’ve", "I’d"}


def fold(line):
    """(lowercased line, per-word capital flags), KEEP words left as they are."""
    words, caps = [], []
    for w in line.split():
        cap = w not in KEEP and any(c.isalpha() for c in w) and next(c for c in w if c.isalpha()).isupper()
        words.append(w.lower() if w not in KEEP else w); caps.append(cap)
    return ' '.join(words), caps


_starts = {}


def word_starts(sp):
    """For each piece id: does it begin a word ('▁...')? Cached per tokenizer."""
    key = id(sp)
    if key not in _starts: _starts[key] = [sp.id_to_piece(i).startswith('▁') for i in range(sp.vocab_size())]
    return _starts[key]


def with_caps(ids, caps, sp):
    """Insert CAP before the first piece of every capitalised word."""
    starts = word_starts(sp)
    out, k = [], -1
    for t in ids:
        if starts[t]:
            k += 1
            if k < len(caps) and caps[k]: out.append(CAP)
        out.append(t)
    return out


def encode(sp, line):
    text, caps = fold(line)
    return with_caps(sp.encode(text), caps, sp)
