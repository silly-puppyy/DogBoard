"""Russian student tokenizers for the stem + ending A/B (private, on the SSD), the pilot-caps recipe on lm-text/ru:
  R0  caps.py folding + SentencePiece Unigram 16k (as T0)
  R1  the same, with each split word (ru_morph.py: ru-endings.tsv) cut into stem + ending: SentencePiece sees the
      stem, and the ending's own token ('<-ов>', a user-defined symbol) goes after the stem's last piece, as
      clitics.py does for English clitics. The word's text is stem + ending, so the encoding is exact.
Usage: [TOK_LANG=es] tok_prep_ru.py R0|R1  ->  lm-text/<lang>-<variant>/{sp16k.model, train.bin, val.bin}
Importable (RU_VARIANT=R0|R1): sp, encode(line)."""
import os, re, sys, numpy as np, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caps

VARIANT = os.environ.get('RU_VARIANT') or sys.argv[1]
# TOK_LANG: the same recipe for another language's lm-text/<lang> (R0 only: the endings are Russian's)
LANG = os.environ.get('TOK_LANG', 'ru')
L = f'/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/{LANG}'
OUT = f'{L}-{VARIANT}'; os.makedirs(OUT, exist_ok=True)
THREADS = int(os.environ.get('THREADS', '4'))
ENDINGS = {}
if VARIANT == 'R1':
    for line in open(os.path.join(L, 'ru-endings.tsv'), encoding='utf-8'):
        w, stem, e = line.rstrip('\n').split('\t'); ENDINGS[w] = (stem, e)
    SYMBOLS = ['<-' + e.split('\t')[0] + '>' for e in open(os.path.join(L, 'ru-ending-list.txt'), encoding='utf-8').read().split('\n') if e]
else:
    SYMBOLS = []
WORD = re.compile(r'^([\W\d_]*)([а-яё]+)([\W\d_]*)$')


def fold(line):
    """(text with endings cut off, capital flags, per-word ending symbol or None)."""
    text, cap = caps.fold(line)
    if VARIANT != 'R1': return text, cap, [None] * len(cap)
    words, syms = [], []
    for w in text.split():
        m = WORD.match(w)
        hit = ENDINGS.get(m.group(2)) if m and not m.group(3) else None  # an ending only at the word's very end
        if hit:
            words.append(m.group(1) + hit[0]); syms.append('<-' + hit[1] + '>')
        else:
            words.append(w); syms.append(None)
    return ' '.join(words), cap, syms


sample = os.path.join(OUT, 'sp_sample.txt')
if not os.path.exists(os.path.join(OUT, 'sp16k.model')):  # imported by the evaluation: built once
    with open(os.path.join(L, 'train.txt'), encoding='utf-8') as f, open(sample, 'w', encoding='utf-8') as out:
        for i, line in enumerate(f):
            if i % 3 == 0: out.write(fold(line)[0] + '\n')
    spm.SentencePieceTrainer.train(input=sample, model_prefix=os.path.join(OUT, 'sp16k'), vocab_size=16000,
        model_type='unigram', character_coverage=0.9995, byte_fallback=True, num_threads=THREADS,
        input_sentence_size=3_000_000, shuffle_input_sentence=True, max_sentence_length=4096,
        pad_id=0, unk_id=3, bos_id=1, eos_id=2, split_digits=True, user_defined_symbols=[caps.CAP_SYMBOL] + SYMBOLS)
    os.remove(sample)
sp = spm.SentencePieceProcessor(model_file=os.path.join(OUT, 'sp16k.model'))
assert sp.piece_to_id(caps.CAP_SYMBOL) == caps.CAP
sym_ids = {x: sp.piece_to_id(x) for x in SYMBOLS}
starts = caps.word_starts(sp)


def encode_folded(ids, cap, syms):
    out, k = [], -1
    for t in ids:
        if starts[t]:
            if 0 <= k < len(syms) and syms[k]: out.append(sym_ids[syms[k]])
            k += 1
            if k < len(cap) and cap[k]: out.append(caps.CAP)
        out.append(t)
    if 0 <= k < len(syms) and syms[k]: out.append(sym_ids[syms[k]])
    return out


def encode(line):
    text, cap, syms = fold(line)
    return encode_folded(sp.encode(text), cap, syms)


if __name__ == '__main__':
    for split in ('val', 'train'):
        total = 0
        with open(os.path.join(L, f'{split}.txt'), encoding='utf-8') as f, open(os.path.join(OUT, f'{split}.bin'), 'wb') as out:
            while True:
                lines = [l.rstrip('\n') for _, l in zip(range(200_000), f)]
                if not lines: break
                folded = [fold(l) for l in lines]
                ids = sp.encode([t for t, _, _ in folded], num_threads=THREADS)
                flat = np.fromiter((t for seq, (_, c, s) in zip(ids, folded) for t in [1] + encode_folded(seq, c, s)), dtype=np.uint16)
                out.write(flat.tobytes()); total += len(flat)
        print(split, f'{total:,} tokens', flush=True)
