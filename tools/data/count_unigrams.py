#!/usr/bin/env python3
"""Case-sensitive word counts from FineWeb parquet shards, streamed one shard at a time.

    uv run --with pyarrow tools/data/count_unigrams.py --work DIR [--shards 0-14] [--workers 4]

For each shard of HuggingFaceFW/fineweb sample/10BT (ODC-By 1.0): download it into DIR, count
words in parallel (each worker one row-group range), write DIR/counts/shard_NN.tsv, delete the
parquet. Then merge all shard files into DIR/counts/unigrams.tsv (word, count; most frequent
first). A shard whose counts file exists is skipped, so the job can be stopped and resumed.

Words: Latin-1 letters with inner apostrophes ("don't", "café"); case kept, so the counts also
give each word's casings. Numbers and other symbols are not counted.
"""
import argparse
import collections
import multiprocessing as mp
import os
import re
import shutil
import subprocess
import sys
import urllib.request

import pyarrow.parquet as pq

BASE = "https://huggingface.co/datasets/HuggingFaceFW/fineweb/resolve/main/sample/10BT/"
WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’][A-Za-zÀ-ÖØ-öø-ÿ]+)*")
MIN_FREE_HOME_GB = 15


def shard_name(i: int) -> str:
    return f"{i:03d}_00000.parquet"


def count_groups(args):
    path, groups = args
    f = pq.ParquetFile(path)
    c = collections.Counter()
    for g in groups:
        for text in f.read_row_group(g, columns=["text"]).column("text").to_pylist():
            c.update(m.replace("’", "'") for m in WORD.findall(text))
    return c


def home_free_gb() -> float:
    return shutil.disk_usage(os.path.expanduser("~")).free / 1e9


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True)
    ap.add_argument("--shards", default="0-14")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    lo, hi = (int(x) for x in args.shards.split("-"))
    counts_dir = os.path.join(args.work, "counts")
    os.makedirs(counts_dir, exist_ok=True)

    for i in range(lo, hi + 1):
        out = os.path.join(counts_dir, f"shard_{i:02d}.tsv")
        if os.path.exists(out):
            continue
        if home_free_gb() < MIN_FREE_HOME_GB:
            sys.exit(f"stop: /home has {home_free_gb():.1f} GB free")
        path = os.path.join(args.work, shard_name(i))
        if not os.path.exists(path):
            print(f"download shard {i}", flush=True)
            tmp = path + ".part"
            subprocess.run(["curl", "-sSL", "--retry", "5", "-o", tmp, BASE + shard_name(i)], check=True)
            os.rename(tmp, path)
        f = pq.ParquetFile(path)
        n = f.num_row_groups
        chunks = [list(range(k, n, args.workers)) for k in range(args.workers)]
        total = collections.Counter()
        with mp.Pool(args.workers) as pool:
            for c in pool.imap_unordered(count_groups, [(path, ch) for ch in chunks if ch]):
                total.update(c)
        with open(out + ".part", "w", encoding="utf-8") as w:
            for word, n_ in total.items():
                w.write(f"{word}\t{n_}\n")
        os.rename(out + ".part", out)
        os.remove(path)
        print(f"shard {i}: {sum(total.values())} words, {len(total)} distinct", flush=True)

    merged = collections.Counter()
    for name in sorted(os.listdir(counts_dir)):
        if name.startswith("shard_") and name.endswith(".tsv"):
            with open(os.path.join(counts_dir, name), encoding="utf-8") as f:
                for line in f:
                    w, n_ = line.rstrip("\n").split("\t")
                    merged[w] += int(n_)
    with open(os.path.join(counts_dir, "unigrams.tsv"), "w", encoding="utf-8") as w:
        w.write("#source FineWeb sample-10BT (ODC-By 1.0); tools/data/count_unigrams.py\n")
        for word, n_ in merged.most_common():
            w.write(f"{word}\t{n_}\n")
    print(f"merged: {len(merged)} distinct, {sum(merged.values())} words", flush=True)


if __name__ == "__main__":
    main()
