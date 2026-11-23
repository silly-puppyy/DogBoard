#!/usr/bin/env python3
"""Gboard's dynamic-layout rules as derived JSON (notes/design/data/layouts/README.md "Gboard's conditional keys"):
the regex rewrite rules a script's keyboard applies after each key (a vowel key after a consonant writes its sign, a
virama shows its half-form...). Read from the rule protobufs extracted from Gboard's native library (they stay outside
the repo); written as {"rules": [{"pattern", "text", "label"}]} with back-references as $1 ($n: Java's replacement
syntax), one file a script, into the app's assets.

    dynamic_rules.py RULES_DIR OUT_DIR       (RULES_DIR: dynamic_rules_<script>.pb)

Schema (inferred, 2026-11-23): repeated rule (field 1) { 1: pattern over the text before the cursor and the key typed,
2: the key's label in that context, 3: the text written }; U+EE5C and a digit is a back-reference.
"""
import json
import os
import re
import sys

BACKREF = ''


def varint(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1; r |= (x & 0x7f) << s; s += 7
        if not x & 0x80: return r, i


def fields(b):
    i, out = 0, []
    while i < len(b):
        key, i = varint(b, i)
        f, t = key >> 3, key & 7
        if t == 2:
            n, i = varint(b, i); out.append((f, b[i:i + n])); i += n
        elif t == 0:
            v, i = varint(b, i); out.append((f, v))
        else:
            raise ValueError(f'wire type {t}')
    return out


def replacement(s):
    """Back-references as $n; a literal $ or backslash escaped for Java's Matcher.appendReplacement."""
    s = s.replace('\\', '\\\\').replace('$', '\\$')
    return re.sub(BACKREF + r'(\d)', r'$\1', s)


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for name in sorted(os.listdir(src)):
        m = re.fullmatch(r'dynamic_rules_(.+)\.pb', name)
        if not m: continue
        rules = []
        for f, r in fields(open(os.path.join(src, name), 'rb').read()):
            if f != 1 or not isinstance(r, bytes): continue
            d = {g: v.decode('utf-8') for g, v in fields(r) if isinstance(v, bytes)}
            if 1 not in d: continue
            rules.append({'pattern': d[1], 'text': replacement(d.get(3, d.get(2, ''))), 'label': replacement(d.get(2, ''))})
        json.dump({'script': m.group(1), 'source': 'Gboard dynamic layout rules (derived)', 'rules': rules},
                  open(os.path.join(out, f'{m.group(1)}.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(m.group(1), len(rules))


if __name__ == '__main__':
    main()
