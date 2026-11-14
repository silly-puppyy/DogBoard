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
            elif section == 2:
                a, b = ids.get(words[0]), ids.get(words[1])
                if a is not None and b is not None:
                    pairs.append(((a << 32) | b, p, bo))
            elif section == 3:
                a, b, c = ids.get(words[0]), ids.get(words[1]), ids.get(words[2])
                if a is not None and b is not None and c is not None:
                    triples.append(((a << 42) | (b << 21) | c, p))
    print(f"kept vocab {len(vocab)}, pairs {len(pairs)}, triples {len(triples)}", file=sys.stderr)
    if len(pairs) > args.max_pairs:
        pairs.sort(key=lambda e: -e[1]); pairs = pairs[: args.max_pairs]
    if len(triples) > args.max_triples:
        triples.sort(key=lambda e: -e[1]); triples = triples[: args.max_triples]
    pairs.sort(key=lambda e: e[0]); triples.sort(key=lambda e: e[0])
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


if __name__ == "__main__":
    main()
