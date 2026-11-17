"""FineWeb-only ablation data (private, on the SSD): the first lines of v2/fineweb.txt up to the
pilot's size (~147 M tokens), encoded with the pilot's sp16k so the run differs from
base-modern12x384 only in the text. Val = the pilot val (same as every other run).
Writes lm-text/fineweb-only/{train.bin, val.bin, sp16k.model (copy)}."""
import os, shutil, numpy as np, sentencepiece as spm
L = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text'
OUT = f'{L}/fineweb-only'; os.makedirs(OUT, exist_ok=True)
for f in ('sp16k.model', 'val.bin'): shutil.copy(f'{L}/pilot/{f}', OUT)
sp = spm.SentencePieceProcessor(model_file=f'{L}/pilot/sp16k.model')
BUDGET, total = 147_346_785, 0
with open(f'{L}/v2/fineweb.txt', encoding='utf-8') as f, open(f'{OUT}/train.bin', 'wb') as out:
    while total < BUDGET:
        lines = [l.rstrip('\n') for _, l in zip(range(200_000), f)]
        if not lines: break
        flat = np.fromiter((t for seq in sp.encode(lines, num_threads=2) for t in [1] + seq), dtype=np.uint16)
        out.write(flat.tobytes()); total += len(flat)
print(f'fineweb-only: {total:,} tokens')
