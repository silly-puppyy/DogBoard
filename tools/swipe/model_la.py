"""Layout-agnostic swipe recogniser (tools/swipe/model_la.py): the keyboard is an input, so one model reads
any layout (QWERTY, ClearFlow, AZERTY with é, German with ä ö ü, ...). Idea from FUTO's paper (arXiv
2606.25247: a per-step 2D spatial pattern read off at the key centres); written here from its
description, not their code (GPL).

Per path step the network gives: lam, the chance a letter is meant here, and 64 coefficients of a
smooth pattern over the keyboard (8 x 8 cosines over the letter area). Each key's logit is that
pattern at the key's centre; P(key) = lam x softmax over the layout's keys, P(blank) = 1 - lam (both
normalised, so plain CTC works). The path and the keys are in the same key units (x 0..10, y 0..3);
training moves them together (flips, rotation, shear, scale, shift), so the network can't learn
where QWERTY's letters are, only how a path relates to the keys it is given.

Backbones to compare: "lstm" (iOS's shape: conv 5 -> 2 BiLSTM 128), "lstm256", "tcn" (dilated
convolutions, as FUTO's encoder).
"""
import math

import torch
import torch.nn as nn

BASIS = 8
FEATURES = 4


class TCNBlock(nn.Module):
    def __init__(self, ch, dilation):
        super().__init__()
        self.dw = nn.Conv1d(ch, ch, 5, padding=2 * dilation, dilation=dilation, groups=ch)
        self.bn = nn.BatchNorm1d(ch)
        self.pw1 = nn.Conv1d(ch, 4 * ch, 1)
        self.pw2 = nn.Conv1d(2 * ch, ch, 1)

    def forward(self, x):
        h = self.bn(self.dw(x))
        a, b = self.pw1(h).chunk(2, 1)
        return x + self.pw2(a * torch.sigmoid(b))  # GLU, residual


class SwipeNetLA(nn.Module):
    def __init__(self, backbone="lstm", hidden=128):
        super().__init__()
        self.backbone = backbone
        if backbone.startswith("lstm"):
            h = 256 if backbone == "lstm256" else hidden
            self.conv = nn.Conv1d(FEATURES, h, kernel_size=5, padding=2)
            self.bn1 = nn.BatchNorm1d(h)
            self.lstm1 = nn.LSTM(h, h, batch_first=True, bidirectional=True)
            self.bn2 = nn.BatchNorm1d(2 * h)
            self.lstm2 = nn.LSTM(2 * h, h, batch_first=True, bidirectional=True)
            out = 2 * h
        elif backbone == "tcn":
            ch = 192
            self.inp = nn.Conv1d(FEATURES, ch, 5, padding=2)
            self.blocks = nn.ModuleList(TCNBlock(ch, d) for d in (1, 2, 3, 5, 8, 1, 2, 3))
            out = ch
        else:
            raise ValueError(backbone)
        self.lam = nn.Linear(out, 1)
        self.coef = nn.Linear(out, BASIS * BASIS)
        self.register_buffer("freq", torch.arange(BASIS, dtype=torch.float32) * math.pi)

    def encode(self, x):
        if self.backbone.startswith("lstm"):
            h = self.bn1(torch.relu(self.conv(x.transpose(1, 2)))).transpose(1, 2)
            h, _ = self.lstm1(h)
            h = self.bn2(h.transpose(1, 2)).transpose(1, 2)
            h, _ = self.lstm2(h)
            return h
        h = torch.relu(self.inp(x.transpose(1, 2)))
        for b in self.blocks:
            h = b(h)
        return h.transpose(1, 2)

    def basis(self, keys):
        """keys (B, K, 2) in key units -> (B, K, 64): cos(pi u x) cos(pi v y) over the letter area."""
        ux = torch.cos(self.freq * (keys[..., :1] / 10.0))  # (B, K, 8)
        uy = torch.cos(self.freq * (keys[..., 1:] / 3.0))
        return (ux.unsqueeze(-1) * uy.unsqueeze(-2)).flatten(-2)

    def forward(self, x, keys, kmask=None):
        """x (B, T, 4), keys (B, K, 2), kmask (B, K) True = real key -> log-probs (B, T, 1 + K)."""
        h = self.encode(x)
        z = torch.einsum("btc,bkc->btk", self.coef(h), self.basis(keys))
        if kmask is not None:
            z = z.masked_fill(~kmask.unsqueeze(1), -1e4)
        lam = self.lam(h)
        return torch.cat([nn.functional.logsigmoid(-lam), nn.functional.logsigmoid(lam) + z.log_softmax(-1)], -1)


def letters_logp(logp, layout_letters, alphabet):
    """(T, 1 + K) log-probs over a layout's keys -> (T, 1 + len(alphabet)) in alphabet order (missing
    letters get -inf), for decode.py's beam."""
    idx = {c: i + 1 for i, c in enumerate(layout_letters)}
    cols = [0] + [idx.get(c, -1) for c in alphabet]
    out = torch.full((logp.shape[0], len(cols)), -1e9)
    for j, k in enumerate(cols):
        if k >= 0:
            out[:, j] = logp[:, k]
    return out
