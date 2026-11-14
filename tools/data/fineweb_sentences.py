#!/usr/bin/env python3
"""Streams FineWeb sample-10BT text as one sentence per line, for KenLM's lmplz.

    uv run --with pyarrow tools/data/fineweb_sentences.py --work DIR --shards 0-2 | lmplz -o 3 ...

Downloads each shard into DIR (resumable), writes its sentences to stdout and deletes it.
Tokens are lowercase words (Latin-1 letters with inner apostrophes, ’ as '), the same tokens
the engine's bigram model uses; anything else splits the text, and . ! ? and line breaks end a
sentence. FineWeb is ODC-By 1.0.
"""
import argparse
import os
import re
import subprocess
import sys

import pyarrow.parquet as pq

BASE = "https://huggingface.co/datasets/HuggingFaceFW/fineweb/resolve/main/sample/10BT/"
WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’][A-Za-zÀ-ÖØ-öø-ÿ]+)*")
SENTENCE_END = re.compile(r"[.!?\n]+")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--shards", default="0-2")
    args = ap.parse_args()
    lo, hi = (int(x) for x in args.shards.split("-"))
    out = sys.stdout
    for i in range(lo, hi + 1):
        name = f"{i:03d}_00000.parquet"
        path = os.path.join(args.work, name)
        if not os.path.exists(path):
            subprocess.run(["curl", "-sSL", "--retry", "5", "-o", path + ".part", BASE + name], check=True)
            os.rename(path + ".part", path)
        f = pq.ParquetFile(path)
        for g in range(f.num_row_groups):
            for text in f.read_row_group(g, columns=["text"]).column("text").to_pylist():
                for sentence in SENTENCE_END.split(text):
                    words = WORD.findall(sentence)
                    if len(words) >= 2:
                        out.write(" ".join(w.lower().replace("’", "'") for w in words))
                        out.write("\n")
        os.remove(path)
        print(f"shard {i} done", file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
