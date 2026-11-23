#!/usr/bin/env python3
"""DogBoard's n-gram file, version 3 (DGN3): the DGN2 model, smaller, every probability the same.

    tools/data/dgn3.py convert IN.dgn2.bin OUT.dgn3.bin      # DGN2 -> DGN3, byte-identical codes and codebooks
    tools/data/dgn3.py verify  IN.dgn2.bin OUT.dgn3.bin [N]  # N random lookups (default 200000) agree exactly

DGN2 (arpa_to_dogboard.py --compact) keeps pairs grouped by their first word (i32 second word + 2 x u8 codes) but
stores every triple as a full i64 key (a << 42 | b << 21 | c) + u8 code: 9 bytes, the first two words repeated in
each. DGN3 groups the triples under their pair, so a triple stores only its third word ("sorting all
trigrams and then starting a word block where you define the word only once"), and word ids take 3 bytes (u24):

    magic "DGN3", V, P (pairs incl. placeholders), T                        i32 x 4
    vocabulary: u16 length + UTF-8 each, then padding to 8                  (as DGN2)
    unigram ln probs f32[V], unigram back-offs f32[V]
    pair prob codebook f32[256], pair back-off codebook f32[256]
    pair offsets by first word i32[V + 1]
    pair second words u24[P]; prob codes u8[P]; back-off codes u8[P]
    placeholder bits u8[ceil(P / 8)]: a pair kept only as its triples' context (its own pair was pruned): no pair
        probability, back-off 0, as DGN2 behaves when the pair is absent (12.6 % of French triple groups)
    triple counts per pair u8[P] (255 = see escapes); escapes: i32 E, then E x (pair index i32, count i32)
    triple starts every 64 pairs i32[ceil(P / 64) + 1] (prefix sums; the last = T), padding to 4
    triple prob codebook f32[256]; triple third words u24[T]; triple prob codes u8[T]

Lookups stay binary searches: a pair inside its first word's range, a triple inside its pair's group (start = the
block's prefix sum + at most 63 counts). Measured on French (49.0 MB DGN2): see the commit adding this file.
"""
import random
import struct
import sys

import numpy as np


def read_dgn2(path):
    d = open(path, "rb").read()
    magic, V, P, T = struct.unpack(">iiii", d[:16])
    assert magic == 0x44474E32, "not a DGN2 file"
    o = 16
    vocab_start = o
    for _ in range(V):
        n = struct.unpack(">H", d[o:o + 2])[0]
        o += 2 + n
    vocab_bytes = d[vocab_start:o]
    o += (-o) % 8
    uni = d[o:o + 8 * V]; o += 8 * V
    pbook = d[o:o + 2048]; o += 2048
    off = np.frombuffer(d, ">i4", V + 1, o).astype(np.int64); o += 4 * (V + 1)
    pw = np.frombuffer(d, ">i4", P, o).astype(np.int64); o += 4 * P
    pp = np.frombuffer(d, "u1", P, o).copy(); o += P
    pb = np.frombuffer(d, "u1", P, o).copy(); o += P
    tbook = d[o:o + 1024]; o += 1024
    o += (-o) % 8
    tk = np.frombuffer(d, ">i8", T, o).astype(np.int64); o += 8 * T
    tp = np.frombuffer(d, "u1", T, o).copy()
    return dict(V=V, vocab=vocab_bytes, uni=uni, pbook=pbook, off=off, pw=pw, pp=pp, pb=pb, tbook=tbook, tk=tk, tp=tp)


def u24(a):
    a = np.asarray(a, dtype=np.int64)
    assert a.max(initial=0) < 1 << 24
    return np.stack([(a >> 16) & 0xFF, (a >> 8) & 0xFF, a & 0xFF], axis=1).astype(np.uint8).tobytes()


def convert(src, dst):
    m = read_dgn2(src)
    V, off, pw = m["V"], m["off"], m["pw"]
    firsts = np.repeat(np.arange(V, dtype=np.int64), np.diff(off))
    real_keys = (firsts << 21) | pw
    tk = m["tk"]
    tab = tk >> 21
    # placeholder pairs for triple contexts whose pair was pruned
    missing = np.setdiff1d(np.unique(tab), real_keys)
    keys = np.concatenate([real_keys, missing])
    placeholder = np.concatenate([np.zeros(len(real_keys), bool), np.ones(len(missing), bool)])
    pp = np.concatenate([m["pp"], np.zeros(len(missing), np.uint8)])
    pb = np.concatenate([m["pb"], np.zeros(len(missing), np.uint8)])
    order = np.argsort(keys, kind="stable")
    keys, placeholder, pp, pb = keys[order], placeholder[order], pp[order], pb[order]
    P = len(keys)
    a = keys >> 21
    offsets = np.searchsorted(a, np.arange(V + 1))
    # triples: already sorted by (a, b, c); count per pair
    pair_of_triple = np.searchsorted(keys, tab)
    assert np.all(keys[pair_of_triple] == tab)
    counts = np.bincount(pair_of_triple, minlength=P)
    esc = np.flatnonzero(counts >= 255)
    c8 = np.minimum(counts, 255).astype(np.uint8)
    starts = np.concatenate([[0], np.cumsum(counts)])
    blocks = starts[::64]
    if (P % 64) != 0 or len(blocks) < P // 64 + 1:
        blocks = np.concatenate([blocks, [starts[-1]]]) if len(blocks) == (P + 63) // 64 else blocks
    assert len(blocks) == (P + 63) // 64 + 1 and blocks[-1] == len(tk)
    with open(dst, "wb") as o:
        o.write(struct.pack(">iiii", 0x44474E33, V, P, len(tk)))
        o.write(m["vocab"])
        o.write(b"\0" * (-o.tell() % 8))
        o.write(m["uni"]); o.write(m["pbook"])
        o.write(offsets.astype(">i4").tobytes())
        o.write(u24(keys & ((1 << 21) - 1)))
        o.write(pp.tobytes()); o.write(pb.tobytes())
        o.write(np.packbits(placeholder, bitorder="little").tobytes())
        o.write(c8.tobytes())
        o.write(struct.pack(">i", len(esc)))
        o.write(np.stack([esc, counts[esc]], axis=1).astype(">i4").tobytes())
        o.write(blocks.astype(">i4").tobytes())
        o.write(b"\0" * (-o.tell() % 4))
        o.write(m["tbook"])
        o.write(u24(tk & ((1 << 21) - 1)))
        o.write(m["tp"].tobytes())
    print(f"{dst}: pairs {len(real_keys)} + {len(missing)} placeholders, triples {len(tk)}, escapes {len(esc)}")


class Dgn3:
    """A reader the app's NgramModel will mirror (verify only)."""

    def __init__(self, path):
        d = self.d = open(path, "rb").read()
        magic, V, P, T = struct.unpack(">iiii", d[:16])
        assert magic == 0x44474E33
        self.V, self.P, self.T = V, P, T
        o = 16
        for _ in range(V):
            o += 2 + struct.unpack(">H", d[o:o + 2])[0]
        o += (-o) % 8
        self.uni_p = np.frombuffer(d, ">f4", V, o); o += 4 * V
        self.uni_b = np.frombuffer(d, ">f4", V, o); o += 4 * V
        self.pbook = np.frombuffer(d, ">f4", 256, o); o += 1024
        self.bbook = np.frombuffer(d, ">f4", 256, o); o += 1024
        self.off = np.frombuffer(d, ">i4", V + 1, o); o += 4 * (V + 1)
        w = np.frombuffer(d, "u1", 3 * P, o).reshape(P, 3).astype(np.int64); o += 3 * P
        self.pw = (w[:, 0] << 16) | (w[:, 1] << 8) | w[:, 2]
        self.pp = np.frombuffer(d, "u1", P, o); o += P
        self.pb = np.frombuffer(d, "u1", P, o); o += P
        self.ph = np.unpackbits(np.frombuffer(d, "u1", (P + 7) // 8, o), bitorder="little")[:P].astype(bool); o += (P + 7) // 8
        self.cnt = np.frombuffer(d, "u1", P, o); o += P
        E = struct.unpack(">i", d[o:o + 4])[0]; o += 4
        e = np.frombuffer(d, ">i4", 2 * E, o).reshape(E, 2); o += 8 * E
        self.esc = {int(i): int(c) for i, c in e}
        nb = (P + 63) // 64 + 1
        self.blocks = np.frombuffer(d, ">i4", nb, o); o += 4 * nb
        o += (-o) % 4
        self.tbook = np.frombuffer(d, ">f4", 256, o); o += 1024
        w = np.frombuffer(d, "u1", 3 * T, o).reshape(T, 3).astype(np.int64); o += 3 * T
        self.tw = (w[:, 0] << 16) | (w[:, 1] << 8) | w[:, 2]
        self.tp = np.frombuffer(d, "u1", T, o)

    def pair_index(self, a, b):
        lo, hi = int(self.off[a]), int(self.off[a + 1])
        i = lo + int(np.searchsorted(self.pw[lo:hi], b))
        return i if i < hi and self.pw[i] == b else -1

    def count(self, i):
        c = int(self.cnt[i])
        return self.esc[i] if c == 255 else c

    def triple_range(self, i):
        blk = i // 64
        s = int(self.blocks[blk]) + sum(self.count(j) for j in range(blk * 64, i))
        return s, s + self.count(i)

    def log_prob(self, a, b, w):
        """P(w | a b), the reader's rule (NgramModel.logProbOf): triple, else back-off(a b) + P(w | b)."""
        i = self.pair_index(a, b) if a >= 0 else -1
        if i >= 0:
            s, e = self.triple_range(i)
            j = s + int(np.searchsorted(self.tw[s:e], w))
            if j < e and self.tw[j] == w:
                return float(self.tbook[self.tp[j]])
        bo = float(self.bbook[self.pb[i]]) if i >= 0 and not self.ph[i] else 0.0
        return bo + self.bigram(b, w)

    def bigram(self, b, w):
        i = self.pair_index(b, w)
        if i >= 0 and not self.ph[i]:
            return float(self.pbook[self.pp[i]])
        return float(self.uni_b[b]) + float(self.uni_p[w])


def dgn2_log_prob(m, pbook, bbook, tbook, uni_p, uni_b, a, b, w):
    off, pw = m["off"], m["pw"]

    def pair(x, y):
        lo, hi = int(off[x]), int(off[x + 1])
        i = lo + int(np.searchsorted(pw[lo:hi], y))
        return i if i < hi and pw[i] == y else -1
    if a >= 0:
        k = (a << 42) | (b << 21) | w
        j = int(np.searchsorted(m["tk"], k))
        if j < len(m["tk"]) and m["tk"][j] == k:
            return float(tbook[m["tp"][j]])
    i = pair(a, b) if a >= 0 else -1
    bo = float(bbook[m["pb"][i]]) if i >= 0 else 0.0
    i2 = pair(b, w)
    big = float(pbook[m["pp"][i2]]) if i2 >= 0 else float(uni_b[b]) + float(uni_p[w])
    return bo + big


def verify(src, dst, n):
    m = read_dgn2(src)
    r = Dgn3(dst)
    pbook, bbook = np.frombuffer(m["pbook"], ">f4", 256), np.frombuffer(m["pbook"], ">f4", 256, 1024)
    tbook = np.frombuffer(m["tbook"], ">f4", 256)
    V = m["V"]
    uni_p, uni_b = np.frombuffer(m["uni"], ">f4", V), np.frombuffer(m["uni"], ">f4", V, 4 * V)
    rnd = random.Random(7)
    tk = m["tk"]
    bad = 0
    for k in range(n):
        if k % 3 == 0:  # a stored triple
            t = int(tk[rnd.randrange(len(tk))]); a, b, w = t >> 42, (t >> 21) & 0x1FFFFF, t & 0x1FFFFF
        elif k % 3 == 1:  # a stored pair with a random third word
            i = rnd.randrange(len(m["pw"])); b = int(np.searchsorted(m["off"], i, side="right") - 1)
            a, w = rnd.randrange(V), int(m["pw"][i])
        else:
            a, b, w = rnd.randrange(V), rnd.randrange(V), rnd.randrange(V)
        x = dgn2_log_prob(m, pbook, bbook, tbook, uni_p, uni_b, a, b, w)
        y = r.log_prob(a, b, w)
        if x != y:
            bad += 1
            if bad <= 5:
                print("MISMATCH", a, b, w, x, y)
    print(f"{n} lookups, {bad} mismatches")
    return bad == 0


if __name__ == "__main__":
    if sys.argv[1] == "convert":
        convert(sys.argv[2], sys.argv[3])
    else:
        sys.exit(0 if verify(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 200000) else 1)
