"""v3: the v2 mix with capitalisation as a token (caps.py, Apple's scheme; CAP A/B 2026-11-16: 8 wins /
2 ties / 1 loss) plus one-to-one texting (teacher/dm.tsv, x3). Otherwise as tok_prep_v2.py:
Tokenizer + token files for the second student mix (private, on the SSD). Sources and weights
(a weight above 1 repeats the source, below 1 samples it; teacher text and group chat are the parts
that add messaging style, so they are repeated):
  pilot (lm-text/pilot/train.txt, 100 M words: WildChat, Persona, Common Voice, IRC, Leipzig)  x1
  reddit (v2/reddit.txt, 300 M)  x1      soda (v2/soda.txt, 60 M)  x1
  fineweb (v2/fineweb.txt, 400 M)  x0.6  teacher continuations (teacher/qwen17.tsv)  x1
  group chat (teacher/groupchat.tsv)  x3 (one message per line: the phone model only sees the current field)
Teacher lines with non-Latin letters are dropped (the 1.7B sometimes switches to Chinese).
1) SentencePiece unigram 16k on a weighted sample (2 threads); 2) encodes every source to its own
uint16 .bin (BOS per line) under lm-text/v2/tok/, plus weights.json for train.py's mixer.
Val = the pilot val (comparable to v1 runs)."""
import os, re, sys, json, random, numpy as np, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import caps

L = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text'
OUT = os.path.join(L, 'v3/tok'); os.makedirs(OUT, exist_ok=True)
SOURCES = {'pilot': (f'{L}/pilot/train.txt', 1.0), 'reddit': (f'{L}/v2/reddit.txt', 1.0), 'soda': (f'{L}/v2/soda.txt', 1.0),
           'fineweb': (f'{L}/v2/fineweb.txt', 0.6), 'teacher': (f'{L}/teacher/qwen17.tsv', 1.0),
           'groupchat': (f'{L}/teacher/groupchat.tsv', 3.0), 'dm': (f'{L}/teacher/dm.tsv', 3.0)}
if os.environ.get('DM_LATER') == '1': SOURCES.pop('dm')  # still generating: encode_extra_v3.py adds it
NON_LATIN = re.compile(r'[^\x00-ɏ -⁯₠-⃏\U0001f000-\U0001faff☀-➿]')
THREADS = 2

def lines(name):
    path = SOURCES[name][0]
    with open(path, encoding='utf-8') as f:
        for l in f:
            if path.endswith('.tsv'):
                l = l.split('\t', 1)[-1]
                if NON_LATIN.search(l): continue
            l = l.strip()
            if l: yield l

# 1) tokenizer on a ~4 M-line sample in proportion to each source's weighted size
rnd = random.Random(5)
sample = os.path.join(OUT, 'sp_sample.txt')
with open(sample, 'w', encoding='utf-8') as out:
    for name, (path, w) in SOURCES.items():
        if not os.path.exists(path): print('missing', name); continue
        keep = min(1.0, 0.04 * w)
        for l in lines(name):
            if rnd.random() < keep: out.write(caps.fold(l)[0] + '\n')
spm.SentencePieceTrainer.train(input=sample, model_prefix=os.path.join(OUT, 'sp16k'), vocab_size=16000,
    model_type='unigram', character_coverage=0.9995, byte_fallback=True, num_threads=THREADS,
    input_sentence_size=4_000_000, shuffle_input_sentence=True, max_sentence_length=4096,
    pad_id=0, unk_id=3, bos_id=1, eos_id=2, split_digits=True, user_defined_symbols=[caps.CAP_SYMBOL])
os.remove(sample)

# 2) one token file per source; train.py samples sources by weight x size
sp = spm.SentencePieceProcessor(model_file=os.path.join(OUT, 'sp16k.model'))
assert sp.piece_to_id(caps.CAP_SYMBOL) == caps.CAP
sizes = {}
for name, (path, w) in SOURCES.items():
    if not os.path.exists(path): continue
    total = 0; it = lines(name)
    with open(os.path.join(OUT, f'{name}.bin'), 'wb') as out:
        while True:
            chunk = [l for _, l in zip(range(200_000), it)]
            if not chunk: break
            folded = [caps.fold(l) for l in chunk]
            ids = sp.encode([t for t, _ in folded], num_threads=THREADS)
            flat = np.fromiter((t for seq, (_, c) in zip(ids, folded) for t in [1] + caps.with_caps(seq, c, sp)), dtype=np.uint16)
            out.write(flat.tobytes()); total += len(flat)
    sizes[name] = total; print(f'{name}: {total:,} tokens', flush=True)
vf = [caps.fold(l.strip()) for l in open(f'{L}/pilot/val.txt', encoding='utf-8')]
val = np.fromiter((t for seq, (_, c) in zip(sp.encode([t for t, _ in vf], num_threads=THREADS), vf) for t in [1] + caps.with_caps(seq, c, sp)), dtype=np.uint16)
val.tofile(os.path.join(OUT, 'val.bin'))
json.dump({n: {'tokens': sizes[n], 'weight': SOURCES[n][1]} for n in sizes}, open(os.path.join(OUT, 'weights.json'), 'w'), indent=1)
print('val', len(val), 'tokens; weights.json written')
