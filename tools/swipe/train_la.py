#!/usr/bin/env python3
"""Trains the layout-agnostic recogniser (tools/swipe/train_la.py) on QWERTY swipes, with the keyboard
moved together with the path, and checks it on real swipes from layouts it never saw.

    train_la.py --data DIR --layout qwerty.json --layouts DIR --out DIR [--backbone lstm|lstm256|tcn]
                [--epochs 16] [--batch 256]

DIR: prepare_futo.py shards (key units). --layouts: prepare_layouts.py test sets; each epoch reports
greedy word accuracy on held-out layouts (500 swipes each) besides QWERTY dev.
"""
import argparse
import glob
import json
import math
import os
import random
import sys
import time

import torch
import torch.nn as nn

from model_la import SwipeNetLA

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import track  # noqa: E402


def move_together(x, lens, keys, gen_flip=True):
    """The same random affine map on each swipe's path and its keyboard (key units), steps recomputed.
    x (B, T, 4), keys (B, K, 2)."""
    b, t, _ = x.shape
    dev = x.device
    centre = torch.tensor([5.0, 1.5], device=dev)
    ang = (torch.rand(b, device=dev) * 2 - 1) * math.radians(15)
    shear = (torch.rand(b, device=dev) * 2 - 1) * 0.2
    sx = torch.empty(b, device=dev).uniform_(0.8, 1.2)
    sy = torch.empty(b, device=dev).uniform_(0.8, 1.2)
    if gen_flip:
        sx = sx * torch.where(torch.rand(b, device=dev) < 0.5, -1.0, 1.0)
        sy = sy * torch.where(torch.rand(b, device=dev) < 0.5, -1.0, 1.0)
    c, s = torch.cos(ang), torch.sin(ang)
    # M = R(ang) @ [[1, shear], [0, 1]] @ diag(sx, sy)
    m = torch.stack([
        torch.stack([c * sx, (c * shear - s) * sy], -1),
        torch.stack([s * sx, (s * shear + c) * sy], -1),
    ], -2)  # (B, 2, 2)
    shift = torch.randn(b, 1, 2, device=dev) * 0.2

    def apply(p):
        return torch.einsum("bij,bnj->bni", m, p - centre) + centre + shift
    xy = apply(x[..., :2])
    k2 = apply(keys)
    valid = (torch.arange(t, device=dev).view(1, t) < lens.to(dev).view(b, 1)).unsqueeze(-1)
    last = (torch.arange(t, device=dev).view(1, t) < (lens.to(dev).view(b, 1) - 1)).unsqueeze(-1)
    step = torch.zeros_like(xy)
    step[:, :-1] = xy[:, 1:] - xy[:, :-1]
    return torch.cat([xy, step * last], -1) * valid, k2


def batches(items, size, shuffle):
    idx = list(range(len(items)))
    if shuffle:
        random.shuffle(idx)
    idx = sorted(idx, key=lambda i: items[i][0].shape[0] // 8 + (random.random() if shuffle else 0))
    chunks = [idx[i:i + size] for i in range(0, len(idx), size)]
    if shuffle:
        random.shuffle(chunks)
    for ch in chunks:
        feats = [items[i][0] for i in ch]
        yield nn.utils.rnn.pad_sequence(feats, batch_first=True), torch.tensor([f.shape[0] for f in feats]), [items[i][1] for i in ch]


def greedy_keys(logp, letters):
    out, prev = [], 0
    for k in logp.argmax(-1).tolist():
        if k != prev and k != 0:
            out.append(letters[k - 1])
        prev = k
    return "".join(out)


def evaluate(model, items, keys_map, device, n=500):
    """Greedy word accuracy on a layout's swipes (the keyboard given as input)."""
    letters = "".join(sorted(keys_map))
    kt = torch.tensor([keys_map[c] for c in letters], dtype=torch.float32, device=device)
    model.eval()
    right = total = 0
    with torch.no_grad():
        for x, lens, words in batches(items[:n], 256, False):
            x = x.to(device)
            lp = model(x, kt.unsqueeze(0).expand(x.shape[0], -1, -1)).cpu()
            for i, w in enumerate(words):
                right += greedy_keys(lp[i, :lens[i]], letters) == w
                total += 1
    model.train()
    return right / max(total, 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--layout", required=True, help="the training layout's json (FUTO format)")
    ap.add_argument("--layouts", default="", help="prepare_layouts.py folder: held-out checks")
    ap.add_argument("--out", required=True)
    ap.add_argument("--backbone", default="lstm")
    ap.add_argument("--epochs", type=int, default=16)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--no-flip", action="store_true")
    ap.add_argument("--name", default="")
    a = ap.parse_args()
    torch.set_num_threads(2)
    os.makedirs(a.out, exist_ok=True)
    device = "cuda"
    train = [it for f in sorted(glob.glob(os.path.join(a.data, "train-*.pt"))) for it in torch.load(f)]
    dev = torch.load(os.path.join(a.data, "dev.pt"))
    lay = json.load(open(a.layout))
    qkeys = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
    letters = "".join(sorted(qkeys))
    index = {c: i + 1 for i, c in enumerate(letters)}
    train = [it for it in train if all(c in index for c in it[1])]
    held = {}
    if a.layouts:
        for f in sorted(glob.glob(os.path.join(a.layouts, "*-en.pt"))):
            d = torch.load(f)
            if d["layout"] != "qwerty":
                random.Random(1).shuffle(d["items"])
                held[d["layout"]] = d
    kt = torch.tensor([qkeys[c] for c in letters], dtype=torch.float32, device=device)
    model = SwipeNetLA(a.backbone).to(device)
    params = sum(p.numel() for p in model.parameters())
    print(f"train {len(train)}, dev {len(dev)}, backbone {a.backbone}, {params} params, held-out {sorted(held)}", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    steps = a.epochs * ((len(train) + a.batch - 1) // a.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=steps, pct_start=0.1)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    name = a.name or os.path.basename(a.out.rstrip("/"))
    run = track.start("swipe", "en", name, layout="any", data=os.path.basename(a.data.rstrip("/")),
                      config=dict(model="layout-agnostic", backbone=a.backbone, epochs=a.epochs, batch=a.batch, lr=a.lr,
                                  flips=not a.no_flip, params=params, train_swipes=len(train)))
    best = 0.0
    for epoch in range(a.epochs):
        t0, total, n = time.time(), 0.0, 0
        for x, lens, words in batches(train, a.batch, True):
            x = x.to(device)
            keys = kt.unsqueeze(0).expand(x.shape[0], -1, -1)
            x, keys = move_together(x, lens, keys, not a.no_flip)
            logp = model(x, keys)
            targets = torch.tensor([index[c] for w in words for c in w])
            loss = ctc(logp.transpose(0, 1), targets, lens, torch.tensor([len(w) for w in words]))
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            sched.step()
            total += loss.item(); n += 1
            if n % 500 == 0:
                run.log({"train/loss": total / n, "lr": sched.get_last_lr()[0]})
        acc = evaluate(model, dev, qkeys, device, 5000)
        log = {"dev/greedy_acc": acc, "epoch": epoch + 1}
        msg = f"epoch {epoch}: loss {total / max(n, 1):.3f}, qwerty dev {acc:.3f}"
        for lname, d in held.items():
            h = evaluate(model, d["items"], d["keys"], device)
            log[f"held/{lname}_greedy"] = h
            msg += f", {lname} {h:.3f}"
        run.log(log)
        print(msg + f", {time.time() - t0:.0f} s", flush=True)
        torch.save({"model": model.state_dict(), "backbone": a.backbone}, os.path.join(a.out, f"ckpt-{epoch}.pt"))
        if acc >= best:
            best = acc
            torch.save({"model": model.state_dict(), "backbone": a.backbone}, os.path.join(a.out, "best.pt"))
    run.finish()


if __name__ == "__main__":
    main()
