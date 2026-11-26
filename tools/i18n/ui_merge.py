#!/usr/bin/env python3
"""Builds assets/i18n/<lang>.json from the machine-translated sentences (ui_translate.py's output, read from
MT_DIR/<lang>.json) and the hand-translated short labels (tools/i18n/ui-labels.tsv: English, German, Spanish, French).
Short labels (four words or fewer) come only from the table: machine translation mangled them without context
("Swipe to type" -> "Auf die Art schwenken"). A sentence whose quoted example (“le maison”) did not survive word
for word is left out (the English shows).   ui_merge.py MT_DIR"""
import json, os, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'i18n')
COLS = {'de': 1, 'es': 2, 'fr': 3}
labels = [l.rstrip('\n').split('\t') for l in open(os.path.join(HERE, 'ui-labels.tsv'), encoding='utf-8') if '\t' in l and not l.startswith('#')]
for lang, col in COLS.items():
    mt = json.load(open(os.path.join(sys.argv[1], f'{lang}.json'), encoding='utf-8'))
    out = {}
    for en, t in mt.items():
        if len(en.split()) <= 4: continue
        quoted = re.findall(r'“([^”]+)”', en)
        if any(q not in t for q in quoted): continue
        out[en] = t
    for row in labels:
        if len(row) > col and row[col] and row[col] != row[0]: out[row[0]] = row[col]
    json.dump(out, open(os.path.join(OUT, f'{lang}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0, sort_keys=True)
    print(lang, len(out), 'strings')
