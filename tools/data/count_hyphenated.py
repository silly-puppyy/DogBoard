#!/usr/bin/env python3
"""Case-sensitive counts of hyphenated words (well-known, T-shirt, brother-in-law) in FineWeb parquet shards.

    uv run --with pyarrow tools/data/count_hyphenated.py SHARD.parquet [...] --out hyphenated.tsv [--workers 3]

count_unigrams.py splits words at hyphens, so its counts have none; this counts only the joined forms (letters with
inner hyphens, two to four parts), one shard per worker, and writes "word<TAB>count" most frequent first. The
lexicon builder takes them with --hyphenated (iOS's en_US lexicon has ~1000 such words; SCOWL's lists have none).
"""
import argparse
import re
from collections import Counter
from multiprocessing import Pool

L = "A-Za-zÀ-ÖØ-öø-ÿ"
HYPH = re.compile(rf"(?<![{L}\-'’])[{L}]+(?:-[{L}]+){{1,3}}(?![{L}\-'’])")


def count(path: str) -> Counter:
    import pyarrow.parquet as pq
    c = Counter()
    f = pq.ParquetFile(path)
    for rg in range(f.num_row_groups):
        for text in f.read_row_group(rg, columns=["text"]).column("text").to_pylist():
            c.update(HYPH.findall(text))
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("shards", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--workers", type=int, default=3)
    ap.add_argument("--min", type=int, default=5)
    a = ap.parse_args()
    total = Counter()
    with Pool(a.workers) as pool:
        for c in pool.imap_unordered(count, a.shards):
            total.update(c)
            print(f"shard done: {len(total)} distinct so far", flush=True)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("#source FineWeb sample-10BT (ODC-By 1.0) shards: " + " ".join(s.rsplit("/", 1)[-1] for s in a.shards) + "; tools/data/count_hyphenated.py\n")
        for w, n in total.most_common():
            if n >= a.min:
                f.write(f"{w}\t{n}\n")


if __name__ == "__main__":
    main()
