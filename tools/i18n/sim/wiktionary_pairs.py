#!/usr/bin/env python3
"""Single-word translation pairs from a kaikki.org Wiktionary extract (CC BY-SA), for the code-switching generator
(mixed_sets_v2.py): content words (nouns, verbs, adjectives) whose first sense's gloss is one English word.

    wiktionary_pairs.py kaikki-LANG.jsonl.gz OUT.tsv      (lang word <TAB> English word <TAB> part of speech)
"""
import gzip
import json
import re
import sys

POS = {'noun', 'verb', 'adj'}
ONE = re.compile(r"^[a-z]+(?:-[a-z]+)?$")


def main():
    src, out = sys.argv[1], sys.argv[2]
    pairs = {}
    with gzip.open(src, 'rt', encoding='utf-8') as f:
        for line in f:
            e = json.loads(line)
            if e.get('pos') not in POS: continue
            word = e.get('word', '')
            if not word.isalpha() or not word.islower(): continue
            for s in e.get('senses', []):
                if 'form_of' in s or 'alt_of' in s: break  # an inflected form: its own entry carries the meaning
                g = (s.get('glosses') or [''])[0].strip().lower()
                g = re.sub(r'\s*\(.*?\)\s*', ' ', g).strip()
                if e['pos'] == 'verb' and g.startswith('to '): g = g[3:]
                if ONE.match(g):
                    pairs.setdefault((word, e['pos']), g)
                break
    with open(out, 'w', encoding='utf-8') as f:
        for (w, pos), g in sorted(pairs.items()):
            f.write(f'{w}\t{g}\t{pos}\n')
    print(len(pairs), 'pairs')


if __name__ == '__main__':
    main()
