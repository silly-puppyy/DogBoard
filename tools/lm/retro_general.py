"""A general hindsight retrocorrection, measured (tools/lm/retro_general.py).

    retro_general.py CKPT TATOEBA_SENTENCES LEXICON [-n 1500]

User 2026-11-18: one general retrocorrection instead of hand-made lists (as iOS: the language model judges
word sequences; no grammar rules). For a finished word, its alternatives are found from the lexicon alone,
so any language works the same way:
  - sort-key twins: words equal once apostrophes, case and accents are ignored (its/it's, were/we're)
  - one-edit typo alternatives: an adjacent key, a missed letter, an extra letter, two letters swapped
    (stand-ins for the keyboard decoder's candidates, which come with their own geometry scores)
Each alternative c of the typed word t is read with the k words after it:
    margin(c) = ln P(c + after | before) - ln P(t + after | before) - penalty * edits(t, c)
and t changes to the best c when its margin > threshold (twins: 0 edits).
Text: Tatoeba English (edited, conversational; not in the model's training data).
  clean:       every word of clean sentences (a change = a false alarm)
  apostrophe:  apostrophe words with the apostrophe dropped (a fix = the right word back)
  real-word:   a word turned into ANOTHER real word by one typo (from -> form, than -> then): autocorrect
               keeps those at the word end, so only a look back can fix them
Reported per k (1 = iOS's one word ahead, 3 = ours) and penalty: false alarms vs fixes per threshold.
"""
import argparse
import math
import os
import random
import re
import sys
import unicodedata

import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sentencepiece as spm  # noqa: E402
import caps  # noqa: E402
from student import Config, Student  # noqa: E402

ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
POS = {c: (r, i + 0.5 * r) for r, row in enumerate(ROWS) for i, c in enumerate(row)}
NEAR = {a: {b for b in POS if b != a and abs(POS[a][0] - POS[b][0]) <= 1 and abs(POS[a][1] - POS[b][1]) <= 1.0} for a in POS}


def sort_key(w):
    w = unicodedata.normalize("NFKD", w)
    return "".join(c for c in w.lower() if c.isalpha())


def edits1(w):
    """One-edit variants of w (lowercase): adjacent-key substitution, missed letter (insert), extra letter
    (delete), swapped neighbours."""
    out = set()
    for i, ch in enumerate(w):
        for n in NEAR.get(ch, ()):
            out.add(w[:i] + n + w[i + 1:])
        out.add(w[:i] + w[i + 1:])
        if i + 1 < len(w):
            out.add(w[:i] + w[i + 1] + w[i] + w[i + 2:])
    for i in range(len(w) + 1):
        for c in "abcdefghijklmnopqrstuvwxyz":
            out.add(w[:i] + c + w[i:])
    out.discard(w)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ckpt")
    ap.add_argument("sentences")
    ap.add_argument("lexicon")
    ap.add_argument("-n", type=int, default=1500, help="cases per set")
    ap.add_argument("--clean", type=int, default=0, help="clean words (default: -n)")
    ap.add_argument("--prefilter", type=int, default=0, help="only the K most plausible alternatives (frequency - 5 x typos) reach the model")
    a = ap.parse_args()
    # lexicon: the 120k most frequent words (casing kept) and by sort key
    freq = {}
    for line in open(a.lexicon, encoding="utf-8"):
        if line.startswith("#"):
            continue
        w, c = line.rstrip("\n").split("\t")
        freq[w] = freq.get(w, 0) + int(c)
    top = dict(sorted(freq.items(), key=lambda kv: -kv[1])[:120000])
    lower = {}
    for w in top:
        lower.setdefault(w.lower(), w)
    by_key = {}
    for w in top:
        by_key.setdefault(sort_key(w), set()).add(w)

    def alternatives(t):
        """(candidate, edits) for typed word t: its sort-key twins (0) and real-word one-edit variants (1).
        None for a word kept on purpose: not in the lexicon, or capitalised mid-sentence (a name)."""
        out = {}
        if t.lower() not in lower or (t[0].isupper() and t != "I" and not t.startswith("I'")):
            return out
        for w in by_key.get(sort_key(t), ()):
            if w.lower() != t.lower():
                out[w] = 0
        if t.isalpha() and t.isascii():
            for v in edits1(t.lower()):
                if v in lower and lower[v].lower() != t.lower() and lower[v] not in out:
                    out[lower[v]] = 1
        return out

    s = torch.load(a.ckpt, map_location="cuda")
    net = Student(Config(**s["config"])).cuda().eval()
    net.load_state_dict(s["model"])
    sp = spm.SentencePieceProcessor(model_file=os.path.join(s["tokdir"], "sp16k.model"))
    enc = (lambda x: caps.encode(sp, x)) if s.get("caps") else (lambda x: sp.encode(x))

    @torch.no_grad()
    def lp(ctx, conts):
        c = [1] + (enc(ctx) if ctx.strip() else [])
        seqs = [c + enc(x) for x in conts]
        T = max(map(len, seqs))
        ids = torch.zeros(len(seqs), T, dtype=torch.long, device="cuda")
        for i, q in enumerate(seqs):
            ids[i, :len(q)] = torch.tensor(q)
        o = torch.log_softmax(net(ids).float(), -1)
        return [sum(float(o[i, j - 1, q[j]]) for j in range(len(c), len(q))) for i, q in enumerate(seqs)]

    rng = random.Random(3)
    lines = [l.split("\t")[-1].replace("’", "'") for l in open(a.sentences, encoding="utf-8").read().splitlines()]
    rng.shuffle(lines)
    lines = [l for l in lines if 6 <= len(l.split()) <= 20]
    sets = {"clean": [], "apostrophe": [], "real-word": []}
    n_clean = a.clean or a.n
    for line in lines:
        words = line.split(" ")
        for i in range(1, len(words) - 3):
            core = re.sub(r"[^\w']", "", words[i])
            if not core or core != words[i]:
                continue  # punctuation attached: skip (the look back doesn't cross it)
            before, after = " ".join(words[:i]), words[i + 1:i + 4]
            if any(re.search(r"[.!?;:]$", x) for x in after[:-1]):
                continue
            if len(sets["clean"]) < n_clean and rng.random() < 0.25:
                sets["clean"].append((before, core, core, after))
            if "'" in core and len(sets["apostrophe"]) < a.n:
                dropped = core.replace("'", "")
                if dropped.lower() in lower:
                    sets["apostrophe"].append((before, dropped.lower() if core[0].islower() else dropped, core, after))
            if core.isalpha() and core.isascii() and len(core) >= 3 and len(sets["real-word"]) < a.n and rng.random() < 0.3:
                vs = [v for v in edits1(core.lower()) if v in lower and len(v) >= 2]
                if vs:
                    typo = rng.choice(sorted(vs))
                    sets["real-word"].append((before, lower[typo] if core[0].islower() else lower[typo].capitalize(), core, after))
        if len(sets["clean"]) >= n_clean and len(sets["apostrophe"]) >= a.n and len(sets["real-word"]) >= a.n:
            break
    print({k: len(v) for k, v in sets.items()}, flush=True)

    # margins for every case, k in (1, 3): per case the list of (candidate, edits, raw lm margin)
    scored = {k: {name: [] for name in sets} for k in (1, 3)}
    for name, cases in sets.items():
        for before, typed, want, after in cases:
            alts = alternatives(typed)
            if not alts:
                for k in (1, 3):
                    scored[k][name].append((typed, want, []))
                continue
            names = list(alts)
            if a.prefilter:
                import math as _m
                names = sorted(names, key=lambda c: -(_m.log(top.get(c, 1)) - 5.0 * alts[c]))[:a.prefilter]
            for k in (1, 3):
                tail = " " + " ".join(after[:k])
                v = lp(before, [typed + tail] + [c + tail for c in names])
                scored[k][name].append((typed, want, [(c, alts[c], v[j + 1] - v[0]) for j, c in enumerate(names)]))

    def outcome(typed, want, cands, penalty, th):
        best = max(cands, key=lambda x: x[2] - penalty * x[1], default=None)
        if best is None or best[2] - penalty * best[1] <= th:
            return typed
        return best[0]

    def norm(w):
        return w.lower()
    if os.environ.get("SHOW_ALARMS"):
        pen, th = (float(x) for x in os.environ["SHOW_ALARMS"].split(","))
        print(f"false alarms at 3 words, penalty {pen}, threshold {th}:")
        for (t, w, c), (before, _, _, after) in zip(scored[3]["clean"], sets["clean"]):
            o = outcome(t, w, c, pen, th)
            if norm(o) != norm(t):
                print(f"   ...{before[-40:]} [{t} -> {o}] {' '.join(after)}")
    for k in (1, 3):
        print(f"\n== {'1 word of hindsight (iOS)' if k == 1 else '3 words of hindsight'}")
        for penalty in (3.0, 4.0, 5.0):
            print(f"  edit penalty {penalty}:   threshold  false alarms   apostrophes fixed   real-word typos fixed")
            for th in (0.0, 0.5, 1.0, 1.5, 2.0, 3.0):
                fa = sum(norm(outcome(t, w, c, penalty, th)) != norm(t) for t, w, c in scored[k]["clean"]) / len(scored[k]["clean"])
                ap = sum(norm(outcome(t, w, c, penalty, th)) == norm(w) for t, w, c in scored[k]["apostrophe"]) / max(1, len(scored[k]["apostrophe"]))
                rw = sum(norm(outcome(t, w, c, penalty, th)) == norm(w) for t, w, c in scored[k]["real-word"]) / max(1, len(scored[k]["real-word"]))
                # over the cases the look back is for (a lowercase dictionary word typed): apostrophe drops
                # leaving a non-word go to the word-end autocorrect, typos in names are left alone
                ape = [(t, w, c) for t, w, c in scored[k]["apostrophe"] if c]
                rwe = [(t, w, c) for t, w, c in scored[k]["real-word"] if c]
                ap2 = sum(norm(outcome(t, w, c, penalty, th)) == norm(w) for t, w, c in ape) / max(1, len(ape))
                rw2 = sum(norm(outcome(t, w, c, penalty, th)) == norm(w) for t, w, c in rwe) / max(1, len(rwe))
                print(f"                     {th:4.1f}      {100 * fa:6.2f} %        {100 * ap:6.1f} % ({100 * ap2:5.1f} % of {len(ape)})   {100 * rw:6.1f} % ({100 * rw2:5.1f} % of {len(rwe)})")


if __name__ == "__main__":
    main()
