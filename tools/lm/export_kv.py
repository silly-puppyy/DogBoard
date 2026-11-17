"""Export a student with a KV cache to LiteRT (private study, venv-litert): one token per call, so each
new token costs one step instead of re-running a window. Fixed-size cache buffers ([layers, kv_heads,
CTX, head_dim] for K and V) go in and come out; the new token's K/V are written at `pos` with a
one-hot blend (export-friendly, no dynamic shapes) and attention is masked to positions <= pos.
Checks step-by-step logits against the full forward pass, then exports f32 and int8 (dynamic).
Usage: export_kv.py CKPT OUT_DIR [CTX]   ("modern" family only: RoPE + GQA)"""
import os, sys, numpy as np, torch, torch.nn.functional as F
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student, rope
import litert_torch

ckpt, out = sys.argv[1], sys.argv[2]
CTX = int(sys.argv[3]) if len(sys.argv) > 3 else 64
os.makedirs(out, exist_ok=True)
s = torch.load(ckpt, map_location='cpu')
c = Config(**s['config']); net = Student(c).eval(); net.load_state_dict(s['model'])
assert c.family == 'modern'
L, KV, HD = c.layers, c.kv_heads, c.width // c.heads


class Step(torch.nn.Module):
    """(ids [1,1] int, pos [1] int, k_cache, v_cache [L, KV, CTX, HD]) -> (logits [1, vocab], k_cache, v_cache)."""
    def __init__(self, m): super().__init__(); self.m = m

    def forward(self, ids, pos, k_cache, v_cache):
        x = self.m.emb(ids)                                     # [1, 1, W]
        slot = (torch.arange(CTX) == pos).to(x.dtype)            # [CTX] one-hot at pos
        allowed = torch.arange(CTX) <= pos                        # [CTX]
        mask = torch.where(allowed, 0.0, float('-inf')).view(1, 1, 1, CTX)
        ks, vs = [], []
        for i, b in enumerate(self.m.blocks):
            h = b.n1(x)
            q = b.q(h).view(1, 1, c.heads, HD).transpose(1, 2)    # [1, H, 1, HD]
            k = b.k(h).view(1, 1, KV, HD).transpose(1, 2)        # [1, KV, 1, HD]
            v = b.v(h).view(1, 1, KV, HD).transpose(1, 2)
            q, k = rope(q, pos), rope(k, pos)
            sl = slot.view(1, 1, CTX, 1)
            kc = k_cache[i:i + 1] * (1 - sl) + k * sl             # [1, KV, CTX, HD]
            vc = v_cache[i:i + 1] * (1 - sl) + v * sl
            ks.append(kc); vs.append(vc)
            rep = c.heads // KV
            kr, vr = kc.repeat_interleave(rep, 1), vc.repeat_interleave(rep, 1)
            att = torch.softmax(q @ kr.transpose(-1, -2) / HD ** 0.5 + mask, -1)
            x = x + b.o((att @ vr).transpose(1, 2).reshape(1, 1, -1))
            h = b.n2(x)
            x = x + b.down(F.silu(b.gate(h)) * b.up(h))
        logits = (self.m.norm(x) @ self.m.emb.weight.T)[:, -1, :]
        return logits, torch.cat(ks), torch.cat(vs)


step = Step(net).eval()
# check: feed a real-looking sequence one token at a time, compare with the full forward pass
ids = torch.randint(4, 15000, (1, 20))
k_cache = torch.zeros(L, KV, CTX, HD); v_cache = torch.zeros(L, KV, CTX, HD)
with torch.no_grad():
    full = net(ids)[0]
    worst = 0.0
    for t in range(ids.shape[1]):
        lg, k_cache, v_cache = step(ids[:, t:t + 1], torch.tensor([t]), k_cache, v_cache)
        worst = max(worst, (lg[0] - full[t]).abs().max().item())
print(f'step vs full forward over 20 tokens: max |diff| {worst:.2e}')

sample = (torch.tensor([[5]], dtype=torch.int32), torch.tensor([0], dtype=torch.int32),
          torch.zeros(L, KV, CTX, HD), torch.zeros(L, KV, CTX, HD))
name = os.path.basename(ckpt)[:-3]
from litert_torch.generative.quantize import quant_recipes
for tag, qc in (('f32', None), ('int8', quant_recipes.full_dynamic_recipe())):
    m = litert_torch.convert(step, sample, quant_config=qc) if qc else litert_torch.convert(step, sample)
    p = os.path.join(out, f'{name}-kv{CTX}-{tag}.tflite'); m.export(p)
    print(f'{tag}: {os.path.getsize(p) / 1e6:.1f} MB -> {p}')
