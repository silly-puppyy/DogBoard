"""Export a student with an app-kept KV cache to LiteRT (private study, venv-litert): one token per call.

    export_kv2.py CKPT OUT_DIR [CTX]   ("modern" family only: RoPE + GQA)

Version 2 of export_kv.py (2026-11-18, optimisation pass): the cache ([layers, kv_heads, CTX, head_dim] for
K and V, slots < pos filled) only goes in; the step returns the new token's K and V ([layers, kv_heads,
head_dim]) and the app writes them at `pos`. Version 1 blended the new K/V into the whole cache in every
layer, repeated it per query head (GQA) and returned it: at CTX 64 that cache traffic took ~4 of a step's
~5.7 ms (host CPU, one thread; 1.6 ms at CTX 16). Here attention reads the cache once, grouped queries
([kv_heads, heads / kv_heads, head_dim]) instead of a repeated cache, and the token's own key is scored
separately (softmax over cache slots < pos, then itself).
Checks step-by-step logits against the full forward pass, then exports int8 (dynamic).
"""
import os
import sys

import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from student import Config, Student, rope  # noqa: E402
import litert_torch  # noqa: E402

ckpt, out = sys.argv[1], sys.argv[2]
CTX = int(sys.argv[3]) if len(sys.argv) > 3 else 64
os.makedirs(out, exist_ok=True)
s = torch.load(ckpt, map_location='cpu')
c = Config(**s['config']); net = Student(c).eval(); net.load_state_dict(s['model'])
assert c.family == 'modern'
L, KV, HD = c.layers, c.kv_heads, c.width // c.heads
REP = c.heads // KV


class Step(torch.nn.Module):
    """(ids [1,1] int, pos [1] int, k_cache, v_cache [L, KV, CTX, HD]) -> (logits [1, vocab], k_new, v_new [L, KV, HD])."""
    def __init__(self, m): super().__init__(); self.m = m

    def forward(self, ids, pos, k_cache, v_cache):
        x = self.m.emb(ids)                                       # [1, 1, W]
        mask = torch.where(torch.arange(CTX) < pos, 0.0, float('-inf')).view(1, 1, CTX)
        scale = HD ** -0.5
        ks, vs = [], []
        for i, b in enumerate(self.m.blocks):
            h = b.n1(x)
            q = rope(b.q(h).view(1, 1, c.heads, HD).transpose(1, 2), pos)   # [1, H, 1, HD]
            k = rope(b.k(h).view(1, 1, KV, HD).transpose(1, 2), pos)        # [1, KV, 1, HD]
            v = b.v(h).view(1, 1, KV, HD).transpose(1, 2)
            qg = q.reshape(KV, REP, HD)                              # query heads grouped by their KV head
            kn, vn = k.reshape(KV, 1, HD), v.reshape(KV, 1, HD)
            ks.append(kn.reshape(KV, HD)); vs.append(vn.reshape(KV, HD))
            s_cache = qg @ k_cache[i].transpose(-1, -2) * scale + mask      # [KV, REP, CTX]
            s_self = (qg * kn).sum(-1, keepdim=True) * scale              # [KV, REP, 1]
            a = torch.softmax(torch.cat([s_cache, s_self], -1), -1)
            o = a[..., :CTX] @ v_cache[i] + a[..., CTX:] * vn            # [KV, REP, HD]
            x = x + b.o(o.reshape(1, 1, -1))
            h = b.n2(x)
            x = x + b.down(F.silu(b.gate(h)) * b.up(h))
        logits = (self.m.norm(x) @ self.m.emb.weight.T)[:, -1, :]
        return logits, torch.stack(ks), torch.stack(vs)


step = Step(net).eval()
# check: feed a sequence one token at a time (the cache kept as the app keeps it), compare with the full pass
ids = torch.randint(4, 15000, (1, 20))
k_cache = torch.zeros(L, KV, CTX, HD); v_cache = torch.zeros(L, KV, CTX, HD)
with torch.no_grad():
    full = net(ids)[0]
    worst = 0.0
    for t in range(ids.shape[1]):
        lg, kn, vn = step(ids[:, t:t + 1], torch.tensor([t]), k_cache, v_cache)
        k_cache[:, :, t] = kn; v_cache[:, :, t] = vn
        worst = max(worst, (lg[0] - full[t]).abs().max().item())
print(f'step vs full forward over 20 tokens: max |diff| {worst:.2e}')
assert worst < 1e-3

sample = (torch.tensor([[5]], dtype=torch.int32), torch.tensor([0], dtype=torch.int32),
          torch.zeros(L, KV, CTX, HD), torch.zeros(L, KV, CTX, HD))
name = os.path.basename(ckpt)[:-3]
from litert_torch.generative.quantize import quant_recipes  # noqa: E402
m = litert_torch.convert(step, sample, quant_config=quant_recipes.full_dynamic_recipe())
p = os.path.join(out, f'{name}-kvb{CTX}-int8.tflite'); m.export(p)
print(f'int8: {os.path.getsize(p) / 1e6:.1f} MB -> {p}')
