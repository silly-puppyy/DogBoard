"""Lexicon-constrained CTC decoding for SwipeNet (tools/swipe/decode.py), as iOS decodes swipes: a prefix
beam over a letter tree of the lexicon, the network's per-point letter probabilities, and each word's
prior (unigram frequency, weight `alpha`; iOS's lmAlpha 0.434 x ln 10 on log10 = 1.0 on natural logs).
The sentence context (trigram, neural model) comes after, in the keyboard, as for taps.
"""
import math

import numpy as np

NEG = -1e30


def logsumexp(a, b):
    if a < b:
        a, b = b, a
    return a if b <= NEG else a + math.log1p(math.exp(b - a))


class Trie:
    def __init__(self):
        self.next = {}
        self.word = None   # the word ending here
        self.prior = NEG   # its log prior

    @staticmethod
    def build(words_with_logp):
        root = Trie()
        for w, lp in words_with_logp:
            node = root
            for c in w:
                node = node.next.setdefault(c, Trie())
            node.word, node.prior = w, max(node.prior, lp)
        return root


def load_lexicon(path, top=80000):
    """DogBoard's lexicon (word<TAB>count), lowercased a-z words, the `top` most frequent; log share."""
    counts = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            w, n = line.rstrip("\n").split("\t")
            w = w.lower()
            if w.isascii() and w.isalpha():
                counts[w] = counts.get(w, 0) + int(n)
    best = sorted(counts.items(), key=lambda kv: -kv[1])[:top]
    total = sum(n for _, n in best)
    return [(w, math.log(n / total)) for w, n in best]


def beam(logp: np.ndarray, root: Trie, alphabet: str, width=48, alpha=1.0, top=3, prune=-8.0):
    """logp: (T, 27) natural log-probs (0 = blank). Returns [(word, score)] best first."""
    # beams: node -> [log p ending in blank, log p ending in a letter]
    beams = {root: [0.0, NEG]}
    last_char = {root: None}
    for t in range(logp.shape[0]):
        row = logp[t]
        allowed = [k for k in range(1, len(alphabet) + 1) if row[k] > prune]
        nxt = {}
        nlast = {}
        for node, (pb, pnb) in beams.items():
            total = logsumexp(pb, pnb)
            # stay: blank, or repeat the same letter
            e = nxt.setdefault(node, [NEG, NEG]); nlast[node] = last_char[node]
            e[0] = logsumexp(e[0], total + row[0])
            lc = last_char[node]
            if lc is not None:
                e[1] = logsumexp(e[1], pnb + row[lc])
            # extend with a letter the tree allows
            for k in allowed:
                c = alphabet[k - 1]
                child = node.next.get(c)
                if child is None:
                    continue
                # a repeated letter needs a blank in between
                p = (pb if lc == k else total) + row[k]
                ce = nxt.setdefault(child, [NEG, NEG]); nlast[child] = k
                ce[1] = logsumexp(ce[1], p)
        ranked = sorted(nxt.items(), key=lambda kv: -logsumexp(*kv[1]))[:width]
        beams = dict(ranked)
        last_char = {n: nlast[n] for n in beams}
    words = [(n.word, logsumexp(*s) + alpha * n.prior) for n, s in beams.items() if n.word]
    words.sort(key=lambda ws: -ws[1])
    return words[:top]
