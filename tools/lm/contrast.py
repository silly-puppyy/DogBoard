"""Minimal-pair fine-tune for the student (private study, GPU; user 2026-11-22: "push the model to be more decisive
(and correct) on English examples like plural vs possessive"). The tokenizer A/B showed it is not a token problem
(eval_tok_ab.py: plural / possessive ~66 % right from the left context, a quarter confidently wrong, whatever the
clitic tokens), so this changes the training signal instead.

From the TRAINING text (the held-out lines stay for eval_tok_ab.py), every sentence with a confusable word gives a
pair: the sentence as written and the same sentence with a rival form (cats / cat's / cats' from the plural table,
its / it's, your / you're, there / their / they're, whose / who's, lets / let's, were / we're / where). Loss: the
usual next-token loss on the real sentence (so the model keeps its predictions) plus LAMBDA x a hinge that wants the
real word, through the next word's first piece, at least MARGIN nats above the rival's. Logged to wandb
(dogboard-llm, llm-en-<run>).
Usage: contrast.py INIT_CKPT RUN [STEPS]   (T0 tokenizer: pilot-caps)"""
import os, re, sys, math, time, random, torch, sentencepiece as spm
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
from student import Config, Student
import caps, track

INIT, RUN = sys.argv[1], sys.argv[2]
STEPS = int(sys.argv[3]) if len(sys.argv) > 3 else 1500
BATCH, LR, MARGIN, LAMBDA = 64, float(os.environ.get('LR', '2e-4')), math.log(4), float(os.environ.get('LAMBDA', '0.5'))
CK = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm/ckpt'
TEXT = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot/train.txt'
PLURALS = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/agid/en-plurals.tsv'
torch.manual_seed(0); random.seed(0)

s = torch.load(INIT, map_location='cuda')
net = Student(Config(**s['config'])).cuda(); net.load_state_dict(s['model'])
sp = spm.SentencePieceProcessor(model_file=os.path.join(s['tokdir'], 'sp16k.model'))
assert s.get('caps') and 'clitics' not in s['tokdir'], 'T0 (pilot-caps) checkpoints only'

SETS = [["its", "it's"], ["your", "you're"], ["there", "their", "they're"], ["whose", "who's"], ["lets", "let's"],
        ["were", "we're", "where"]]
rivals = {w: [x for x in g if x != w] for g in SETS for w in g}
for line in open(PLURALS, encoding='utf-8'):
    if line.startswith('#'): continue
    f = line.rstrip('\n').split('\t'); sg, pl = f[0], f[1]
    forms = [pl, sg + "'s", pl + ("'" if pl.endswith('s') else "'s")]
    if len(set(forms)) == 3:
        for w in forms: rivals.setdefault(w, [x for x in forms if x != w])
WORD = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*'?")


def examples():
    """(context, word, rival, next word) from the training text, endlessly (a random start each pass)."""
    while True:
        with open(TEXT, encoding='utf-8') as f:
            f.seek(random.randrange(os.path.getsize(TEXT))); f.readline()
            for l in f:
                ms = list(WORD.finditer(l))
                for j, m in enumerate(ms[:-1]):
                    w = m.group(0).replace('’', "'")
                    r = rivals.get(w.lower())
                    if not r or l[m.end():ms[j + 1].start()] != ' ': continue
                    rv = random.choice(r)
                    if w[0].isupper(): rv = rv[0].upper() + rv[1:]
                    yield l[:m.start()].rstrip(), w, rv, ms[j + 1].group(0)


def encode(t): return caps.encode(sp, t)


def seq_and_span(ctx, w, nxt):
    sep = ' ' if ctx else ''
    base = [1] + (encode(ctx) if ctx else [])
    full = [1] + encode(ctx + sep + w + ' ' + nxt)
    word_end = len([1] + encode(ctx + sep + w))
    return full[:256], len(base), min(word_end + 1, 256)  # through the next word's first piece


def logp_spans(seqs, spans):
    T = max(map(len, seqs)); ids = torch.zeros(len(seqs), T, dtype=torch.long, device='cuda')
    for i, q in enumerate(seqs): ids[i, :len(q)] = torch.tensor(q)
    with torch.autocast('cuda', dtype=torch.bfloat16):
        out = torch.log_softmax(net(ids).float(), -1)
    tok = out[:, :-1].gather(-1, ids[:, 1:, None])[..., 0]
    span = torch.stack([tok[i, a - 1:b - 1].sum() for i, (a, b) in enumerate(spans)])
    mask = torch.zeros_like(tok)
    for i, q in enumerate(seqs): mask[i, :len(q) - 1] = 1
    return span, -(tok * mask).sum() / mask.sum()


opt = torch.optim.AdamW(net.parameters(), lr=LR, weight_decay=0.01)
wb = track.start('llm', 'en', RUN, data='pilot', config=dict(init=INIT, steps=STEPS, batch=BATCH, lr=LR, margin=MARGIN,
                 lam=LAMBDA), tags=('contrast',))
gen = examples(); t0 = time.time()
for step in range(1, STEPS + 1):
    batch = [next(gen) for _ in range(BATCH)]
    good, bad, gs, bs = [], [], [], []
    for ctx, w, rv, nxt in batch:
        q, a, b = seq_and_span(ctx, w, nxt); good.append(q); gs.append((a, b))
        q, a, b = seq_and_span(ctx, rv, nxt); bad.append(q); bs.append((a, b))
    lr = LR * min(1.0, step / 100)
    for g in opt.param_groups: g['lr'] = lr
    lg, lm = logp_spans(good, gs)
    lb, _ = logp_spans(bad, bs)
    hinge = torch.relu(MARGIN - (lg - lb)).mean()
    loss = lm + LAMBDA * hinge
    opt.zero_grad(); loss.backward(); torch.nn.utils.clip_grad_norm_(net.parameters(), 1.0); opt.step()
    if step % 50 == 0:
        acc = float(((lg - lb) > 0).float().mean())
        wb.log({'contrast/lm': float(lm), 'contrast/hinge': float(hinge), 'contrast/pair_acc': acc, 'lr': lr}, step=step)
        print(f'step {step} lm {float(lm):.3f} hinge {float(hinge):.3f} pair acc {acc:.2f} ({time.time() - t0:.0f} s)', flush=True)
wb.finish()
torch.save({'model': net.state_dict(), 'step': STEPS, 'config': s['config'], 'tokdir': s['tokdir'], 'caps': True},
           os.path.join(CK, RUN + '.pt'))
print('saved', os.path.join(CK, RUN + '.pt'))
