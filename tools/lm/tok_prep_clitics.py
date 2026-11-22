"""Clitic-token A/B for the student (private, on the SSD): the pilot text tokenised as pilot-caps (caps.py) plus the
English clitics as tokens of their own (clitics.py; T1 = one token per form, T2 = one possessive token). Same lines,
sample rule and SentencePiece settings as tok_prep_caps.py, so the clitics are the only difference from pilot-caps.
Usage: tok_prep_clitics.py T1|T2  ->  lm-text/pilot-clitics-<variant>/{sp16k.model, train.bin, val.bin}"""
import os, sys, numpy as np, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caps, clitics

VARIANT = os.environ.get('CLITIC_VARIANT') or sys.argv[1]  # the evaluation imports it with CLITIC_VARIANT
P = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot'
OUT = f'{P}-clitics-{VARIANT}'; os.makedirs(OUT, exist_ok=True)
THREADS = int(os.environ.get('THREADS', '4'))


def fold(line):
    text, cap = caps.fold(line)
    text, syms = clitics.fold(text, VARIANT)
    return text, cap, syms


sample = os.path.join(OUT, 'sp_sample.txt')
if not os.path.exists(os.path.join(OUT, 'sp16k.model')):  # imported by the evaluation: the tokenizer is built once
  with open(os.path.join(P, 'train.txt'), encoding='utf-8') as f, open(sample, 'w', encoding='utf-8') as out:
    for i, line in enumerate(f):
        if i % 8 == 0: out.write(fold(line)[0] + '\n')
  spm.SentencePieceTrainer.train(input=sample, model_prefix=os.path.join(OUT, 'sp16k'), vocab_size=16000,
    model_type='unigram', character_coverage=0.9995, byte_fallback=True, num_threads=THREADS,
    input_sentence_size=3_000_000, shuffle_input_sentence=True, max_sentence_length=4096,
    pad_id=0, unk_id=3, bos_id=1, eos_id=2, split_digits=True,
    user_defined_symbols=[caps.CAP_SYMBOL] + clitics.symbols(VARIANT))
  os.remove(sample)
sp = spm.SentencePieceProcessor(model_file=os.path.join(OUT, 'sp16k.model'))
assert sp.piece_to_id(caps.CAP_SYMBOL) == caps.CAP
sym_ids = {s: sp.piece_to_id(s) for s in clitics.symbols(VARIANT)}
assert list(sym_ids.values()) == list(range(clitics.FIRST_ID, clitics.FIRST_ID + len(sym_ids))), sym_ids
starts = caps.word_starts(sp)


def encode_folded(ids, cap, syms):
    """CAP before a capitalised word's first piece, its clitic after its last."""
    out, k = [], -1
    for t in ids:
        if starts[t]:
            if 0 <= k < len(syms): out.extend(sym_ids[x] for x in syms[k])
            k += 1
            if k < len(cap) and cap[k]: out.append(caps.CAP)
        out.append(t)
    if 0 <= k < len(syms): out.extend(sym_ids[x] for x in syms[k])
    return out


def encode(line):
    text, cap, syms = fold(line)
    return encode_folded(sp.encode(text), cap, syms)


if __name__ == '__main__':
    for split in ('val', 'train'):
        total = 0
        with open(os.path.join(P, f'{split}.txt'), encoding='utf-8') as f, open(os.path.join(OUT, f'{split}.bin'), 'wb') as out:
            while True:
                lines = [l.rstrip('\n') for _, l in zip(range(200_000), f)]
                if not lines: break
                folded = [fold(l) for l in lines]
                ids = sp.encode([t for t, _, _ in folded], num_threads=THREADS)
                flat = np.fromiter((t for seq, (_, c, s) in zip(ids, folded) for t in [1] + encode_folded(seq, c, s)), dtype=np.uint16)
                out.write(flat.tobytes()); total += len(flat)
        print(split, f'{total:,} tokens', flush=True)
