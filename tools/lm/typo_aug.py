"""Typo-augmented token stream for the student (private, on the SSD): every other line of
pilot/train.txt with each word (letters only, 2+ chars) given a typo with probability P, using the
same keyboard typo model as predbench's noisy.py (different seed). The model sees the typo in its
context but is never trained to predict it: a parallel uint8 mask marks the tokens to predict
(0 = a piece of a corrupted word). The word after a typo is predicted normally, which is what teaches
robustness. Words are encoded one at a time ('▁' + word, as SentencePiece splits on whitespace), so
the mask lines up with the pieces.
Writes pilot/train_typo.bin (uint16, BOS per line) + train_typo.mask (uint8, 1 = predict).
TOK=v2: encodes with the v2 tokenizer and writes into lm-text/v2/tok/ (where train.py DATA=v2 looks);
TYPOS=sim: typos from the realistic simulator (noisy_sim.py) instead of noisy.py's.
TOK=v3: the v3 tokenizer with capitalisation as a token (caps.py: words folded, CAP before a capitalised
word; the CAP is predicted even before a corrupted word: a typo doesn't change the capital)."""
import os, re, sys, random, numpy as np, sentencepiece as spm
sys.path.insert(0, '/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench')
P_DIR = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot'
P, EVERY = 0.15, 2
src = open('/home/syn/.claude/jobs/eb2fb8fd/tmp/predbench/noisy.py').read().split("if sys.argv[1] == 'make':")[0]
exec(src.split('import sys, random, re', 1)[1])  # ROWS, POS, NEAR, typo() without running the CLI
if os.environ.get('TYPOS') == 'sim':  # the realistic simulator (predbench/noisy_sim.py, validated on real typos)
    from noisy_sim import typo
WORD = re.compile(r"^([^A-Za-z]*)([A-Za-z]{2,})([^A-Za-z]*)$")
TOK = os.environ.get('TOK')
TOK_DIR = os.path.join(P_DIR, f'../{TOK}/tok') if TOK in ('v2', 'v3') else P_DIR
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caps
sp = spm.SentencePieceProcessor(model_file=os.path.join(TOK_DIR, 'sp16k.model'))
rnd = random.Random(29); n_tok = n_masked = 0
with open(os.path.join(P_DIR, 'train.txt'), encoding='utf-8') as f, \
     open(os.path.join(TOK_DIR, os.environ.get('AUGNAME', 'train_typo') + '.bin'), 'wb') as ob, \
     open(os.path.join(TOK_DIR, os.environ.get('AUGNAME', 'train_typo') + '.mask'), 'wb') as om:
    ids, mask = [], []
    for li, line in enumerate(f):
        if li % EVERY: continue
        words, bad = [], []
        for w in line.split():
            m = WORD.match(w)
            if m and rnd.random() < P:
                words.append(m.group(1) + typo(m.group(2), rnd) + m.group(3)); bad.append(True)
            else:
                words.append(w); bad.append(False)
        if not words: continue
        ids.append(1); mask.append(1)
        if TOK == 'v3':
            folded = [caps.fold(w) for w in words]
            for (fw, (cap,)), pieces, b in zip(folded, sp.encode([f for f, _ in folded]), bad):
                if cap: ids.append(caps.CAP); mask.append(1)
                ids.extend(pieces); mask.extend([0 if b else 1] * len(pieces))
        else:
            for pieces, b in zip(sp.encode(words), bad):
                ids.extend(pieces); mask.extend([0 if b else 1] * len(pieces))
        if len(ids) > 2_000_000:
            ob.write(np.array(ids, dtype=np.uint16).tobytes()); om.write(np.array(mask, dtype=np.uint8).tobytes())
            n_tok += len(ids); n_masked += mask.count(0); ids, mask = [], []
    ob.write(np.array(ids, dtype=np.uint16).tobytes()); om.write(np.array(mask, dtype=np.uint8).tobytes())
    n_tok += len(ids); n_masked += mask.count(0)
print(f'{n_tok:,} tokens, {n_masked:,} masked ({100 * n_masked / n_tok:.1f} %)')
