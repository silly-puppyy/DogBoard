#!/usr/bin/env python3
"""Builds DogBoard's English trigram model from chat-like open text.

    tools/data/build_ngrams.py --data DATA_DIR --work WORK_DIR [--lexicon PATH] [--out PATH]

Stages (each cached in WORK_DIR, delete a file to redo that stage):
  1. sentences.txt / heldout.txt: text from the corpora, cleaned and tokenised, one sentence or
     clause per line; 1% of WildChat conversations and Persona-Chat's test split are held out
  2. counts.npz: 1-, 2- and 3-gram counts over the lexicon's vocabulary
  3. the model: interpolated Kneser-Ney, rare n-grams pruned, written in DogBoard's format

Sources in DATA_DIR/corpora (tools/data/fetch_corpora.sh) and DATA_DIR/tatoeba:
  WildChat-1M human turns (ODC-BY 1.0), Synthetic-Persona-Chat (CC BY 4.0),
  Common Voice sentences (CC0), Tatoeba English sentences (CC BY 2.0 FR),
  Ubuntu IRC logs (public domain; English channels, nicks and bot lines removed).
--fraction SOURCE=F samples a source (stable by document) to balance the mix.

Tokens: lowercase words with inner apostrophes ("don't"), as the keyboard's context is. A line
starting with "<s>" begins a sentence; other lines are clauses after punctuation, where the
keyboard has no previous word. Words outside the lexicon become "<unk>". Emails, URLs, phone
numbers and @handles are removed before tokenising.

DogBoard n-gram format, version 1 (UTF-8 text, natural log):
    #dogboard-ngrams 1
    1<TAB>word<TAB>logp<TAB>backoff
    2<TAB>w1 w2<TAB>logp<TAB>backoff
    3<TAB>w1 w2 w3<TAB>logp
P(w | u v) = the stored trigram if present, else exp(backoff(u v)) * P(w | v); likewise for
bigrams. Stored probabilities are already interpolated (Kneser-Ney), so this back-off reproduces
the interpolated model.
"""
import argparse
import csv
import glob
import gzip
import hashlib
import json
import math
import os
import re
import sys
from collections import Counter

import numpy as np
import pyarrow.parquet as pq

START, UNK = "<s>", "<unk>"
SCRUB = re.compile(
    r"\S+@\S+\.\w+"                                  # emails
    r"|https?://\S+|www\.\S+"                         # URLs
    r"|@\w+"                                          # handles
    r"|\+?\d[\d\s().-]{7,}\d"                         # phone numbers
)
TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)*|[.!?\n]|[^\sa-z']")
CODE_HINT = re.compile(r"[{};<>=]|def |import |\bfunction\b|```")
# WildChat human turns longer than this are mostly pasted documents, not typing
MAX_TURN_CHARS = 400


def clean(text: str) -> str:
    return SCRUB.sub(" ", text.replace("’", "'").replace("‘", "'"))


def clauses(text: str):
    """Yields token lists; a sentence start is marked with START, clauses after other punctuation are not."""
    current = [START]
    for tok in TOKEN.findall(clean(text).lower()):
        if tok in ".!?\n":
            if len(current) > 1:
                yield current
            current = [START]
        elif tok[0].isalpha():
            current.append(tok)
        else:  # comma, colon, quote, digit...: the keyboard has no previous word after these
            if len(current) > 1 or (current and current[0] != START):
                yield current
            current = []
    if len(current) > 1 or (current and current[0] != START):
        yield current


# fraction of each source's documents to use (the rest is skipped), set from the command line
FRACTION = {}
IRC_LINE = re.compile(r"^\[\d\d:\d\d\] <([^>]+)> (.*)$")
# channels in other languages: #ubuntu-de, #ubuntu-ir, #kubuntu-fr ...
NON_ENGLISH_CHANNEL = re.compile(r"-(?!offtopic|devel|server|desktop|bugs|meeting|mobile|motu|irc|uk|us|au|ca|nz|ie|za|in)[a-z]{2,3}$")


def sampled(source: str, key: str) -> bool:
    """True if this document of [source] is in the sample (stable by key)."""
    frac = FRACTION.get(source, 1.0)
    return frac >= 1.0 or int(hashlib.md5(key.encode()).hexdigest(), 16) % 1000 < frac * 1000


def ubuntu_irc(data):
    for path in sorted(glob.glob(os.path.join(data, "corpora/ubuntu_irc/*.jsonl.gz"))):
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                doc = json.loads(line)
                meta = doc.get("metadata") or {}
                channel = meta.get("channel", "")
                if NON_ENGLISH_CHANNEL.search(channel) or not sampled("ubuntu_irc", doc["id"]):
                    continue
                held = int(hashlib.md5(doc["id"].encode()).hexdigest(), 16) % 100 == 0
                nicks = {n.lower() for n in meta.get("authors") or []}
                for log_line in doc["text"].split("\n"):
                    m = IRC_LINE.match(log_line)
                    if not m or m.group(1).lower().startswith(("ubot", "lubot")):
                        continue
                    text = m.group(2)
                    if text.startswith(("!", "/")):
                        continue
                    first = text.split(" ", 1)[0].rstrip(":,").lower()
                    if first in nicks and " " in text:  # "nick: message" addressing
                        text = text.split(" ", 1)[1]
                    yield text, held


def wildchat(data):
    for path in sorted(glob.glob(os.path.join(data, "corpora/wildchat/*.parquet"))):
        table = pq.read_table(path, columns=["conversation_hash", "language", "conversation"])
        for h, lang, conv in zip(*(table.column(c).to_pylist() for c in ("conversation_hash", "language", "conversation"))):
            if lang != "English":
                continue
            held = int(hashlib.md5(h.encode()).hexdigest(), 16) % 100 == 0
            for turn in conv:
                text = turn.get("content") or ""
                if turn.get("role") != "user" or len(text) > MAX_TURN_CHARS or CODE_HINT.search(text):
                    continue
                yield text, held


def persona_chat(data):
    for path in sorted(glob.glob(os.path.join(data, "corpora/persona_chat/*.csv"))):
        held = "_test" in path
        with open(path, encoding="utf-8") as f:
            for row in csv.reader(f):
                for cell in row:
                    for line in cell.split("\n"):
                        if line.startswith(("User 1:", "User 2:")):
                            yield line.split(":", 1)[1], held


def common_voice(data):
    for path in sorted(glob.glob(os.path.join(data, "corpora/common_voice/*.txt"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if sampled("common_voice", line):
                    yield line, False


def tatoeba(data):
    path = os.path.join(data, "tatoeba/eng_sentences.tsv")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and (parts[0].endswith("0") or sampled("tatoeba", parts[0])):
                yield parts[2], parts[0].endswith("0")  # same held-out rule as the bigram builder


def extract(data, work):
    out_path, held_path = os.path.join(work, "sentences.txt"), os.path.join(work, "heldout.txt")
    if os.path.exists(out_path):
        return
    stats = Counter()
    with open(out_path + ".tmp", "w") as out, open(held_path, "w") as held:
        for name, source in (("wildchat", wildchat), ("persona_chat", persona_chat),
                             ("common_voice", common_voice), ("tatoeba", tatoeba), ("ubuntu_irc", ubuntu_irc)):
            for text, is_held in source(data):
                for toks in clauses(text):
                    (held if is_held else out).write(f"{name}\t{' '.join(toks)}\n")
                    stats[name, is_held] += len(toks)
    os.replace(out_path + ".tmp", out_path)
    print("tokens:", dict(stats), file=sys.stderr)


def count(work, vocab):
    path = os.path.join(work, "counts.npz")
    if os.path.exists(path):
        return dict(np.load(path))
    ids = {w: i for i, w in enumerate(vocab)}
    unk, n = ids[UNK], len(vocab)
    assert n < 2 ** 21
    chunks2, chunks3 = [], []
    uni = np.zeros(n, dtype=np.int64)
    buf2, buf3 = [], []

    def flush():
        for buf, chunks in ((buf2, chunks2), (buf3, chunks3)):
            if buf:
                keys, cnt = np.unique(np.array(buf, dtype=np.int64), return_counts=True)
                chunks.append((keys, cnt))
                buf.clear()
        for chunks in (chunks2, chunks3):  # merge to keep memory bounded
            if len(chunks) > 8:
                keys = np.concatenate([k for k, _ in chunks]); cnt = np.concatenate([c for _, c in chunks])
                order = np.argsort(keys, kind="stable"); keys, cnt = keys[order], cnt[order]
                uk, start = np.unique(keys, return_index=True)
                chunks[:] = [(uk, np.add.reduceat(cnt, start))]

    with open(os.path.join(work, "sentences.txt")) as f:
        for i, line in enumerate(f):
            toks = [ids.get(t, unk) for t in line.rstrip("\n").split("\t", 1)[1].split()]
            for t in toks:
                uni[t] += 1
            for a, b in zip(toks, toks[1:]):
                buf2.append((a << 21) | b)
            for a, b, c in zip(toks, toks[1:], toks[2:]):
                buf3.append((a << 42) | (b << 21) | c)
            if len(buf3) > 20_000_000:
                flush()
    flush()
    out = {"uni": uni}
    for name, chunks in (("bi", chunks2), ("tri", chunks3)):
        keys = np.concatenate([k for k, _ in chunks]); cnt = np.concatenate([c for _, c in chunks])
        order = np.argsort(keys, kind="stable"); keys, cnt = keys[order], cnt[order]
        uk, start = np.unique(keys, return_index=True)
        out[name + "_keys"], out[name + "_counts"] = uk, np.add.reduceat(cnt, start)
    np.savez(path, **out)
    return out


def kneser_ney(c, vocab, min_bigram, min_trigram, out_path):
    """Interpolated Kneser-Ney (one discount per order), pruned, written in DogBoard's format."""
    n = len(vocab)
    mask = (1 << 21) - 1
    uni = c["uni"].astype(np.float64)
    bk, bc = c["bi_keys"], c["bi_counts"].astype(np.float64)
    tk, tc = c["tri_keys"], c["tri_counts"].astype(np.float64)

    def discount(counts):
        n1, n2 = np.sum(counts == 1), np.sum(counts == 2)
        return n1 / (n1 + 2 * n2) if n1 + n2 else 0.5

    # continuation counts: how many distinct left words precede each bigram / word
    b_left, b_right = bk >> 21, bk & mask
    t_ctx, t_right = tk >> 21, tk & mask           # (u v) as a bigram key, w
    t_mid_right = tk & ((1 << 42) - 1)              # (v w) as a bigram key
    cont_bi = np.zeros(len(bk))                     # N1+(. v w) for each stored bigram (v w)
    idx = np.searchsorted(bk, t_mid_right)
    ok = (idx < len(bk)) & (bk[np.minimum(idx, len(bk) - 1)] == t_mid_right)
    np.add.at(cont_bi, idx[ok], 1)
    cont_bi = np.where(cont_bi > 0, cont_bi, bc)    # sentence-initial bigrams: fall back to counts
    cont_uni = np.bincount(b_right, minlength=n).astype(np.float64)
    cont_uni[vocab.index(START)] = 0

    # unigram: continuation probability, mixed with uniform so every word has some mass
    p1 = 0.99 * cont_uni / cont_uni.sum() + 0.01 / n
    # bigram
    d2 = discount(cont_bi)
    ctx_tot2 = np.bincount(b_left, weights=cont_bi, minlength=n)
    ctx_types2 = np.bincount(b_left, minlength=n).astype(np.float64)
    gamma2 = np.where(ctx_tot2 > 0, d2 * ctx_types2 / np.maximum(ctx_tot2, 1), 1.0)
    p2 = np.maximum(cont_bi - d2, 0) / ctx_tot2[b_left] + gamma2[b_left] * p1[b_right]
    # trigram
    d3 = discount(tc)
    ctx_keys, ctx_inv = np.unique(t_ctx, return_inverse=True)
    ctx_tot3 = np.bincount(ctx_inv, weights=tc)
    ctx_types3 = np.bincount(ctx_inv).astype(np.float64)
    gamma3 = d3 * ctx_types3 / ctx_tot3
    j = np.searchsorted(bk, tk & ((1 << 42) - 1))
    p2_of_vw = np.where(bk[np.minimum(j, len(bk) - 1)] == (tk & ((1 << 42) - 1)), p2[np.minimum(j, len(bk) - 1)],
                        gamma2[(tk >> 21) & mask] * p1[t_right])
    p3 = np.maximum(tc - d3, 0) / ctx_tot3[ctx_inv] + gamma3[ctx_inv] * p2_of_vw

    keep2 = (bc >= min_bigram) & (b_right != vocab.index(UNK))
    keep3 = (tc >= min_trigram) & (t_right != vocab.index(UNK))
    gamma3_by_bigram = dict(zip(ctx_keys.tolist(), gamma3.tolist()))
    with open(out_path, "w", encoding="utf-8") as out:
        out.write("#dogboard-ngrams 1\n#lang en_US\n")
        out.write(f"#source tools/data/build_ngrams.py: interpolated Kneser-Ney (d2 {d2:.3f}, d3 {d3:.3f}); "
                  f"WildChat-1M human turns (ODC-BY), Synthetic-Persona-Chat (CC BY 4.0), Common Voice (CC0), "
                  f"Tatoeba (CC BY 2.0 FR); min counts {min_bigram}/{min_trigram}\n")
        for i, w in enumerate(vocab):
            if uni[i] > 0 or w == START:
                out.write(f"1\t{w}\t{math.log(p1[i]):.4f}\t{math.log(gamma2[i]):.4f}\n")
        for k, p in zip(bk[keep2].tolist(), p2[keep2].tolist()):
            bow = gamma3_by_bigram.get(k)
            out.write(f"2\t{vocab[k >> 21]} {vocab[k & mask]}\t{math.log(p):.4f}\t{math.log(bow) if bow else 0.0:.4f}\n")
        for k, p in zip(tk[keep3].tolist(), p3[keep3].tolist()):
            out.write(f"3\t{vocab[k >> 42]} {vocab[(k >> 21) & mask]} {vocab[k & mask]}\t{math.log(p):.4f}\n")
    print(f"model: {int(keep2.sum())} bigrams, {int(keep3.sum())} trigrams -> {out_path}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--work", required=True)
    ap.add_argument("--lexicon", default="app/src/main/assets/dogboard/en.lexicon.tsv")
    ap.add_argument("--out", default="app/src/main/assets/dogboard/en.ngrams.tsv")
    ap.add_argument("--min-bigram", type=int, default=2)
    ap.add_argument("--min-trigram", type=int, default=3)
    ap.add_argument("--fraction", action="append", default=[], metavar="SOURCE=F",
                    help="use only this fraction of a source's documents, e.g. tatoeba=0.3")
    args = ap.parse_args()
    for item in args.fraction:
        name, value = item.split("=")
        FRACTION[name] = float(value)
    os.makedirs(args.work, exist_ok=True)
    extract(args.data, args.work)
    words = []
    with open(args.lexicon, encoding="utf-8") as f:
        for line in f:
            if not line.startswith("#"):
                words.append(line.split("\t")[0].lower())
    vocab = [START, UNK] + sorted(set(words) - {START, UNK})
    counts = count(args.work, vocab)
    kneser_ney(counts, vocab, args.min_bigram, args.min_trigram, args.out)


if __name__ == "__main__":
    main()
