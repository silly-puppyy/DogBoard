"""Adds the SmolLM2 group-chat text to the v2 token mix (private, on the SSD), after tok_prep_v2.py
built the tokenizer and the other sources while the group chat was still being generated: one
message per line (BOS each), lines with non-Latin letters dropped, the v2 sp16k; weight x3 in
weights.json (the messaging style the mix lacks)."""
import os, re, json, numpy as np, sentencepiece as spm
L = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text'
T = f'{L}/v2/tok'
NON_LATIN = re.compile(r'[^\x00-ɏ -⁯₠-⃏\U0001f000-\U0001faff☀-➿]')
sp = spm.SentencePieceProcessor(model_file=f'{T}/sp16k.model')
lines = []
for l in open(f'{L}/teacher/groupchat.tsv', encoding='utf-8'):
    msg = l.rstrip('\n').split('\t', 1)[-1].strip()
    if msg and not NON_LATIN.search(msg): lines.append(msg)
total = 0
with open(f'{T}/groupchat.bin', 'wb') as out:
    for i in range(0, len(lines), 200_000):
        flat = np.fromiter((t for seq in sp.encode(lines[i:i + 200_000], num_threads=2) for t in [1] + seq), dtype=np.uint16)
        out.write(flat.tobytes()); total += len(flat)
w = json.load(open(f'{T}/weights.json'))
w['groupchat'] = {'tokens': total, 'weight': 3.0}
json.dump(w, open(f'{T}/weights.json', 'w'), indent=1)
print(f'groupchat: {len(lines):,} messages, {total:,} tokens; weights.json updated')
