"""Tokenizer + token files for the student pilot (private, on the SSD).
1) SentencePiece unigram, VOCAB pieces, byte fallback, trained on every SAMPLE_EVERY-th line of
   pilot/train.txt (2 threads: heat). Standard '▁' word-start pieces (a word starts with '▁').
2) Encodes pilot/{train,val}.txt to uint16 .bin files: each line = BOS + pieces (one message per line,
   BOS marks the boundary; no EOS so the model never learns to end a message mid-thought).
Writes lm-text/pilot/sp16k.model and {train,val}.bin."""
import os, numpy as np, sentencepiece as spm

P = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot'
VOCAB, SAMPLE_EVERY, THREADS = 16000, 8, 2

sample = os.path.join(P, 'sp_sample.txt')
with open(os.path.join(P, 'train.txt'), encoding='utf-8') as f, open(sample, 'w', encoding='utf-8') as out:
    for i, line in enumerate(f):
        if i % SAMPLE_EVERY == 0: out.write(line)
spm.SentencePieceTrainer.train(input=sample, model_prefix=os.path.join(P, 'sp16k'), vocab_size=VOCAB,
    model_type='unigram', character_coverage=0.9995, byte_fallback=True, num_threads=THREADS,
    input_sentence_size=3_000_000, shuffle_input_sentence=True, max_sentence_length=4096,
    pad_id=0, unk_id=3, bos_id=1, eos_id=2, split_digits=True, train_extremely_large_corpus=False)
os.remove(sample)

sp = spm.SentencePieceProcessor(model_file=os.path.join(P, 'sp16k.model'))
for split in ('val', 'train'):
    total = 0
    with open(os.path.join(P, f'{split}.txt'), encoding='utf-8') as f, open(os.path.join(P, f'{split}.bin'), 'wb') as out:
        while True:
            lines = [l.rstrip('\n') for _, l in zip(range(200_000), f)]
            if not lines: break
            ids = sp.encode(lines, num_threads=THREADS)
            flat = np.fromiter((t for seq in ids for t in [1] + seq), dtype=np.uint16)
            out.write(flat.tobytes()); total += len(flat)
    print(split, f'{total:,} tokens', flush=True)
