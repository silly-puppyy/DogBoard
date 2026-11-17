"""DogBoard student language model (study / training code, not app code).

Two families, one class:
- "modern": RMSNorm, rotary positions, SwiGLU feed-forward, grouped-query attention, tied embeddings
  (the MobileLLM recipe, arXiv 2402.14905).
- "gpt2": LayerNorm, learned positions, GELU feed-forward, full multi-head attention, tied embeddings
  (the shape of Apple's unilm: 6 x 512, 8 heads, FFN 2048, context 256).
Decoding uses a KV cache: each new token costs one step, as on the phone.
"""
import math
from dataclasses import dataclass
import torch
import torch.nn as nn
import torch.nn.functional as F


@dataclass
class Config:
    vocab: int = 16000
    layers: int = 12
    width: int = 384
    heads: int = 6
    kv_heads: int = 2
    ffn: int = 1024
    ctx: int = 256
    family: str = "modern"  # or "gpt2"

    def params(self):
        m = Student(self)
        return sum(p.numel() for p in m.parameters())


class RMSNorm(nn.Module):
    def __init__(self, d, eps=1e-5):
        super().__init__(); self.w = nn.Parameter(torch.ones(d)); self.eps = eps
    def forward(self, x):
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + self.eps) * self.w


def rope(x, pos, base=10000.0):
    d = x.shape[-1]
    inv = 1.0 / (base ** (torch.arange(0, d, 2, device=x.device).float() / d))
    ang = pos[:, None].float() * inv[None]
    cos, sin = ang.cos()[None, None], ang.sin()[None, None]
    x1, x2 = x[..., ::2], x[..., 1::2]
    return torch.stack((x1 * cos - x2 * sin, x1 * sin + x2 * cos), -1).flatten(-2)


class Block(nn.Module):
    def __init__(self, c: Config):
        super().__init__()
        self.c = c; hd = c.width // c.heads; kv = c.kv_heads if c.family == "modern" else c.heads
        self.hd, self.kv = hd, kv
        self.q = nn.Linear(c.width, c.heads * hd, bias=c.family == "gpt2")
        self.k = nn.Linear(c.width, kv * hd, bias=c.family == "gpt2")
        self.v = nn.Linear(c.width, kv * hd, bias=c.family == "gpt2")
        self.o = nn.Linear(c.heads * hd, c.width, bias=c.family == "gpt2")
        if c.family == "modern":
            self.n1, self.n2 = RMSNorm(c.width), RMSNorm(c.width)
            self.gate = nn.Linear(c.width, c.ffn, bias=False)
            self.up = nn.Linear(c.width, c.ffn, bias=False)
            self.down = nn.Linear(c.ffn, c.width, bias=False)
        else:
            self.n1, self.n2 = nn.LayerNorm(c.width), nn.LayerNorm(c.width)
            self.up = nn.Linear(c.width, c.ffn)
            self.down = nn.Linear(c.ffn, c.width)

    def forward(self, x, pos, cache=None):
        B, T, _ = x.shape
        h = self.n1(x)
        q = self.q(h).view(B, T, self.c.heads, self.hd).transpose(1, 2)
        k = self.k(h).view(B, T, self.kv, self.hd).transpose(1, 2)
        v = self.v(h).view(B, T, self.kv, self.hd).transpose(1, 2)
        if self.c.family == "modern":
            q, k = rope(q, pos), rope(k, pos)
        if cache is not None:
            if cache.get("k") is not None:
                k = torch.cat([cache["k"], k], 2); v = torch.cat([cache["v"], v], 2)
            cache["k"], cache["v"] = k, v
        if self.kv != self.c.heads:
            rep = self.c.heads // self.kv
            k = k.repeat_interleave(rep, 1); v = v.repeat_interleave(rep, 1)
        a = F.scaled_dot_product_attention(q, k, v, is_causal=cache is None or T > 1)
        x = x + self.o(a.transpose(1, 2).reshape(B, T, -1))
        h = self.n2(x)
        f = self.down(F.silu(self.gate(h)) * self.up(h)) if self.c.family == "modern" else self.down(F.gelu(self.up(h), approximate="tanh"))
        return x + f


class Student(nn.Module):
    def __init__(self, c: Config):
        super().__init__()
        self.c = c
        self.emb = nn.Embedding(c.vocab, c.width)
        self.pos = nn.Embedding(c.ctx, c.width) if c.family == "gpt2" else None
        self.blocks = nn.ModuleList(Block(c) for _ in range(c.layers))
        self.norm = RMSNorm(c.width) if c.family == "modern" else nn.LayerNorm(c.width)
        nn.init.normal_(self.emb.weight, std=0.02)

    def forward(self, ids, start=0, caches=None):
        T = ids.shape[1]
        pos = torch.arange(start, start + T, device=ids.device)
        x = self.emb(ids)
        if self.pos is not None: x = x + self.pos(pos)
        for i, b in enumerate(self.blocks):
            x = b(x, pos, None if caches is None else caches[i])
        return self.norm(x) @ self.emb.weight.T  # tied output
