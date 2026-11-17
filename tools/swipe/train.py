#!/usr/bin/env python3
"""Trains SwipeNet with CTC on prepared shards (tools/swipe/train.py).

    train.py --data DIR --out DIR [--epochs 8] [--batch 256] [--workers 2]

DIR holds train-*.pt / dev.pt from prepare_futo.py: lists of (features float32 [T, 4], word). Runs on
the GPU; checkpoints every epoch (out/ckpt-N.pt, out/best.pt by dev greedy word accuracy), so a stop
or a power-off loses at most one epoch (machine notes: checkpoints, at most 2-3 CPU cores).
"""
import argparse
import glob
import os
import random
import time

import torch
import torch.nn as nn

from model import SwipeNet, encode, greedy


def batches(items, size, shuffle):
    idx = list(range(len(items)))
    if shuffle:
        random.shuffle(idx)
    # similar lengths together: less padding, faster LSTMs
    idx = sorted(idx, key=lambda i: items[i][0].shape[0] // 8 + (random.random() if shuffle else 0))
    chunks = [idx[i:i + size] for i in range(0, len(idx), size)]
    if shuffle:
        random.shuffle(chunks)
    for c in chunks:
        feats = [items[i][0] for i in c]
        words = [items[i][1] for i in c]
        lens = torch.tensor([f.shape[0] for f in feats])
        x = nn.utils.rnn.pad_sequence(feats, batch_first=True)
        targets = torch.tensor([k for w in words for k in encode(w)])
        tlens = torch.tensor([len(w) for w in words])
        yield x, lens, targets, tlens, words


def augment(x, lens):
    """Each swipe a little stretched, shifted, tilted and shaken (on the GPU): people, phones and
    keyboards differ, and FUTO's swipes all come from one keyboard. Steps (dx, dy) are recomputed."""
    b, t, _ = x.shape
    dev = x.device
    xy = x[..., :2]
    centre = xy.sum(1, keepdim=True) / lens.view(b, 1, 1).to(dev)
    scale = torch.empty(b, 1, 2, device=dev).uniform_(0.85, 1.15)
    angle = torch.randn(b, device=dev) * 0.05  # ~3 degrees
    cos, sin = torch.cos(angle).view(b, 1), torch.sin(angle).view(b, 1)
    c = (xy - centre) * scale
    rot = torch.stack([c[..., 0] * cos - c[..., 1] * sin, c[..., 0] * sin + c[..., 1] * cos], -1)
    shift = torch.randn(b, 1, 2, device=dev) * torch.tensor([0.15, 0.12], device=dev)
    xy = rot + centre + shift + torch.randn_like(xy) * 0.03
    mask = (torch.arange(t, device=dev).view(1, t) < (lens.to(dev).view(b, 1) - 1)).unsqueeze(-1)
    step = torch.zeros_like(xy)
    step[:, :-1] = xy[:, 1:] - xy[:, :-1]
    return torch.cat([xy, step * mask], -1) * (torch.arange(t, device=dev).view(1, t, 1) < lens.to(dev).view(b, 1, 1))


def evaluate(model, dev, device, n=5000):
    model.eval()
    right = total = 0
    with torch.no_grad():
        for x, lens, _, _, words in batches(dev[:n], 512, False):
            logp = model(x.to(device)).cpu()
            for i, w in enumerate(words):
                right += greedy(logp[i, :lens[i]]) == w
                total += 1
    model.train()
    return right / max(total, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--resume", default="")
    ap.add_argument("--augment", action="store_true")
    a = ap.parse_args()
    torch.set_num_threads(2)  # the GPU does the work; leave the CPU cool
    os.makedirs(a.out, exist_ok=True)
    device = "cuda"
    train = [it for f in sorted(glob.glob(os.path.join(a.data, "train-*.pt"))) for it in torch.load(f)]
    dev = torch.load(os.path.join(a.data, "dev.pt"))
    print(f"train {len(train)}, dev {len(dev)}", flush=True)
    model = SwipeNet().to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    steps = a.epochs * ((len(train) + a.batch - 1) // a.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=steps, pct_start=0.1)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    start, best = 0, 0.0
    if a.resume:
        ck = torch.load(a.resume)
        model.load_state_dict(ck["model"]); opt.load_state_dict(ck["opt"]); sched.load_state_dict(ck["sched"])
        start, best = ck["epoch"] + 1, ck.get("best", 0.0)
    for epoch in range(start, a.epochs):
        t0, total, n = time.time(), 0.0, 0
        for x, lens, targets, tlens, _ in batches(train, a.batch, True):
            x = x.to(device)
            if a.augment:
                x = augment(x, lens)
            logp = model(x)
            loss = ctc(logp.transpose(0, 1), targets, lens, tlens)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            sched.step()
            total += loss.item(); n += 1
            if n % 500 == 0:
                print(f"epoch {epoch} step {n} loss {total / n:.3f}", flush=True)
        acc = evaluate(model, dev, device)
        best = max(best, acc)
        state = {"model": model.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(), "epoch": epoch, "best": best}
        torch.save(state, os.path.join(a.out, f"ckpt-{epoch}.pt"))
        if acc >= best:
            torch.save(model.state_dict(), os.path.join(a.out, "best.pt"))
        print(f"epoch {epoch}: loss {total / max(n, 1):.3f}, dev greedy word accuracy {acc:.3f}, {time.time() - t0:.0f} s", flush=True)


if __name__ == "__main__":
    main()
