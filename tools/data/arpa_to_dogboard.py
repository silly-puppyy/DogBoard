#!/usr/bin/env python3
"""Converts a KenLM ARPA trigram model into DogBoard's binary n-gram file (NgramModel.readBinary).

    python3 tools/data/arpa_to_dogboard.py MODEL.arpa OUT.bin [--max-pairs N] [--max-triples N]

Keeps only words of the shipped lexicon (lowercase keys) plus <s> and </s>; n-grams with other
words are dropped. Then keeps the most probable pairs/triples up to the given limits (the
back-off weights stay as KenLM computed them, so dropped entries fall back to the shorter
context). Layout (big-endian, as Java's DataOutputStream): magic "DGN1", vocab/pair/triple
counts, vocabulary (Java modified UTF: u16 length + bytes), ln unigram probs and back-offs,
pair keys (a<<32|b) with ln probs and back-offs, triple keys (a<<42|b<<21|c) with ln probs.
"""
import argparse
import heapq
import math
import struct
import sys

LEX = "app/src/main/assets/dogboard/en.lexicon.tsv"
LN10 = math.log(10)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("arpa")
    ap.add_argument("out")
    ap.add_argument("--max-pairs", type=int, default=3_000_000)
    ap.add_argument("--max-triples", type=int, default=3_000_000)
    ap.add_argument("--legacy", action="store_true", help="the earlier float layout (DGN1); the app reads only DGN2")
    args = ap.parse_args()
    lex = {l.split("\t")[0].lower() for l in open(LEX, encoding="utf-8") if not l.startswith("#")}
    keep = lex | {"<s>", "</s>"}

    vocab, uni_p, uni_b, ids = [], [], [], {}
    pairs, triples = [], []
    section = 0
    with open(args.arpa, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("ngram ") or line in ("\\data\\", "\\end\\"):
                continue
            if line.startswith("\\") and line.endswith("-grams:"):
                section = int(line[1])
                print(f"section {section}", file=sys.stderr, flush=True)
                continue
            parts = line.split("\t")
            p = float(parts[0]) * LN10
            words = parts[1].split(" ")
            bo = float(parts[2]) * LN10 if len(parts) > 2 else 0.0
            if section == 1:
                if words[0] in keep:
                    ids[words[0]] = len(vocab)
                    vocab.append(words[0]); uni_p.append(p); uni_b.append(bo)
            # keep only the most probable entries while streaming (min-heaps of the limits' size),
            # so a multi-GB ARPA converts in bounded memory
            elif section == 2:
                a, b = ids.get(words[0]), ids.get(words[1])
                if a is not None and b is not None:
                    e = (p, (a << 32) | b, bo)
                    if len(pairs) < args.max_pairs: heapq.heappush(pairs, e)
                    elif p > pairs[0][0]: heapq.heapreplace(pairs, e)
            elif section == 3:
                a, b, c = ids.get(words[0]), ids.get(words[1]), ids.get(words[2])
                if a is not None and b is not None and c is not None:
                    e = (p, (a << 42) | (b << 21) | c)
                    if len(triples) < args.max_triples: heapq.heappush(triples, e)
                    elif p > triples[0][0]: heapq.heapreplace(triples, e)
    print(f"kept vocab {len(vocab)}, top pairs {len(pairs)}, top triples {len(triples)}", file=sys.stderr)
    pairs = sorted(((k, p, bo) for p, k, bo in pairs), key=lambda e: e[0])
    triples = sorted(((k, p) for p, k in triples), key=lambda e: e[0])
    if not args.legacy:
        write_compact(args.out, vocab, uni_p, uni_b, pairs, triples)
        return
    with open(args.out, "wb") as o:
        o.write(struct.pack(">iiii", 0x44474E31, len(vocab), len(pairs), len(triples)))
        for w in vocab:
            b = w.encode("utf-8")
            o.write(struct.pack(">H", len(b))); o.write(b)
        o.write(struct.pack(f">{len(vocab)}f", *uni_p))
        o.write(struct.pack(f">{len(vocab)}f", *uni_b))
        o.write(struct.pack(f">{len(pairs)}q", *[k for k, _, _ in pairs]))
        o.write(struct.pack(f">{len(pairs)}f", *[p for _, p, _ in pairs]))
        o.write(struct.pack(f">{len(pairs)}f", *[b for _, _, b in pairs]))
        o.write(struct.pack(f">{len(triples)}q", *[k for k, _ in triples]))
        o.write(struct.pack(f">{len(triples)}f", *[p for _, p in triples]))
    print(f"wrote {args.out}: vocab {len(vocab)}, pairs {len(pairs)}, triples {len(triples)}", file=sys.stderr)


def codebook(values, levels=256):
    """Quantile codebook: the mean of each of [levels] equal-count bins; returns (centers, codes)."""
    order = sorted(range(len(values)), key=lambda i: values[i])
    n = len(values)
    centers, codes = [], [0] * n
    for q in range(levels):
        lo, hi = q * n // levels, (q + 1) * n // levels
        if lo >= hi:
            centers.append(centers[-1] if centers else 0.0)
            continue
        idx = order[lo:hi]
        centers.append(sum(values[i] for i in idx) / len(idx))
        for i in idx:
            codes[i] = q
    return centers, codes


def write_compact(out, vocab, uni_p, uni_b, pairs, triples):
    """DGN2 layout (big-endian): magic, counts; vocabulary (u16 length + UTF-8); ln unigram probs
    and back-offs (f32); pair codebooks (2 x 256 f32: prob, back-off); pair offsets by first word
    (i32, vocab + 1); pair second words (i32) with prob and back-off codes (u8); triple codebook
    (256 f32); triple keys (i64, a<<42|b<<21|c) with prob codes (u8)."""
    pc, pcode = codebook([p for _, p, _ in pairs])
    bc, bcode = codebook([b for _, _, b in pairs])
    tc, tcode = codebook([p for _, p in triples])
    offsets = [0] * (len(vocab) + 1)
    for k, _, _ in pairs:
        offsets[(k >> 32) + 1] += 1
    for i in range(len(vocab)):
        offsets[i + 1] += offsets[i]
    with open(out, "wb") as o:
        o.write(struct.pack(">iiii", 0x44474E32, len(vocab), len(pairs), len(triples)))
        for w in vocab:
            b = w.encode("utf-8")
            o.write(struct.pack(">H", len(b))); o.write(b)
        o.write(struct.pack(f">{len(vocab)}f", *uni_p))
        o.write(struct.pack(f">{len(vocab)}f", *uni_b))
        o.write(struct.pack(">256f", *pc)); o.write(struct.pack(">256f", *bc))
        o.write(struct.pack(f">{len(offsets)}i", *offsets))
        o.write(struct.pack(f">{len(pairs)}i", *[k & 0xFFFFFFFF for k, _, _ in pairs]))
        o.write(bytes(pcode)); o.write(bytes(bcode))
        o.write(struct.pack(">256f", *tc))
        o.write(struct.pack(f">{len(triples)}q", *[k for k, _ in triples]))
        o.write(bytes(tcode))
    print(f"wrote {out} (compact): vocab {len(vocab)}, pairs {len(pairs)}, triples {len(triples)}", file=sys.stderr)


if __name__ == "__main__":
    main()
