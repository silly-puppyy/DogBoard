"""Student pilot training (private study, GPU): next-token LM on lm-text/pilot/train.bin, the
baseline before distillation. Random 257-token windows of the packed BOS-separated stream, bf16
autocast, AdamW (0.9, 0.95, wd 0.1), linear warmup then cosine to 10 %. Val loss every EVAL steps,
checkpoint every CKPT steps (resumes from the last one), logged to wandb project dogboard-llm.
Usage: train.py SHAPE RUN_NAME [steps] [batch]   (SHAPE: a key of SHAPES)"""
import os, sys, math, time, numpy as np, torch, torch.nn.functional as F
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student

P = os.environ.get('TOKDIR', '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/pilot')
CAPS = os.environ.get('CAPS') == '1'  # data made with caps.py (CAP token before capitalised words)
CK = '/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm/ckpt'
SHAPES = {
    'apple-6x512': Config(vocab=16000, layers=6, width=512, heads=8, kv_heads=8, ffn=2048, family='gpt2'),
    'modern-12x384': Config(vocab=16000, layers=12, width=384, heads=6, kv_heads=2, ffn=1024),
}
shape, run = sys.argv[1], sys.argv[2]
STEPS = int(sys.argv[3]) if len(sys.argv) > 3 else 8000
BATCH = int(sys.argv[4]) if len(sys.argv) > 4 else 128
ACCUM = int(os.environ.get('ACCUM', '1'))  # micro-batches per step (deeper shapes run out of GPU memory at 128)
LR, WARMUP, EVAL, CKPT, T = 1.5e-3, 500, 500, 1000, 256
# fine-tuning: LR / WARMUP overrides, INIT = a checkpoint whose weights start the run (not its optimiser)
LR = float(os.environ.get('LR', LR)); WARMUP = int(os.environ.get('WARMUP', WARMUP))
torch.manual_seed(0); torch.backends.cuda.matmul.allow_tf32 = True

c = SHAPES[shape]
# DATA=v2: several sources (tok_prep_v2.py); each batch row picks a source with probability
# proportional to weight x tokens. Default: the v1 pilot stream.
DATA = os.environ.get('DATA', 'v1')
if DATA in ('v2', 'v3'):  # v3 = the v2 mix + one-to-one texting, with capitalisation as a token
    import json
    P = f'/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data/lm-text/{DATA}/tok'
    if DATA == 'v3': CAPS = True
    W = json.load(open(os.path.join(P, 'weights.json')))
    streams = [np.memmap(os.path.join(P, f'{n}.bin'), dtype=np.uint16, mode='r') for n in W]
    mix = np.array([W[n]['tokens'] * W[n]['weight'] for n in W], dtype=np.float64); mix /= mix.sum()
    print('mix', {n: round(float(m), 3) for n, m in zip(W, mix)})
else:
    streams, mix = [np.memmap(os.path.join(P, 'train.bin'), dtype=np.uint16, mode='r')], np.ones(1)
val = np.memmap(os.path.join(P, 'val.bin'), dtype=np.uint16, mode='r')
# AUG = share of each micro-batch drawn from the typo-augmented stream (typo_aug.py); its masked
# targets (pieces of corrupted words) are ignored, so typos are seen in context but never predicted
AUG = float(os.environ.get('AUG', '0'))
if AUG > 0:
    AUGNAME = os.environ.get('AUGNAME', 'train_typo')  # train_typo_sim: typos from the realistic simulator
    aug = np.memmap(os.path.join(P, AUGNAME + '.bin'), dtype=np.uint16, mode='r')
    aug_mask = np.memmap(os.path.join(P, AUGNAME + '.mask'), dtype=np.uint8, mode='r')

def batch(data, n, gen, mask=None):
    ix = gen.integers(0, len(data) - T - 1, n)
    x = torch.from_numpy(np.stack([data[i:i + T + 1].astype(np.int64) for i in ix])).cuda(non_blocking=True)
    y = x[:, 1:].clone()
    if mask is not None:
        m = torch.from_numpy(np.stack([mask[i + 1:i + T + 1] for i in ix])).cuda(non_blocking=True)
        y[m == 0] = -100
    return x[:, :-1], y

def clean_batch(n, gen):
    if len(streams) == 1: return batch(streams[0], n, gen)
    counts = gen.multinomial(n, mix)
    parts = [batch(st, k, gen) for st, k in zip(streams, counts) if k > 0]
    return torch.cat([p[0] for p in parts]), torch.cat([p[1] for p in parts])

def train_batch(n, gen):
    k = int(round(AUG * n))
    if k == 0: return clean_batch(n, gen)
    (x1, y1), (x2, y2) = clean_batch(n - k, gen), batch(aug, k, gen, aug_mask)
    return torch.cat([x1, x2]), torch.cat([y1, y2])

model = Student(c).cuda()
decay = [p for n, p in model.named_parameters() if p.dim() >= 2]
no_decay = [p for n, p in model.named_parameters() if p.dim() < 2]
opt = torch.optim.AdamW([{'params': decay, 'weight_decay': 0.1}, {'params': no_decay, 'weight_decay': 0.0}],
                        lr=LR, betas=(0.9, 0.95), fused=True)
os.makedirs(CK, exist_ok=True); ck_path = os.path.join(CK, f'{run}.pt'); step = 0
if os.path.exists(ck_path):
    s = torch.load(ck_path, map_location='cuda'); model.load_state_dict(s['model']); opt.load_state_dict(s['opt']); step = s['step']
    print('resumed at', step)
elif os.environ.get('INIT'):
    model.load_state_dict(torch.load(os.environ['INIT'], map_location='cuda')['model']); print('initialised from', os.environ['INIT'])
fwd = torch.compile(model) if os.environ.get('COMPILE') == '1' else model

def lr_at(s):
    if s < WARMUP: return LR * (s + 1) / WARMUP
    return LR * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * min(1.0, (s - WARMUP) / (STEPS - WARMUP)))))

@torch.no_grad()
def val_loss(n=40):
    model.eval(); g = np.random.default_rng(123); tot = 0.0
    for _ in range(n):
        x, y = batch(val, 64, g)
        with torch.autocast('cuda', dtype=torch.bfloat16):
            tot += F.cross_entropy(fwd(x).float().view(-1, c.vocab), y.reshape(-1)).item()
    model.train(); return tot / n

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import track
# same project as before; older runs (v2, v3, v4ft) kept their plain names
wandb = track.start('llm', os.environ.get('LM_LANG', 'en'), run, data=DATA,
           config=dict(shape=shape, steps=STEPS, batch=BATCH, lr=LR, window=T, aug=AUG, accum=ACCUM, data=DATA, caps=CAPS, params=sum(p.numel() for p in model.parameters()), **c.__dict__))
gen = np.random.default_rng(step + 1); t0 = time.time()
model.train()
while step < STEPS:
    for gr in opt.param_groups: gr['lr'] = lr_at(step)
    opt.zero_grad(set_to_none=True)
    for _ in range(ACCUM):
        x, y = train_batch(BATCH // ACCUM, gen)
        with torch.autocast('cuda', dtype=torch.bfloat16):
            loss = F.cross_entropy(fwd(x).float().view(-1, c.vocab), y.reshape(-1))
        (loss / ACCUM).backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0); opt.step(); step += 1
    if step % 50 == 0:
        tps = 50 * BATCH * T / (time.time() - t0); t0 = time.time()
        wandb.log({'train/loss': loss.item(), 'lr': lr_at(step), 'tok_per_s': tps}, step=step)
    if step % EVAL == 0 or step == STEPS:
        vl = val_loss(); wandb.log({'val/loss': vl, 'val/ppl': math.exp(vl)}, step=step)
        print(f'step {step} train {loss.item():.3f} val {vl:.3f}', flush=True)
    if step % CKPT == 0 or step == STEPS:
        torch.save({'model': model.state_dict(), 'opt': opt.state_dict(), 'step': step, 'config': c.__dict__, 'tokdir': P, 'caps': CAPS}, ck_path + '.tmp')
        os.replace(ck_path + '.tmp', ck_path)
wandb.finish()
