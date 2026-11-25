"""Which letters a language's words use that its layouts can't type (no key, no long-press)."""
import collections, glob, os, re, sys, unicodedata
import xml.etree.ElementTree as ET
REPO = '/home/syn/Documents/Code/AI/keyboard/dogboard-batch8'
KB = REPO + '/app/src/main/assets/extensions/org.florisboard.k3.foundation/keyboard'
W = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/packs-work'
src = open(REPO + '/app/src/main/kotlin/io/github/sillypuppyy/dogboard/ime/GeneratedLayouts.kt', encoding='utf-8').read()
by_lang = collections.defaultdict(list)
for m in re.finditer(r'LayoutInfo\("([^"]+)", "([^"]+)"', src):
    by_lang[m.group(2)].append(m.group(1))
def decode(s):
    return re.sub(r'\\u\{([0-9A-Fa-f]+)\}', lambda m: chr(int(m.group(1), 16)), s or '')
def typable(layout_id):
    path = f'{KB}/{layout_id}.xml'
    if not os.path.exists(path): return None
    root = ET.parse(path).getroot()
    ns = {'k': root.tag.split('}')[0].strip('{')}
    keys = {k.get('id'): k for k in root.iter('{%s}key' % ns['k'])}
    out = set()
    for layer in root.iter('{%s}layer' % ns['k']):
        if layer.get('id') not in ('base', 'shift'): continue
        for row in layer.iter('{%s}row' % ns['k']):
            for kid in row.get('keys', '').split():
                k = keys.get(kid)
                if k is None: continue
                out.update(decode(k.get('output')).lower())
                for alt in (k.get('longPressKeyIds') or '').split():
                    a = keys.get(alt)
                    if a is not None: out.update(decode(a.get('output')).lower())
    return out
langs = sorted(d for d in os.listdir(W) if re.fullmatch(r'[a-z]{2,3}', d) and os.path.exists(f'{W}/{d}/{d}.lexicon.tsv'))
for lang in langs:
    freq = collections.Counter(); total = 0
    for line in open(f'{W}/{lang}/{lang}.lexicon.tsv', encoding='utf-8'):
        if line.startswith('#') or '\t' not in line: continue
        w, c = line.split('\t')[:2]
        try: c = int(c)
        except ValueError: continue
        for ch in unicodedata.normalize('NFC', w.lower()):
            if ch.isalpha(): freq[ch] += c; total += c
    common = {ch: n / total for ch, n in freq.items() if n / total >= 0.0005}
    for lid in by_lang.get(lang, []):
        t = typable(lid)
        if t is None: continue
        missing = sorted((ch for ch in common if ch not in t), key=lambda ch: -common[ch])
        if missing:
            print(f'{lang:3} {lid:28} missing: ' + ' '.join(f'{ch}({100*common[ch]:.2f}%)' for ch in missing))
