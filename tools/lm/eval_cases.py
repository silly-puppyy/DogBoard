"""Grammar-case evaluation for any language's student (private study, GPU): case TSVs (context, word, rivals separated
by |, next word, kind) such as packs-work/ru/ru-cases.tsv (case endings) or es/fr-agreement.tsv (gender / number),
plus bits per character on a validation text. Each case is scored as eval_tok_ab.py scores English confusables:
whole words (pieces + a word boundary) from the left context, and with the next word; accuracy, mean margin over the
best rival, confident mistakes (wrong by more than ln 4), overall and per kind. Logged to wandb.
The tokenizer comes from the checkpoint's tokdir: lm-text/ru-R0 / ru-R1 (tok_prep_ru.py), pilot-caps (caps.py).
Usage: eval_cases.py CKPT VAL_TEXT LANG CASES.tsv [CASES.tsv ...]"""
import os, re, sys, math, json, random, collections, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from student import Config, Student
import caps

torch.set_grad_enabled(False)
CK, VAL, LANG, CASES = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
s = torch.load(CK, map_location='cuda')
net = Student(Config(**s['config'])).cuda().eval(); net.load_state_dict(s['model'])
m = re.search(r'ru-(R\d)$', s['tokdir'])
if m:
    os.environ['RU_VARIANT'] = m.group(1)
    import tok_prep_ru as tp
    sp, encode, VARIANT = tp.sp, tp.encode, m.group(1)
else:
    import sentencepiece as spm
    sp = spm.SentencePieceProcessor(model_file=os.path.join(s['tokdir'], 'sp16k.model'))
    def encode(t): return caps.encode(sp, t)
    VARIANT = os.path.basename(s['tokdir'])
starts = caps.word_starts(sp)
boundary = torch.tensor([i for i in range(sp.vocab_size()) if starts[i]] + [2], device='cuda')


def logits(seqs):
    T = max(map(len, seqs)); ids = torch.zeros(len(seqs), T, dtype=torch.long, device='cuda')
    for i, q in enumerate(seqs): ids[i, :len(q)] = torch.tensor(q[:T])
    return torch.log_softmax(net(ids).float(), -1)


# bits per character
random.seed(1)
lines = [l.rstrip('\n') for l in open(VAL, encoding='utf-8') if l.strip()]
sample = random.sample(lines, min(3000, len(lines)))
nll = chars = 0.0
for b in range(0, len(sample), 32):
    batch = [l[:600] for l in sample[b:b + 32]]
    seqs = [([1] + encode(l))[:256] for l in batch]
    out = logits(seqs)
    for i, q in enumerate(seqs):
        if len(q) < 2: continue
        nll -= float(out[i, torch.arange(len(q) - 1, device='cuda'), torch.tensor(q[1:], device='cuda')].sum())
        full = len(encode(batch[i])) + 1
        chars += len(batch[i]) * (len(q) - 1) / (full - 1)
bpc = nll / chars / math.log(2)


def word_scores(ctx, cands, nxt):
    sep = ' ' if ctx else ''
    base = [1] + (encode(ctx) if ctx else [])
    cut = max(0, len(base) - 200)  # long contexts keep their last 200 tokens
    seqs, spans = [], []
    for c in cands:
        word = ([1] + encode(ctx + sep + c))
        full = ([1] + encode(ctx + sep + c + (' ' + nxt if nxt else '')))
        seqs.append(full[cut:]); spans.append((len(base) - cut, len(word) - cut, len(full) - cut))
    out = logits(seqs)
    res = []
    for i, (a, b, e) in enumerate(spans):
        q = seqs[i]
        lp = sum(float(out[i, j - 1, q[j]]) for j in range(max(a, 1), b))
        lp += sum(float(out[i, j - 1, q[j]]) for j in range(b, e)) if nxt else float(torch.logsumexp(out[i, b - 1, boundary], 0))
        res.append(lp)
    return res


results = {}
for path in CASES:
    name = os.path.basename(path).replace('.tsv', '')
    rows = [l.rstrip('\n').split('\t') for l in open(path, encoding='utf-8')][:1500]
    for mode in ('left', 'next'):
        agg = collections.defaultdict(lambda: [0, 0, 0.0, 0])
        for ctx, w, rv, nxt, kind in rows:
            cands = [w] + rv.split('|')
            sc = word_scores(ctx, cands, nxt if mode == 'next' else None)
            mg = sc[0] - max(sc[1:])
            for k in ('all', kind):
                a = agg[k]; a[0] += 1; a[1] += mg > 0; a[2] += mg; a[3] += mg < -math.log(4)
        for k, (n, acc, mg, cw) in agg.items():
            results[f'{name}/{k}/{mode}'] = dict(n=n, acc=acc / n, margin=mg / n, confident_wrong=cw / n)
            print(f'{VARIANT} {name} {k:5s} {mode:4s} n {n:4d}  acc {100 * acc / n:5.1f} %  margin {mg / n:+.2f}  confidently wrong {100 * cw / n:4.1f} %', flush=True)
print(f'{VARIANT} bits/char {bpc:.4f}', flush=True)

import track
run = os.path.basename(CK).replace('.pt', '')
wb = track.start('llm', LANG, run + '-cases', data=os.path.basename(s['tokdir']), config=dict(variant=VARIANT, ckpt=CK), tags=('tokenizer-ab',))
wb.log({'eval/bpc': bpc, **{f'cases/{k}/{m}': v for k, r in results.items() for m, v in r.items()}})
wb.finish()
json.dump(dict(variant=VARIANT, bpc=bpc, results=results), open(CK.replace('.pt', '') + '.cases.json', 'w'), indent=1)
