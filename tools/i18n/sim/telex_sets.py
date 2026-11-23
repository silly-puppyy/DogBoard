#!/usr/bin/env python3
"""Vietnamese test sets typed in Telex (notes/research/language-families.md 36): the held-out text and the word list are
spelled the Telex way (việt -> vieetj, được -> dduowcj; the pack's rules.json "spellings", as TypedSpellings does), the
usual typo simulator (make_es_sets.py) runs on that, and each case's context and target are mapped back to Vietnamese.
So a case is what a Telex typist's fingers produce (typos included) and the word they meant.

    telex_sets.py PACK_DIR OUT_DIR [N]      (PACK_DIR: vi.heldout.txt, vi.lexicon.tsv, rules.json)
"""
import json
import os
import re
import subprocess
import sys
import unicodedata

# a letter, or a mark belonging to one (Devanagari ि ा ्): Python's \w has no marks, so किताब split into क त ब
# (the Hindi pack's text, 2026-11-23)
L = '(?:[^\\W\\d_]|[' + ''.join(chr(c) for c in range(0x300, 0x10000) if unicodedata.category(chr(c))[0] == 'M') + '])'

HERE = os.path.dirname(os.path.abspath(__file__))
WORD = re.compile(L + r"+")


CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
JONG = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
SPLIT = {'ㅘ': 'ㅗㅏ', 'ㅙ': 'ㅗㅐ', 'ㅚ': 'ㅗㅣ', 'ㅝ': 'ㅜㅓ', 'ㅞ': 'ㅜㅔ', 'ㅟ': 'ㅜㅣ', 'ㅢ': 'ㅡㅣ', 'ㄳ': 'ㄱㅅ', 'ㄵ': 'ㄴㅈ',
         'ㄶ': 'ㄴㅎ', 'ㄺ': 'ㄹㄱ', 'ㄻ': 'ㄹㅁ', 'ㄼ': 'ㄹㅂ', 'ㄽ': 'ㄹㅅ', 'ㄾ': 'ㄹㅌ', 'ㄿ': 'ㄹㅍ', 'ㅀ': 'ㄹㅎ', 'ㅄ': 'ㅂㅅ'}


def hangul_keys(word):
    """HangulComposer.decompose in Python: a word's 2-set keys (한국어 -> ㅎㅏㄴㄱㅜㄱㅇㅓ)."""
    out = []
    for ch in word.lower():
        code = ord(ch) - 0xAC00
        if not 0 <= code < 11172: out.append(SPLIT.get(ch, ch)); continue
        out.append(CHO[code // 588]); out.append(SPLIT.get(JUNG[(code // 28) % 21], JUNG[(code // 28) % 21]))
        if code % 28: out.append(SPLIT.get(JONG[code % 28], JONG[code % 28]))
    return ''.join(out)


def rule_keys(script):
    """RuleComposer.decompose in Python: each sign as the key the script's rules write it from (किताब -> कइतआब)."""
    rules = json.load(open(f'{HERE}/../../../app/src/main/assets/dogboard/dynamic_rules/{script}.json', encoding='utf-8'))['rules']
    key_of_sign = {}
    for r in rules:
        p, t = r['pattern'], r['text']
        last = p[-1] if p and p[-1] not in ')]}?*+.|$' and (len(p) < 2 or p[-2] != '\\') else None
        sign = t[2:] if t.startswith('$1') else ''
        if len(sign) == 1 and last and sign != last and unicodedata.category(sign) in ('Mn', 'Mc'):
            key_of_sign.setdefault(sign, last)
    return lambda word: ''.join(key_of_sign.get(c, c) for c in word.lower())


def speller(rules):
    """The keys a word is typed with: Hangul jamo (rules path "hangul"), else TypedSpellings.spelled in Python (end
    marks (tones) off the letters, their keys appended; letters replaced)."""
    if rules.get('path') == 'hangul':
        return hangul_keys
    if rules.get('path', '').startswith('rules:'):
        return rule_keys(rules['path'][len('rules:'):])
    sp = rules.get('spellings', [])
    letters = sorted([s for s in sp if s.get('letter') and s.get('typed')], key=lambda s: -len(s['letter']))
    marks = [s for s in sp if s.get('mark') and s.get('typed') and s.get('at') == 'end']

    def spelled(word):
        w = unicodedata.normalize('NFD', word.lower())
        suffix = ''
        for m in marks:
            if m['mark'] in w: w = w.replace(m['mark'], ''); suffix += m['typed']
        w = unicodedata.normalize('NFC', w)
        for l in letters: w = w.replace(l['letter'], l['typed'])
        return w + suffix
    return spelled


def main():
    pack, out = sys.argv[1], sys.argv[2]
    n = sys.argv[3] if len(sys.argv) > 3 else '2000'
    if os.path.realpath(out) == os.path.realpath(pack):
        sys.exit('OUT_DIR must differ from PACK_DIR: it gets its own key-typed word list and held-out text')
    os.makedirs(out, exist_ok=True)
    rules = json.load(open(f'{pack}/rules.json', encoding='utf-8'))
    spelled = speller(rules)
    keyset = set(''.join(rules['letter_rows']['rows']))
    shifted = ''.join(a + b for a, b in rules.get('key_of', {}).items())  # pairs: letter, the key it's typed from
    keyset |= set(shifted[0::2])
    lang = os.path.basename(os.path.normpath(pack)).split('-')[0]
    back = {}  # Telex spelling -> the most frequent word spelled so
    counts = {}
    with open(f'{out}/{lang}.lexicon.tsv', 'w', encoding='utf-8') as f:
        f.write('#dogboard-lexicon 1\n')
        for line in open(f'{pack}/{lang}.lexicon.tsv', encoding='utf-8'):
            if line.startswith('#'): continue
            parts = line.rstrip('\n').split('\t')
            w, c = parts[0], float(parts[1]) if len(parts) > 1 else 1.0
            t = spelled(w)
            if not set(t) <= keyset: continue  # a letter the layout's keys don't cover
            if c > counts.get(t, -1): counts[t] = c; back[t] = w
        for t, c in counts.items(): f.write(f'{t}\t{int(c)}\n')
    with open(f'{out}/{lang}.heldout.txt', 'w', encoding='utf-8') as f:
        for i, line in enumerate(open(f'{pack}/{lang}.heldout.txt', encoding='utf-8')):
            if i >= 200000: break
            f.write(WORD.sub(lambda m: spelled(m.group(0)), line.lower()))
    rows = ','.join(rules['letter_rows']['rows'])
    subprocess.run([sys.executable, '-I', f'{HERE}/make_es_sets.py', f'{out}/{lang}.heldout.txt', f'{out}/{lang}.lexicon.tsv',
                    f'{HERE}/typo_model7.json', out, n],
                   env=dict(os.environ, SIM_ROWS=rows, SIM_SHIFTED=shifted, SIM_PREFIX=lang, SIM_LETTERS=''.join(sorted(keyset))),
                   cwd=HERE, check=True, stdout=subprocess.DEVNULL)
    # context and target back in Vietnamese; the typed text stays as typed
    for kind in ('typos', 'controls'):
        p = f'{out}/{lang}-{kind}.tsv'
        if not os.path.exists(p): continue
        rows_out = []
        for line in open(p, encoding='utf-8'):
            r = line.rstrip('\n').split('\t')
            if len(r) < 5: continue
            r[2] = ' '.join(back.get(w, w) for w in r[2].split(' '))
            if r[4] not in back: continue
            r[4] = back[r[4]]
            rows_out.append(r)
        with open(p, 'w', encoding='utf-8') as f:
            for r in rows_out: f.write('\t'.join(r) + '\n')
        print(kind, len(rows_out))


if __name__ == '__main__':
    main()
