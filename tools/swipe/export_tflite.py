#!/usr/bin/env python3
"""SwipeNet -> LiteRT (.tflite) for the keyboard (tools/swipe/export_tflite.py; run in keyboard-data/venv-litert).

    export_tflite.py --model best.pt --out swipe.tflite [--t 160]

LiteRT wants a fixed length, but padding a swipe changes what a two-way LSTM reads (measured: 93% ->
72% top-1 at 160). So each LSTM runs as its two directions, and each direction gets its sequence with
the padding at its own end, after the real points, where it can't change them. Inputs:
  x    float32 [1, T, 4]: the features (prepare_futo.features), zeros after the swipe's n points
  rev  int32 [T]: n-1, n-2, ..., 0, then n, n+1, ..., T-1 (reverses the real points, keeps the rest)
Output: log-probs [1, T, 27]; the first n rows are the swipe's. Zero padding is also what the
convolution sees at the edges, so rows 0..n-1 equal the model's on the unpadded swipe.
"""
import argparse

import torch
import torch.nn as nn

from model import SwipeNet


def one_way(lstm: nn.LSTM, reverse: bool) -> nn.LSTM:
    """One direction of a bidirectional LSTM as its own LSTM (same weights)."""
    one = nn.LSTM(lstm.input_size, lstm.hidden_size, batch_first=True)
    sfx = "_reverse" if reverse else ""
    with torch.no_grad():
        for name in ("weight_ih_l0", "weight_hh_l0", "bias_ih_l0", "bias_hh_l0"):
            getattr(one, name).copy_(getattr(lstm, name + sfx))
    return one


class FixedLength(nn.Module):
    def __init__(self, m: SwipeNet):
        super().__init__()
        self.m = m
        self.f1, self.b1 = one_way(m.lstm1, False), one_way(m.lstm1, True)
        self.f2, self.b2 = one_way(m.lstm2, False), one_way(m.lstm2, True)

    @staticmethod
    def both(fwd, bwd, h, rev):
        out_f, _ = fwd(h)
        out_b, _ = bwd(h[:, rev])
        return torch.cat([out_f, out_b[:, rev]], -1)

    def forward(self, x, rev):
        m = self.m
        h = m.bn1(torch.relu(m.conv(x.transpose(1, 2)))).transpose(1, 2)
        h = self.both(self.f1, self.b1, h, rev)
        h = m.bn2(h.transpose(1, 2)).transpose(1, 2)
        h = self.both(self.f2, self.b2, h, rev)
        return m.fc(h).log_softmax(-1)


def rev_index(n, t):
    return torch.tensor(list(range(n - 1, -1, -1)) + list(range(n, t)), dtype=torch.int32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--t", type=int, default=160)
    a = ap.parse_args()
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    fixed = FixedLength(m).eval()
    # check: the fixed-length form equals the model on unpadded swipes
    with torch.no_grad():
        worst = 0.0
        for n in (2, 7, 35, 90, a.t):
            f = torch.randn(1, n, 4)
            x = torch.zeros(1, a.t, 4)
            x[:, :n] = f
            ref = m(f)[0]
            got = fixed(x, rev_index(n, a.t).long())[0, :n]
            worst = max(worst, (ref - got).abs().max().item())
    print(f"fixed-length vs unpadded: max difference {worst:.2e}", flush=True)
    import litert_torch
    sample = (torch.zeros(1, a.t, 4), rev_index(10, a.t))
    litert_torch.convert(fixed, sample).export(a.out)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
