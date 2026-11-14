#!/usr/bin/env python3
"""Builds held-out evaluation text for the typing engine (one sentence per line).

    python3 tools/data/build_eval_sets.py OUT_DIR [--chat 3000] [--web 3000]

- wildchat_human.txt: English human (user) turns from allenai/WildChat-1M (ODC-By 1.0), fetched
  through the Hugging Face datasets-server rows API from offsets spread over the dataset, so
  nothing large is downloaded. Assistant turns (model output) are never used.
- fineweb_heldout.txt: sentences from FineWeb sample-10BT rows 9.0M-9.6M (well past shards
  000-002, which the trigram model is built from), through the same rows API.
Short, long and URL/markup lines are dropped. The output stays outside the repository.
"""
import argparse
import json
import os
import random
import re
import sys
import time
import urllib.request

API = "https://datasets-server.huggingface.co/rows?dataset={ds}&config={cfg}&split=train&offset={off}&length=100"
SENT_SPLIT = re.compile(r"(?<=[.!?])\s+|\n+")
WORDS = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)*")


def rows(ds, cfg, off):
    url = API.format(ds=ds, cfg=cfg, off=off)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return json.load(r).get("rows", [])
        except Exception as e:  # rate limits, timeouts
            time.sleep(5 * (attempt + 1))
    print(f"skip offset {off}", file=sys.stderr)
    return []


def good(sentence):
    s = sentence.strip()
    if "http" in s or "www." in s or "<" in s or "{" in s or "```" in s:
        return None
    n = len(WORDS.findall(s))
    # pasted code and data, not typing: mostly non-letters, or code markers
    letters = sum(c.isalpha() or c == " " for c in s)
    if letters < 0.85 * len(s) or any(m in s for m in ("//", "0x", "==", "_", "[", "]", "#", ";", "|")):
        return None
    return s if 3 <= n <= 30 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--chat", type=int, default=3000)
    ap.add_argument("--web", type=int, default=3000)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rng = random.Random(7)

    chat = []
    for off in rng.sample(range(0, 830_000, 100), 400):
        for r in rows("allenai/WildChat-1M", "default", off):
            row = r["row"]
            if row.get("language") != "English":
                continue
            for turn in row["conversation"]:
                if turn["role"] != "user":
                    continue
                for s in SENT_SPLIT.split(turn["content"].replace("’", "'")):
                    g = good(s)
                    if g:
                        chat.append(g)
        if len(chat) >= args.chat * 2:
            break
    rng.shuffle(chat)
    with open(os.path.join(args.out, "wildchat_human.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(chat[: args.chat]) + "\n")
    print(f"wildchat: {min(len(chat), args.chat)} sentences", file=sys.stderr)

    web = []
    for off in rng.sample(range(9_000_000, 9_600_000, 100), 200):
        for r in rows("HuggingFaceFW/fineweb", "sample-10BT", off):
            for s in SENT_SPLIT.split(r["row"]["text"].replace("’", "'")):
                g = good(s)
                if g:
                    web.append(g)
        if len(web) >= args.web * 2:
            break
    rng.shuffle(web)
    with open(os.path.join(args.out, "fineweb_heldout.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(web[: args.web]) + "\n")
    print(f"fineweb: {min(len(web), args.web)} sentences", file=sys.stderr)


if __name__ == "__main__":
    main()
