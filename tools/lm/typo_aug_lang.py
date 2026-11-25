#!/usr/bin/env python3
"""Typo-augmented token stream for any language's student (tools/lm/typo_aug_lang.py; the English typo_aug.py was
A-Z only, its typos from a QWERTY model). Every other line of lm-text/<lang>/train.txt with each word (2+ letters)
given a typo with probability P by the pack test sets' simulator (tools/i18n/sim/sim_typos7, fitted on real phone
typing, on the language's layout rows and word list). Encoded with the language's R0 tokenizer (tok_prep_ru.encode,
word by word: SentencePiece splits on whitespace, so the pieces are the same as the line's). The student sees the typo
in its context but is never trained to predict it: a parallel uint8 mask marks the tokens to predict (0 = a piece of
a corrupted word; a word's capital token is still predicted, a typo doesn't change it). The word after a typo is
predicted normally, which is what teaches robustness.

    TOK_LANG=es SIM_ROWS=qwertyuiop,asdfghjklñ,zxcvbnm typo_aug_lang.py LEXICON.tsv
Writes lm-text/<lang>-R0/train_typo_sim.{bin,mask}, the stream train.py reads with AUG=<share> AUGNAME=train_typo_sim.
"""
import json
import os
import random
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("RU_VARIANT", "R0")
sys.path.insert(0, HERE)
import caps  # noqa: E402
import tok_prep_ru as tp  # noqa: E402

SIM = os.path.join(HERE, "..", "i18n", "sim")
sys.path.insert(0, SIM)
import sim_typos7 as st  # noqa: E402

P, EVERY = 0.15, 2
LANG = tp.LANG
WORD = re.compile(r"^([\W\d_]*)([^\W\d_]{2,})([\W\d_]*)$")


def main():
    lexicon = sys.argv[1]
    rows = os.environ.get("SIM_ROWS", "qwertyuiop,asdfghjkl,zxcvbnm").split(",")
    st.set_layout(rows, [0.0, 0.25, 0.75])
    sim = st.Sim(json.load(open(os.path.join(SIM, "typo_model7.json"))), lexicon, rows, [0.0, 0.25, 0.75])
    rnd = random.Random(29)
    name = os.environ.get("AUGNAME", "train_typo_sim")
    n_tok = n_masked = 0
    with open(os.path.join(tp.L, "train.txt"), encoding="utf-8") as f, \
            open(os.path.join(tp.OUT, name + ".bin"), "wb") as ob, open(os.path.join(tp.OUT, name + ".mask"), "wb") as om:
        ids, mask = [], []
        for li, line in enumerate(f):
            if li % EVERY:
                continue
            words = line.split()
            if not words:
                continue
            ids.append(1); mask.append(1)
            for w in words:
                m = WORD.match(w)
                bad = False
                if m and rnd.random() < P:
                    t = sim.typo(m.group(2), rnd)
                    if t != m.group(2):
                        w, bad = m.group(1) + t + m.group(3), True
                for t in tp.encode(w):
                    ids.append(t)
                    mask.append(1 if (not bad or t == caps.CAP) else 0)
            if len(ids) > 2_000_000:
                ob.write(np.array(ids, dtype=np.uint16).tobytes()); om.write(np.array(mask, dtype=np.uint8).tobytes())
                n_tok += len(ids); n_masked += mask.count(0); ids, mask = [], []
        ob.write(np.array(ids, dtype=np.uint16).tobytes()); om.write(np.array(mask, dtype=np.uint8).tobytes())
        n_tok += len(ids); n_masked += mask.count(0)
    print(f"{LANG}: {n_tok:,} tokens, {n_masked:,} masked ({100 * n_masked / max(1, n_tok):.1f} %)")


if __name__ == "__main__":
    main()
