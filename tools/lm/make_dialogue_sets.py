"""Dialogue next-word sets from OpenAssistant oasst2 (Apache-2.0): the PROMPTS people typed (the human-written
assistant replies read like encyclopedia text, not typing), like make_lang_predbench.py's human sets: 200 lowercased sentences of 5-25 words per language, no links or
code; the chosen lines are appended to exclude-<lang>.txt (student training must drop them). Finnish has 138
messages in oasst2: too few, skipped.
Usage: make_dialogue_sets.py ready.messages.jsonl.gz OUT_DIR"""
import os, re, sys, gzip, json, random, unicodedata

SRC, OUT = sys.argv[1:3]
LANGS = ('es', 'fr', 'ru')
SPLIT = re.compile(r'(?<=[.!?…])\s+|\n+')
BAD = re.compile(r'https?://|www\.|[{}<>=_`#@\\|*]|\d{3,}')
pools = {l: set() for l in LANGS}
for line in gzip.open(SRC, 'rt', encoding='utf-8'):
    m = json.loads(line)
    if m.get('lang') not in LANGS or m.get('deleted') or m.get('synthetic') or m.get('role') != 'prompter': continue
    for s in SPLIT.split(m.get('text') or ''):
        s = unicodedata.normalize('NFC', s.strip().strip('"«»“”-'))
        letters = sum(1 for c in s if c.isalpha())
        if BAD.search(s) or letters < 0.85 * max(1, len(s.replace(' ', ''))): continue
        if 5 <= len(s.split()) <= 25: pools[m['lang']].add(s.lower())
rnd = random.Random(13)
for lang in LANGS:
    rows = sorted(pools[lang]); rnd.shuffle(rows); rows = rows[:200]
    open(os.path.join(OUT, f'dialogue-{lang}.txt'), 'w', encoding='utf-8').write('\n'.join(rows) + '\n')
    with open(os.path.join(OUT, f'exclude-{lang}.txt'), 'a', encoding='utf-8') as f: f.write('\n'.join(rows) + '\n')
    print(lang, 'pool', len(pools[lang]), '->', len(rows))
