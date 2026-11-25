#!/usr/bin/env python3
"""Code-switching test cases (tools/i18n/make_codeswitch_set.py): people's own WildChat messages that mix English with
another language (at least MIN words only English has and MIN only the other has, by the two word lists), each correct
word a case for TypedProbeTest (DOGBOARD_PROBE_CASES: "previous words<TAB>word"; key-centre taps, so only words
the QWERTY letters type). A word the keyboard changes is a broken word: what a combined keyboard (English + French)
does to the other language's words, and what English alone does to them.

    make_codeswitch_set.py <en.lexicon.tsv> <xx.lexicon.tsv> <wildchat parquet dir> <out.tsv> <WildChat language: French> [cases = 3000]
Prints per case its language (en / xx / both) to <out.tsv>.lang, line for line.
"""
import glob
import random
import re
import sys

import pyarrow.parquet as pq

MIN = 3
TOP = 40000  # a word "only English has": among English's TOP and not in the other list at all (and the other way)
TOKEN = re.compile(r"^[^\w]*([A-Za-zÀ-ɏ'’]+)[^\w]*$")


def words_of(path, top=None):
    out = {}
    for i, line in enumerate(open(path, encoding="utf-8")):
        if line.startswith("#") or "\t" not in line:
            continue
        w = line.split("\t", 1)[0].lower()
        out.setdefault(w, len(out))
    ranked = sorted(out, key=out.get)
    return set(ranked[:top]) if top else set(ranked)


def main():
    en_path, xx_path, wild, out, xx_name = sys.argv[1:6]
    n = int(sys.argv[6]) if len(sys.argv) > 6 else 3000
    en_all, xx_all = words_of(en_path), words_of(xx_path)
    en_top, xx_top = words_of(en_path, TOP), words_of(xx_path, TOP)
    only_en, only_xx = en_top - xx_all, xx_top - en_all
    cases, langs, messages = [], [], 0
    rnd = random.Random(5)
    for f in sorted(glob.glob(wild + "/*.parquet")):
        for batch in pq.ParquetFile(f).iter_batches(columns=["conversation"], batch_size=2000):
            for conv in batch.column(0).to_pylist():
                for turn in conv or []:
                    # the turn labelled English or the other language (web word lists hold foreign words: Spanish and
                    # Portuguese messages passed the word test as "French")
                    if turn.get("role") != "user" or turn.get("language") not in ("English", xx_name):
                        continue
                    text = turn.get("content") or ""
                    toks = text.split()
                    if not 6 <= len(toks) <= 60:
                        continue
                    ws = [m.group(1).replace("’", "'") for t in toks if (m := TOKEN.match(t))]
                    low = [w.lower() for w in ws]
                    if sum(w in only_en for w in low) < MIN or sum(w in only_xx for w in low) < MIN:
                        continue
                    messages += 1
                    for i, w in enumerate(ws):
                        # mid-message, written in lower case, letters the QWERTY keys type
                        if i == 0 or not w.isascii() or not w.isalpha() or len(w) < 2 or w != w.lower():
                            continue
                        lang = "en" if w in only_en else "xx" if w in only_xx else "both"
                        cases.append(f"{' '.join(ws[max(0, i - 3):i])}\t{w}\n")
                        langs.append(lang + "\n")
        if messages >= 4 * n:
            break
    idx = list(range(len(cases)))
    rnd.shuffle(idx)
    idx = idx[:n]
    open(out, "w", encoding="utf-8").writelines(cases[i] for i in idx)
    open(out + ".lang", "w").writelines(langs[i] for i in idx)
    print(f"{messages} mixed messages, {len(cases)} words; kept {len(idx)}", file=sys.stderr)


if __name__ == "__main__":
    main()
