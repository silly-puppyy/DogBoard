#!/usr/bin/env python3
"""Builds a DogBoard lexicon for a language from a Hunspell dictionary, wordfreq and plain text.

    tools/data/build_lexicon_hunspell.py --lang es --hunspell DIR/index --wordfreq large_es.msgpack.gz \
        --corpus SENTENCES.txt [--corpus ...] --out es.lexicon.tsv [--review review.tsv]

For languages with no SCOWL (build_lexicon.py is English only). notes/design/spanish-pass.md.

Which words:
  - every form the Hunspell dictionary accepts (expand_hunspell.py: stems, inflections, enclitics);
  - words wordfreq measures often (Zipf >= MIN_ZIPF_EXTRA) that Hunspell lacks: names, loans, slang. Left out
    when the word is a misspelling: it folds (no accents, lowercase) to a Hunspell form ("tambien" ->
    "también"), or a word one edit away is 1000 times as frequent (as build_lexicon.py).
How often: wordfreq's Zipf where it has the word, else a floor below anything wordfreq measures; counts are
10^(zipf + 1), rounded (as build_lexicon.py).
Casing: Hunspell's form; for words from wordfreq, the form the corpus mostly writes in mid-sentence.
--review writes the words added from wordfreq with their reason, for a quick human look (quality control).
Corpus files: one sentence per line; Leipzig "id<TAB>sentence" lines are read too.
"""
import argparse
import math
import os
import re
import sys
import unicodedata
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_lexicon import load_wordfreq  # noqa: E402
from expand_hunspell import expand  # noqa: E402

# Zipf floor for Hunspell forms wordfreq has never seen (below wordfreq's own floor, about 1.0)
FLOOR_ZIPF = 0.5
# wordfreq words Hunspell lacks are kept from this Zipf on (build_lexicon.py's web-only cut)
MIN_ZIPF_EXTRA = 3.0
# ... and capitalised names from this one, when the corpus capitalises them
MIN_ZIPF_NAME = 2.5
# a one-letter word: the vowels English / Spanish / Italian use, or any letter used at least once per 1000 words
# (Zipf 6): the Czech prepositions v s z k (4.7 % of Czech text was "unknown" without them), Russian в с к у о я,
# Polish w z; tokenizer leftovers ("m", "c") stay below 5.5
ONE_LETTER_ZIPF = 6.0


LATIN_SCRIPT = True  # set in main from the language's frequent words: the vowel list is for Latin-script languages


def one_letter_word(w, z):
    return (LATIN_SCRIPT and w in ("a", "i", "o", "y", "e", "u")) or z >= ONE_LETTER_ZIPF
# share of mid-sentence uses that must be capitalised to call a word a name
NAME_SHARE = 0.8
# corpus uses needed before its casing is trusted
MIN_CASING_USES = 20

WORD = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*")


def edits1(w, letters):
    """Words one deletion, transposition, substitution or insertion away from w, over the language's own letters."""
    splits = [(w[:i], w[i:]) for i in range(len(w) + 1)]
    for a, b in splits:
        if b:
            yield a + b[1:]
            for c in letters:
                yield a + c + b[1:]
        if len(b) > 1:
            yield a + b[1] + b[0] + b[2:]
        for c in letters:
            yield a + c + b


def fold(w):
    # ligatures as the letters people type for them ("coeur" for "cœur"), then accents off
    w = w.lower().replace("œ", "oe").replace("æ", "ae")
    return "".join(c for c in unicodedata.normalize("NFD", w) if not unicodedata.combining(c))


def casing_counts(paths):
    """lowercase word -> Counter of mid-sentence forms (not a sentence's first word, not all-caps text)."""
    out = defaultdict(Counter)
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for line in f:
                s = line.rstrip("\n").split("\t")[-1]
                if sum(c.isupper() for c in s) > len(s) / 2:
                    continue
                words = WORD.findall(s)
                for i, w in enumerate(words):
                    if i == 0:
                        continue
                    # one apostrophe, as the keyboard types it: Italian had "c’è" and "c'è" as two words (2026-11-23)
                    w = w.replace("’", "'")
                    out[w.lower()][w] += 1
    return out


class Sink:
    def __init__(self):
        self.words = []

    def write(self, s):
        if s.strip():
            self.words.append(s.strip())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", required=True)
    ap.add_argument("--hunspell", default="", help="path without .dic/.aff; none: words from wordfreq + the corpus")
    ap.add_argument("--min-corpus", type=int, default=3, help="without Hunspell: corpus uses a wordfreq word needs")
    ap.add_argument("--min-corpus-form", type=int, default=0, help="without Hunspell: also every corpus form used this often (0 = off)")
    ap.add_argument("--min-zipf", type=float, default=1.0, help="without Hunspell: wordfreq Zipf a word needs")
    ap.add_argument("--wordfreq", default="", help="wordfreq list; none (Georgian: no list): frequencies from the corpus")
    ap.add_argument("--corpus", action="append", default=[])
    ap.add_argument("--out", required=True)
    ap.add_argument("--review")
    ap.add_argument("--source", default="")
    ap.add_argument("--elision", default="", help="elided prefixes, comma-separated (French: l,d,j,m,t,s,c,n,qu,jusqu,lorsqu,puisqu)")
    ap.add_argument("--unigrams", default="", help="corpus word counts (word<TAB>count) for --elision")
    ap.add_argument("--min-elided", type=int, default=3, help="corpus uses an elided form needs")
    args = ap.parse_args()

    casing = casing_counts(args.corpus)
    if args.wordfreq:
        zipf = load_wordfreq(args.wordfreq)
    else:
        # no wordfreq list for the language: the corpus's own counts on the same scale (Zipf = log10 per billion
        # words), words used at least --min-corpus times (2026-11-22: language packs built from a language code)
        counts = {w: sum(c.values()) for w, c in casing.items()}
        total = sum(counts.values()) or 1
        zipf = {w: math.log10(n / total * 1e9) for w, n in counts.items() if n >= args.min_corpus}
    # Arabic, Georgian, Russian: the Latin vowels are not one-letter words there (the Arabic list had "A", "E", "O")
    global LATIN_SCRIPT
    top = sorted(zipf, key=lambda k: -zipf[k])[:1000]
    LATIN_SCRIPT = sum(all(c.isascii() or unicodedata.name(c, "").startswith("LATIN") for c in w if c.isalpha()) for w in top) > len(top) / 2
    if args.hunspell:
        sink = Sink()
        expand(args.hunspell + ".dic", args.hunspell + ".aff", sink)
        forms = sink.words
    else:
        # no spelling dictionary (Finnish: its morphology lives in Voikko, not Hunspell): the words wordfreq measures
        # that edited text uses too (Leipzig: Wikipedia, news), minus the typo-like ones (a word one edit away 1000x
        # more frequent); written in the corpus's usual casing
        forms = []
        # every form the corpus attests at least --min-corpus-form times joins wordfreq's (at its corpus Zipf, same
        # per-billion scale): agglutinative languages have far more forms than wordfreq's lists hold (Turkish: 62,627
        # words, 11 % of its text unknown, 2026-11-23); the typo filter below applies to them too
        if args.min_corpus_form:
            counts = {w: sum(c.values()) for w, c in casing.items()}
            total = sum(counts.values()) or 1
            added = 0
            for w, n in counts.items():
                if w not in zipf and n >= args.min_corpus_form:
                    zipf[w] = math.log10(n / total * 1e9); added += 1
            print(f"corpus forms added to the candidates: {added}", file=sys.stderr)
        top = sorted(zipf, key=lambda k: -zipf[k])[:20000]
        wf_letters = "".join(sorted({c for w in top for c in w if c.isalpha()}))
        for w, z in zipf.items():
            if z < args.min_zipf or not WORD.fullmatch(w) or len(w) < 2 and not one_letter_word(w, z): continue
            uses = casing.get(w)
            # frequent words count even when edited text rarely has them: spoken forms ("oo", "ok", "kaheksan")
            if z < MIN_ZIPF_EXTRA and (not uses or sum(uses.values()) < args.min_corpus): continue
            if any(e != w and zipf.get(e, 0) >= z + 3 for e in edits1(w, wf_letters)): continue
            forms.append(uses.most_common(1)[0][0] if uses and uses.most_common(1)[0][0][0].isupper() and sum(c for f, c in uses.items() if f[0].isupper()) >= NAME_SHARE * sum(uses.values()) else w)
    hun_lower = {f.lower() for f in forms}
    hun_fold = {fold(f) for f in forms}
    # the language's letters (Cyrillic, ä ö, ñ...): the typo filter's edits use them, not English's a-z
    letters = "".join(sorted({c for f in hun_lower for c in f if c.isalpha()}))
    print(f"{len(forms)} Hunspell forms, {len(zipf)} wordfreq words, {len(casing)} corpus words", file=sys.stderr)

    entries = {}  # surface -> zipf
    for f in forms:
        z = zipf.get(f.lower())
        entries[f] = max(entries.get(f, 0), z if z is not None else FLOOR_ZIPF)

    review = []
    skipped = Counter()
    for w, z in zipf.items():
        # single letters from wordfreq are tokenizer leftovers ("C", "m"); the language's real ones are in Hunspell
        if w in hun_lower or not WORD.fullmatch(w) or len(w) < 2 and not one_letter_word(w, z):
            continue
        if z < MIN_ZIPF_NAME:
            continue
        if fold(w) in hun_fold:
            skipped["accent misspelling"] += 1
            continue
        if any(e != w and zipf.get(e, 0) >= z + 3 for e in edits1(w, letters)):
            skipped["typo of a frequent word"] += 1
            continue
        uses = casing.get(w)
        n = sum(uses.values()) if uses else 0
        caps = sum(c for form, c in uses.items() if form[0].isupper()) if uses else 0
        is_name = n >= MIN_CASING_USES and caps >= NAME_SHARE * n
        if z < MIN_ZIPF_EXTRA and not is_name:
            continue
        surface = uses.most_common(1)[0][0] if is_name else w
        entries[surface] = max(entries.get(surface, 0), z)
        review.append((surface, z, "name" if is_name else "frequent", n))

    # elided forms as whole words, as iOS lists them (its French lexicon has ~148k words with an apostrophe:
    # "c'était", "l'arbre"): every prefix' + dictionary word the corpus uses often enough, at its corpus frequency
    if args.elision and args.unigrams:
        prefixes = tuple(p + "'" for p in args.elision.split(","))
        counts, total = {}, 0
        with open(args.unigrams, encoding="utf-8") as f:
            for line in f:
                w, _, n = line.rstrip("\n").partition("\t")
                n = int(n); total += n
                w = w.replace("’", "'")
                if "'" in w: counts[w] = counts.get(w, 0) + n
        added = 0
        for w, n in counts.items():
            low = w.lower()
            if n < args.min_elided or not low.startswith(prefixes): continue
            rest = low.split("'", 1)[1]
            if rest not in hun_lower: continue
            form = low if w[0].islower() else w
            z = __import__("math").log10(n / total * 1e9)
            if z > entries.get(form, -1):
                entries[form] = z; added += 1
        print(f"elided forms added: {added}", file=sys.stderr)

    with open(args.out, "w", encoding="utf-8") as out:
        out.write("#dogboard-lexicon 1\n")
        out.write(f"#lang {args.lang}\n")
        out.write(f"#source {args.source or 'Hunspell, wordfreq (CC BY-SA 4.0), corpus casing'}; tools/data/build_lexicon_hunspell.py\n")
        for w, z in sorted(entries.items(), key=lambda t: (-t[1], t[0])):
            out.write(f"{w}\t{max(1, round(10 ** (z + 1)))}\n")
    if args.review:
        with open(args.review, "w", encoding="utf-8") as r:
            for w, z, why, n in sorted(review, key=lambda t: -t[1]):
                r.write(f"{w}\t{z:.2f}\t{why}\t{n}\n")
    print(f"{len(entries)} words written; added from wordfreq {len(review)}; skipped {dict(skipped)}", file=sys.stderr)


if __name__ == "__main__":
    main()
