#!/usr/bin/env python3
"""Builds the "plausible unknown word" letter model (en.charlm.tsv) for the typing engine.

    tools/data/build_charlm.py UNIGRAMS LEXICON OUT [--misspellings FILE] [--min-count 100]

A word typed exactly but missing from our lexicon (a name, slang, a brand) needs a fair language
score, or a rare dictionary word one key away beats it ("Kaito" → "Jaito"). iOS gets that from its
neural model (the exact-typed-string veto); this is the stand-in: a letter 5-gram trained on the
words FineWeb has and our lexicon lacks, each word type counted once (what new words look like,
not how often a few of them occur).

- UNIGRAMS: count_unigrams.py output (word<TAB>count, case-sensitive, FineWeb, ODC-By 1.0)
- LEXICON: the shipped en.lexicon.tsv (its words are excluded)
- --misspellings: Wikipedia's common misspellings list (excluded, so typos don't look plausible)
Smoothing: interpolated Witten-Bell. Output lines: "c<TAB>context<TAB>ln(1-λ)" per context and
"p<TAB>context<TAB>char<TAB>ln P(char | context)" per seen pair; "^" starts and "$" ends a word.
The header carries ln P(unknown word), the share of FineWeb tokens our lexicon lacks.
"""
import argparse
import math
import re
import sys
from collections import defaultdict

ORDER = 5  # letters of history + 1
WORD = re.compile(r"^[a-z][a-z']*$")


def fold(w):
    import unicodedata
    return "".join(c for c in unicodedata.normalize("NFD", w.lower()) if unicodedata.category(c) != "Mn")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("unigrams")
    ap.add_argument("lexicon")
    ap.add_argument("out")
    ap.add_argument("--misspellings", default="")
    ap.add_argument("--min-count", type=int, default=100)
    ap.add_argument("--min-context", type=int, default=5, help="drop contexts seen fewer times")
    ap.add_argument("--binary", default="", help="also write the app's binary form (CharModel.read)")
    ap.add_argument("--alphabet", default="", help="the language's letters after folding (e.g. Russian а-я without "
                    "й ё): binary format DGC2 with 6-bit letter codes; default a-z (DGC1)")
    args = ap.parse_args()
    global WORD, ALPHABET
    if args.alphabet:
        ALPHABET = args.alphabet
        WORD = re.compile("^[" + re.escape(ALPHABET) + "][" + re.escape(ALPHABET) + "']*$")

    lexicon = set()
    with open(args.lexicon, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                lexicon.add(fold(line.split("\t")[0]))
    bad = set()
    if args.misspellings:
        with open(args.misspellings, encoding="utf-8", errors="ignore") as f:
            for line in f:
                w = line.split("->")[0].strip().lower()
                if w:
                    bad.add(w)

    types = defaultdict(int)  # folded unknown word -> count
    total = 0
    unknown_tokens = 0
    with open(args.unigrams, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, c = line.rstrip("\n").split("\t")
            c = int(c)
            total += c
            k = fold(w)
            if k in lexicon:
                continue
            unknown_tokens += c
            if c >= args.min_count and WORD.match(k) and 2 <= len(k) <= 24 and k not in bad:
                types[k] += c
    print(f"{len(types)} unknown word types (count >= {args.min_count}); unknown tokens {unknown_tokens / total:.4f} of {total}", file=sys.stderr)

    # counts[context][char], one per word type
    counts = defaultdict(lambda: defaultdict(int))
    for w in types:
        s = "^" * (ORDER - 1) + w + "$"
        for i in range(ORDER - 1, len(s)):
            for n in range(ORDER):  # history lengths 0..ORDER-1
                counts[s[i - n:i]][s[i]] += 1

    alphabet = sorted({ch for ctx in counts.values() for ch in ctx})
    probs = {}  # (context, char) -> P
    lam_out = {}

    def p(ctx, ch):
        if (ctx, ch) in probs:
            return probs[(ctx, ch)]
        if ctx == "":
            return 1.0 / len(alphabet)  # never reached for seen chars
        if ctx in lam_out:
            return (1 - lam_out[ctx]) * p(ctx[1:], ch)
        return p(ctx[1:], ch)

    for n in range(ORDER):  # shortest contexts first, so lower orders exist
        for ctx, nxt in counts.items():
            if len(ctx) != n:
                continue
            tot = sum(nxt.values())
            if n > 0 and tot < args.min_context:
                continue
            kinds = len(nxt)
            lam = tot / (tot + kinds)
            lam_out[ctx] = lam
            for ch, c in nxt.items():
                lower = p(ctx[1:], ch) if n > 0 else 1.0 / len(alphabet)
                probs[(ctx, ch)] = lam * c / tot + (1 - lam) * lower

    with open(args.out, "w", encoding="utf-8") as out:
        out.write("#dogboard-charlm 1\n")
        out.write(f"#order {ORDER}\n#unknown_logprob {math.log(unknown_tokens / total):.5f}\n")
        out.write(f"#source FineWeb sample-10BT (ODC-By 1.0): words our lexicon lacks, count >= {args.min_count}; tools/data/build_charlm.py\n")
        for ctx in sorted(lam_out):
            out.write(f"c\t{ctx}\t{math.log(1 - lam_out[ctx]):.4f}\n")
        for (ctx, ch) in sorted(probs):
            out.write(f"p\t{ctx}\t{ch}\t{math.log(probs[(ctx, ch)]):.4f}\n")
    print(f"{len(lam_out)} contexts, {len(probs)} entries -> {args.out}", file=sys.stderr)
    if args.binary:
        write_binary(args.binary, math.log(unknown_tokens / total), lam_out, probs)


ALPHABET = ""  # set by --alphabet


# the app's keys (CharModel.kt): letters a-z = 1..26, apostrophe 27, word start 28, end 29;
# context letters 5 bits each, most recent lowest; length in bits 20..22; next letter in 23..27
# (31 for a context's own back-off entry)
# DGC2 (--alphabet): letter i of the alphabet = i + 1, then apostrophe, word start, end; 6 bits a code, the
# length at bit 24, the next letter at bit 27 (63 for a context's own back-off entry)
def code(ch):
    if ALPHABET:
        n = len(ALPHABET)
        return ALPHABET.index(ch) + 1 if ch in ALPHABET else {"'": n + 1, "^": n + 2, "$": n + 3}[ch]
    if "a" <= ch <= "z":
        return ord(ch) - ord("a") + 1
    return {"'": 27, "^": 28, "$": 29}[ch]


def ctx_bits(ctx, bits):
    v = 0
    for k, ch in enumerate(reversed(ctx)):
        v |= code(ch) << (bits * k)
    return v


def write_binary(path, unknown, lam_out, probs):
    import struct
    bits = 6 if ALPHABET else 5
    lb, nb, marker = 4 * bits, 4 * bits + 3, (1 << bits) - 1
    entries = [(ctx_bits(c, bits) | (len(c) << lb) | (marker << nb), math.log(1 - lam)) for c, lam in lam_out.items()]
    entries += [(ctx_bits(c, bits) | (len(c) << lb) | (code(ch) << nb), math.log(pr)) for (c, ch), pr in probs.items()]
    entries.sort()
    with open(path, "wb") as f:
        if ALPHABET:
            f.write(b"DGC2")
            f.write(struct.pack(">i", len(ALPHABET)))
            f.write(ALPHABET.encode("utf-16-be"))
        else:
            f.write(b"DGC1")
        f.write(struct.pack(">di", unknown, len(entries)))
        f.write(struct.pack(f">{len(entries)}q", *[k for k, _ in entries]))
        f.write(struct.pack(f">{len(entries)}f", *[v for _, v in entries]))
    print(f"{len(entries)} entries -> {path} (binary)", file=sys.stderr)


if __name__ == "__main__":
    main()
