#!/usr/bin/env python3
"""Trains DogBoard's neural tap-typing model (our MLTT; notes/research/neural-autocorrect-plan.md).

    tools/ml/mltt_train.py TRAIN.npz VALID.npz OUT.pt [--epochs 3] [--threads 8]

Shape (iOS's design, our own weights): one-hot nearest key per tap, each tap fed as two frames
(as iOS does), 27 -> 128 linear, 4 x [BatchNorm -> BiLSTM 128], 256 -> 28 linear; CTC loss over
blank, a-z and the apostrophe. Validation reports greedy word accuracy, overall and on samples
whose taps were not already exact.
"""
import argparse
import sys
import time

import numpy as np
import torch
import torch.nn as nn

IN, OUT, HID = 27, 28, 128


class Net(nn.Module):
    def __init__(self):
        super().__init__()
        self.inp = nn.Linear(IN, HID)
        self.norms = nn.ModuleList()
        self.lstms = nn.ModuleList()
        size = HID
        for _ in range(4):
            self.norms.append(nn.BatchNorm1d(size))
            self.lstms.append(nn.LSTM(size, HID, batch_first=True, bidirectional=True))
            size = 2 * HID
        self.out = nn.Linear(size, OUT)

    def forward(self, x):  # x: (B, T) key codes, 0 = padding
        h = self.inp(nn.functional.one_hot(x.long(), IN).float())
        for norm, lstm in zip(self.norms, self.lstms):
            h = norm(h.transpose(1, 2)).transpose(1, 2)
            h, _ = lstm(h)
        return self.out(h).log_softmax(-1)  # (B, T, OUT)


def frames(x, xlen):
    """Each tap twice: (B, T) -> (B, 2T)."""
    return np.repeat(x, 2, axis=1), xlen.astype(np.int64) * 2


def greedy(logp, lens):
    out = []
    for row, n in zip(logp.argmax(-1).tolist(), lens.tolist()):
        s, prev = [], 0
        for k in row[:n]:
            if k != prev and k != 0:
                s.append(k)
            prev = k
        out.append(s)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("train")
    ap.add_argument("valid")
    ap.add_argument("out")
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = ap.parse_args()
    torch.set_num_threads(args.threads)
    torch.manual_seed(1)
    dev = torch.device(args.device)
    print(f"device {dev}" + (f" ({torch.cuda.get_device_name(dev)})" if dev.type == "cuda" else ""), file=sys.stderr)

    tr = np.load(args.train)
    va = np.load(args.valid)
    net = Net().to(dev)
    print(f"{sum(p.numel() for p in net.parameters())} parameters", file=sys.stderr)
    opt = torch.optim.Adam(net.parameters(), lr=1e-3)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    n = len(tr["x"])
    steps = args.epochs * (n // args.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=2e-3, total_steps=steps)

    def evaluate():
        net.eval()
        right = right_noisy = noisy = 0
        with torch.no_grad():
            for i in range(0, len(va["x"]), 1000):
                x, xl = frames(va["x"][i:i + 1000], va["xlen"][i:i + 1000])
                logp = net(torch.from_numpy(x).to(dev)).cpu()
                got = greedy(logp, torch.from_numpy(xl))
                for j, g in enumerate(got):
                    want = va["y"][i + j][: va["ylen"][i + j]].tolist()
                    typed = [c for c in va["x"][i + j][: va["xlen"][i + j]].tolist()]
                    exact = typed == [c for c in want if c != 27]
                    right += g == want
                    if not exact:
                        noisy += 1
                        right_noisy += g == want
        net.train()
        return right / len(va["x"]), right_noisy / max(noisy, 1)

    step = 0
    t0 = time.time()
    for epoch in range(args.epochs):
        order = np.random.default_rng(epoch).permutation(n)
        for b in range(0, n - args.batch + 1, args.batch):
            idx = order[b:b + args.batch]
            xl_raw = tr["xlen"][idx]
            T = int(xl_raw.max())
            x, xl = frames(tr["x"][idx][:, :T], xl_raw)
            y, yl = tr["y"][idx], tr["ylen"][idx].astype(np.int64)
            logp = net(torch.from_numpy(x).to(dev)).transpose(0, 1)  # (T, B, C)
            loss = ctc(logp, torch.from_numpy(y.astype(np.int64)).to(dev), torch.from_numpy(xl), torch.from_numpy(yl))
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            sched.step()
            step += 1
            if step % 500 == 0:
                print(f"epoch {epoch} step {step}/{steps} loss {loss.item():.3f} {time.time() - t0:.0f}s", file=sys.stderr)
            if step % 3000 == 0:
                acc, acc_noisy = evaluate()
                print(f"  valid: words right {acc:.3f}, of the mistyped ones {acc_noisy:.3f}", file=sys.stderr)
                torch.save(net.state_dict(), args.out)
    acc, acc_noisy = evaluate()
    print(f"final valid: words right {acc:.3f}, of the mistyped ones {acc_noisy:.3f}", file=sys.stderr)
    torch.save(net.state_dict(), args.out)


if __name__ == "__main__":
    main()
