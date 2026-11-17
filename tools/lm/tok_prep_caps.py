"""CAP-token A/B for the student (private, on the SSD): the pilot mix tokenised the caps.py way
(lowercased text + a CAP id before capitalised words) with its own SentencePiece 16k trained on
lowercased text ('<cap>' reserved as id 4). Same lines and sample rule as tok_prep.py, so the only
difference from base-modern12x384 is the capitalisation scheme.
Writes lm-text/pilot-caps/{sp16k.model, train.bin, val.bin}."""
import os, sys, numpy as np, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caps

P = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot'
OUT = P + '-caps'; os.makedirs(OUT, exist_ok=True)
THREADS = 1  # beside the nightly CPU benchmarks
sample = os.path.join(OUT, 'sp_sample.txt')
with open(os.path.join(P, 'train.txt'), encoding='utf-8') as f, open(sample, 'w', encoding='utf-8') as out:
    for i, line in enumerate(f):
        if i % 8 == 0: out.write(caps.fold(line)[0] + '\n')
spm.SentencePieceTrainer.train(input=sample, model_prefix=os.path.join(OUT, 'sp16k'), vocab_size=16000,
    model_type='unigram', character_coverage=0.9995, byte_fallback=True, num_threads=THREADS,
    input_sentence_size=3_000_000, shuffle_input_sentence=True, max_sentence_length=4096,
    pad_id=0, unk_id=3, bos_id=1, eos_id=2, split_digits=True, user_defined_symbols=[caps.CAP_SYMBOL])
os.remove(sample)
sp = spm.SentencePieceProcessor(model_file=os.path.join(OUT, 'sp16k.model'))
assert sp.piece_to_id(caps.CAP_SYMBOL) == caps.CAP, sp.piece_to_id(caps.CAP_SYMBOL)
for split in ('val', 'train'):
    total = 0
    with open(os.path.join(P, f'{split}.txt'), encoding='utf-8') as f, open(os.path.join(OUT, f'{split}.bin'), 'wb') as out:
        while True:
            lines = [l.rstrip('\n') for _, l in zip(range(200_000), f)]
            if not lines: break
            folded = [caps.fold(l) for l in lines]
            ids = sp.encode([t for t, _ in folded], num_threads=THREADS)
            flat = np.fromiter((t for seq, (_, c) in zip(ids, folded) for t in [1] + caps.with_caps(seq, c, sp)), dtype=np.uint16)
            out.write(flat.tobytes()); total += len(flat)
    print(split, f'{total:,} tokens', flush=True)
