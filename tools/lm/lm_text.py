"""Training text for the English student (private pilot data, on the SSD): raw, cased lines from the
same corpus readers as tools/data/build_ngrams.py, with its held-out rule (held-out WildChat / IRC
conversations and Persona-Chat's test split never written) and its PII scrub (emails, URLs, handles,
phone numbers); exact duplicates dropped; plus Leipzig web and news sentences for breadth.
Writes keyboard-data/lm-text/train.txt (one turn / sentence per line) and prints counts per source."""
import os, sys, glob, hashlib, re
sys.path.insert(0, '/home/syn/Documents/Code/AI/keyboard/dogboard/tools/data')
import build_ngrams as bn

D = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data'
OUT = os.path.join(D, 'lm-text'); os.makedirs(OUT, exist_ok=True)
WS = re.compile(r'\s+')

def leipzig():
    for path in sorted(glob.glob(os.path.join(D, 'leipzig/*/*-sentences.txt'))):
        with open(path, encoding='utf-8') as f:
            for line in f:
                parts = line.rstrip('\n').split('\t')
                if len(parts) == 2: yield parts[1], False

seen = set(); counts = {}
with open(os.path.join(OUT, 'train.txt.tmp'), 'w', encoding='utf-8') as out:
    for name, source in (('wildchat', bn.wildchat), ('persona_chat', bn.persona_chat), ('common_voice', bn.common_voice),
                         ('ubuntu_irc', bn.ubuntu_irc), ('leipzig', lambda data: leipzig())):
        n = w = 0
        for text, held in source(D):
            if held: continue
            t = WS.sub(' ', bn.clean(text)).strip()
            if len(t) < 2: continue
            h = hashlib.md5(t.encode()).digest()[:8]
            if h in seen: continue
            seen.add(h); out.write(t + '\n'); n += 1; w += len(t.split())
        counts[name] = (n, w); print(name, n, 'lines', w, 'words', flush=True)
os.replace(os.path.join(OUT, 'train.txt.tmp'), os.path.join(OUT, 'train.txt'))
print('total', sum(c[0] for c in counts.values()), 'lines', sum(c[1] for c in counts.values()), 'words')
