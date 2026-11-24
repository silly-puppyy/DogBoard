#!/usr/bin/env python3
"""The app's layouts and word lists in the training format (tools/swipe/export_app_layouts.py): a FUTO-style layout json
(letter, cx, cy, rx, ry in 0..1, as eval_app_layouts.table draws them) and a lexicon of words spelled as keys
(word<TAB>count), per language, for prepare_synth.py and uni.py.

    export_app_layouts.py --out DIR --langs ar,fa,he,ru,uk,fi,el,bg
"""
import argparse
import json
import os

from eval_app_layouts import PACKS, app_layout, spell, table


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--langs", required=True)
    a = ap.parse_args()
    os.makedirs(f"{a.out}/layouts", exist_ok=True); os.makedirs(f"{a.out}/lexicons", exist_ok=True)
    for lang in a.langs.split(","):
        lid, rows = app_layout(lang)
        letters, boxes = table(rows)
        keys = [{"letter": c, "cx": float(b[0]), "cy": float(b[1]), "rx": float(b[2]), "ry": float(b[3])} for c, b in zip(letters, boxes)]
        json.dump({"name": f"app-{lang}", "letters": letters, "keys": keys}, open(f"{a.out}/layouts/app-{lang}.json", "w"), ensure_ascii=False)
        counts = {}
        for line in open(f"{PACKS}/{lang}/{lang}.lexicon.tsv", encoding="utf-8"):
            if line.startswith("#") or "\t" not in line:
                continue
            w, n = line.rstrip("\n").split("\t")[:2]
            k = spell(w, letters)
            if k and n.isdigit():
                counts[k] = counts.get(k, 0) + int(n)
        with open(f"{a.out}/lexicons/{lang}.tsv", "w", encoding="utf-8") as f:
            for w, n in sorted(counts.items(), key=lambda kv: -kv[1]):
                f.write(f"{w}\t{n}\n")
        print(f"{lang}: {lid}, {len(letters)} keys, {len(counts)} words")


if __name__ == "__main__":
    main()
