"""Adds one generated source (teacher TSV: prompt index, message) to the v3 token mix after
tok_prep_v3.py ran without it (private, on the SSD): one message per line, non-Latin lines dropped,
capitalisation as a token (caps.py) with the v3 sp16k; registered in weights.json.
Usage: encode_extra_v3.py NAME teacher.tsv WEIGHT"""
import os, re, sys, json, numpy as np, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caps
name, path, weight = sys.argv[1], sys.argv[2], float(sys.argv[3])
T = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/v3/tok'
NON_LATIN = re.compile(r'[^\x00-ɏ -⁯₠-⃏\U0001f000-\U0001faff☀-➿]')
sp = spm.SentencePieceProcessor(model_file=f'{T}/sp16k.model')
lines = [m for m in (l.rstrip('\n').split('\t', 1)[-1].strip() for l in open(path, encoding='utf-8')) if m and not NON_LATIN.search(m)]
total = 0
with open(f'{T}/{name}.bin', 'wb') as out:
    for i in range(0, len(lines), 200_000):
        folded = [caps.fold(l) for l in lines[i:i + 200_000]]
        ids = sp.encode([t for t, _ in folded], num_threads=2)
        flat = np.fromiter((t for seq, (_, c) in zip(ids, folded) for t in [1] + caps.with_caps(seq, c, sp)), dtype=np.uint16)
        out.write(flat.tobytes()); total += len(flat)
w = json.load(open(f'{T}/weights.json')); w[name] = {'tokens': total, 'weight': weight}
json.dump(w, open(f'{T}/weights.json', 'w'), indent=1)
print(f'{name}: {len(lines):,} messages, {total:,} tokens; weights.json updated')
