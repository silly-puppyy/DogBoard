#!/usr/bin/env python3
"""FUTO swipe dataset -> training shards for train.py (tools/swipe/prepare_futo.py).

    prepare_futo.py --futo DIR --out DIR [--spacing 0.3] [--shard 200000]

DIR is huggingface.co/datasets/futo-org/swipe.futo.org (MIT, (c) 2025 FUTO): train/dev/test.jsonl, one
swipe per line: word, canvas size, and the path as normalised points (x, y in 0..1 over the letter
area; their qwerty.json: keys 0.1 wide, rows a third high). A path becomes key units (x * 10, y * 3),
resampled every --spacing key widths along its length, with the step to the next point:
features (x, y, dx, dy). Words are lowercased; only a-z words are kept.
"""
import argparse
import json
import math
import os

import torch

MAX_POINTS = 160


def resample(pts, spacing):
    """Points every `spacing` along the path (first and last kept); a tap-like path stays two points."""
    if len(pts) == 1:
        return [pts[0], pts[0]]
    out = [pts[0]]
    carry = 0.0
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        seg = math.hypot(x1 - x0, y1 - y0)
        if seg == 0:
            continue
        d = spacing - carry
        while d <= seg:
            t = d / seg
            out.append((x0 + t * (x1 - x0), y0 + t * (y1 - y0)))
            d += spacing
        carry = seg - (d - spacing)
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    if len(out) == 1:
        out.append(out[0])
    return out[:MAX_POINTS]


def features(path, spacing):
    pts = resample([(p["x"] * 10.0, p["y"] * 3.0) for p in path], spacing)
    f = []
    for i, (x, y) in enumerate(pts):
        nx, ny = pts[i + 1] if i + 1 < len(pts) else pts[i]
        f.append((x, y, nx - x, ny - y))
    return torch.tensor(f, dtype=torch.float32)


def convert(src, dst_prefix, spacing, shard):
    items, n, kept, part = [], 0, 0, 0
    with open(src, encoding="utf-8") as fh:
        for line in fh:
            n += 1
            d = json.loads(line)
            w = d.get("word", "").lower()
            path = d.get("data") or []
            if not w or not w.isascii() or not w.isalpha() or not path or len(w) > 24:
                continue
            items.append((features(path, spacing), w))
            kept += 1
            if shard and len(items) >= shard:
                torch.save(items, f"{dst_prefix}-{part}.pt")
                part += 1
                items = []
    if items or part == 0:
        torch.save(items, f"{dst_prefix}-{part}.pt" if shard else f"{dst_prefix}.pt")
    print(f"{os.path.basename(src)}: {n} swipes, {kept} kept", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--futo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--spacing", type=float, default=0.3)
    ap.add_argument("--shard", type=int, default=200000)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    convert(os.path.join(a.futo, "dev.jsonl"), os.path.join(a.out, "dev"), a.spacing, 0)
    convert(os.path.join(a.futo, "test.jsonl"), os.path.join(a.out, "test"), a.spacing, 0)
    convert(os.path.join(a.futo, "train.jsonl"), os.path.join(a.out, "train"), a.spacing, a.shard)


if __name__ == "__main__":
    main()
