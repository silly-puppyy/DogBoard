#!/usr/bin/env python3
"""A learned human swipe model: conditional variational autoencoder over key routes (tools/swipe/cvae.py).

    cvae.py prep  --futo DIR --out cvae-data.pt [-n 300000]
    cvae.py train --data cvae-data.pt --out cvae.pt [--epochs 20] [--z 16]
    cvae.py show  --model cvae.pt --futo DIR --png out.png [--words later,people]

User 2026-11-18: "could an AE help somehow?" The finger models (physics.py) are hand-made physics with fitted
numbers; this one learns the human part from real swipes. A swipe is described against its key route: the
key-aligned path (mean_paths.key_aligned: 8 points from touch-down to the first key, 16 per key-to-key gap, 8
after the last key, by arc length) minus the straight route through the key centres. The decoder sees only
geometry (key positions in physical units, key widths) and a style code z, never letters, so a model trained
on real QWERTY swipes draws swipes for any layout. The encoder reads a real swipe and gives its z (style: speed
of the corners, bows, aim habits); generating draws z from N(0, I).
Generator for prepare_synth.py: --human cvae:MODEL.pt. Honest test: layouts it never saw (swipe-5's real
ClearFlow, Dvorak, German swipes), per_layout.sh, against the hand-made finger models.
"""
import argparse
import json
import math
import random

import numpy as np
import torch
import torch.nn as nn

import units
from mean_paths import END, SEG, key_aligned

NF = 15


def route_of(word, centres):
    ks = [centres[c] for c in word if c in centres]
    return [k for i, k in enumerate(ks) if i == 0 or k != ks[i - 1]]


def features(route):
    """Per aligned point: base position (straight route) and context. Returns (feats [P, NF], base [P, 2])."""
    r = np.asarray(route, np.float32)
    n = len(r)
    rows, base = [], []

    def add(b, frm, to, prv, nxt, u, kind, gap):
        rows.append([b[0], b[1], to[0] - frm[0], to[1] - frm[1], frm[0] - prv[0], frm[1] - prv[1], nxt[0] - to[0],
                     nxt[1] - to[1], u, kind == 0, kind == 1, kind == 2, gap / max(n - 1, 1), n / 10.0,
                     math.hypot(to[0] - frm[0], to[1] - frm[1])])
        base.append(b)
    z = np.zeros(2, np.float32)
    nxt0 = r[1] if n > 1 else r[0]
    for j in range(END):
        add(r[0], r[0], r[0], r[0], nxt0, j / (END - 1), 0, 0)
    for g in range(n - 1):
        frm, to = r[g], r[g + 1]
        prv = r[g - 1] if g > 0 else frm
        nxt = r[g + 2] if g + 2 < n else to
        for j in range(SEG):
            u = j / (SEG - 1)
            add(frm + (to - frm) * u, frm, to, prv, nxt, u, 1, g)
    prv = r[-2] if n > 1 else r[-1]
    for j in range(END):
        add(r[-1], r[-1], r[-1], prv, r[-1], j / (END - 1), 2, n - 1)
    del z
    return np.asarray(rows, np.float32), np.asarray(base, np.float32)


class CVAE(nn.Module):
    def __init__(self, z=16, h=96):
        super().__init__()
        self.z = z
        self.enc = nn.GRU(NF + 2, h, num_layers=2, batch_first=True, bidirectional=True)
        self.to_z = nn.Linear(2 * h, 2 * z)
        self.dec = nn.GRU(NF + z, h, num_layers=2, batch_first=True, bidirectional=True)
        self.out = nn.Sequential(nn.Linear(2 * h, h), nn.GELU(), nn.Linear(h, 2))

    def encode(self, f, y, mask):
        o, _ = self.enc(torch.cat([f, y], -1))
        m = mask.unsqueeze(-1)
        pooled = (o * m).sum(1) / m.sum(1).clamp(min=1)
        mu, lv = self.to_z(pooled).chunk(2, -1)
        return mu, lv.clamp(-8, 6)

    def decode(self, f, z):
        o, _ = self.dec(torch.cat([f, z.unsqueeze(1).expand(-1, f.shape[1], -1)], -1))
        return self.out(o)


def swipe5_qwerty(futo, exclude_layouts, holdout):
    """swipe-5's real QWERTY English swipes, minus sessions that also swiped [exclude_layouts] (those stay unseen test
    people) and a held-out share of sessions ([holdout], for checks on tall keyboards). 2026-11-19: FUTO's main
    collection is all short keyboards (rows 1.4 key widths apart); these are tall (2.25), where people cut corners."""
    rows, sessions_by_layout = [], {}
    for line in open(f"{futo}/swipe-5/swipe5.jsonl"):
        d = json.loads(line)
        if not isinstance(d.get("data"), list):
            continue
        sessions_by_layout.setdefault(d.get("layout"), set()).add(d["session"])
        if d.get("layout") == "qwerty" and (d.get("lang") or d.get("language")) == "en":
            rows.append(d)
    excluded = set().union(*(sessions_by_layout.get(l, set()) for l in exclude_layouts))
    sessions = sorted({d["session"] for d in rows} - excluded)
    random.Random(11).shuffle(sessions)
    held = set(sessions[: int(len(sessions) * holdout)])
    keep = [d for d in rows if d["session"] not in excluded and d["session"] not in held]
    return keep, held


def cmd_prep(a):
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    out = []
    lines = open(f"{a.futo}/train.jsonl")
    extra = []
    if a.swipe5_repeat > 0:
        keep, held = swipe5_qwerty(a.futo, a.exclude_layouts.split(","), 0.2)
        json.dump(sorted(held), open(a.out + ".heldout_sessions.json", "w"))
        print(f"swipe-5 QWERTY: {len(keep)} swipes kept (x{a.swipe5_repeat}), {len(held)} sessions held out", flush=True)
        extra = keep
    def records():
        for line in lines:
            yield json.loads(line)
    import itertools
    with lines:
        # the first n FUTO lines (some are rejected below), then the swipe-5 swipes, repeated
        for d in itertools.chain(itertools.islice(records(), a.n), *([extra] * a.swipe5_repeat)):
            w = d["word"].lower().strip(".,;:!?\"()[]{}")
            sx, sy = units.scale("phys", layout, d.get("canvas_width"), d.get("canvas_height"))
            keys = units.keys(layout, sx, sy)
            if not (w.isalpha() and all(c in keys for c in w)):
                continue
            route = route_of(w, keys)
            if len(route) < 2 or len(route) > 14:
                continue
            xy = [(q["x"] * sx, q["y"] * sy) for q in d["data"]]
            al = key_aligned(xy, route)
            if al is None or not np.isfinite(al).all():
                continue
            feats, base = features(route)
            y = (al - base).astype(np.float32)
            if np.abs(y).max() > 6:  # not this word, or a broken trace
                continue
            out.append((torch.from_numpy(feats).half(), torch.from_numpy(y).half()))
            if len(out) % 50000 == 0:
                print(f"{len(out)} swipes", flush=True)
    torch.save(out, a.out)
    print(f"{len(out)} swipes -> {a.out}")


def batches(data, bs, shuffle, rng):
    idx = list(range(len(data)))
    if shuffle:
        rng.shuffle(idx)
    else:
        idx.sort(key=lambda i: data[i][0].shape[0])
    for s in range(0, len(idx), bs):
        part = [data[i] for i in idx[s:s + bs]]
        P = max(p[0].shape[0] for p in part)
        f = torch.zeros(len(part), P, NF); y = torch.zeros(len(part), P, 2); m = torch.zeros(len(part), P)
        for i, (fi, yi) in enumerate(part):
            f[i, :len(fi)] = fi.float(); y[i, :len(yi)] = yi.float(); m[i, :len(fi)] = 1
        yield f, y, m


def cmd_train(a):
    import os
    import sys
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    import track
    torch.manual_seed(0)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    data = torch.load(a.data)
    rng = random.Random(0)
    rng.shuffle(data)
    nv = min(5000, len(data) // 10)
    val, train = data[:nv], data[nv:]
    net = CVAE(a.z).to(dev)
    opt = torch.optim.AdamW(net.parameters(), lr=2e-3, weight_decay=1e-4)
    steps = a.epochs * math.ceil(len(train) / a.bs)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, 2e-3, total_steps=steps, pct_start=0.05)
    run = track.start("swipe", "en", a.name, layout="qwerty", data="futo-real",
                      config={"kind": "cvae", "z": a.z, "beta": a.beta, "free_bits": a.free_bits, "n": len(train)})
    best = math.inf
    for ep in range(a.epochs):
        net.train()
        tot = n = 0
        for f, y, m in batches(train, a.bs, True, rng):
            f, y, m = f.to(dev), y.to(dev), m.to(dev)
            mu, lv = net.encode(f, y, m)
            z = mu + torch.randn_like(mu) * (0.5 * lv).exp()
            pred = net.decode(f, z)
            rec = (((pred - y) ** 2).sum(-1) * m).sum(1) / m.sum(1)
            kl = 0.5 * (mu ** 2 + lv.exp() - 1 - lv)              # [B, z]
            kl = kl.mean(0).clamp(min=a.free_bits).sum()          # free bits per dimension
            loss = rec.mean() + a.beta * kl / 50.0
            opt.zero_grad(); loss.backward(); nn.utils.clip_grad_norm_(net.parameters(), 1.0); opt.step(); sched.step()
            tot += loss.item() * len(f); n += len(f)
        net.eval()
        with torch.no_grad():
            rec_e = rec_p = kl_v = cnt = 0.0
            for f, y, m in batches(val, 512, False, rng):
                f, y, m = f.to(dev), y.to(dev), m.to(dev)
                mu, lv = net.encode(f, y, m)
                d = lambda p: ((((p - y) ** 2).sum(-1)).sqrt() * m).sum(1) / m.sum(1)
                rec_e += d(net.decode(f, mu)).sum().item()                    # reconstruction, own z
                rec_p += d(net.decode(f, torch.randn_like(mu))).sum().item()  # a random person
                kl_v += (0.5 * (mu ** 2 + lv.exp() - 1 - lv)).sum().item()
                cnt += len(f)
        rec_e, rec_p, kl_v = rec_e / cnt, rec_p / cnt, kl_v / cnt
        print(f"epoch {ep}: train {tot / n:.4f}  val reconstruct {rec_e:.3f} keys, random style {rec_p:.3f} keys, KL {kl_v:.1f}", flush=True)
        run.log({"epoch": ep, "train_loss": tot / n, "val_reconstruct_keys": rec_e, "val_random_keys": rec_p, "val_kl": kl_v})
        if rec_e < best:
            best = rec_e
            torch.save({"model": net.state_dict(), "z": a.z}, a.out)
    run.finish()


class Generator:
    """prepare_synth.py's --human cvae:MODEL.pt: draws swipes on CPU, a batch of words at a time."""

    def __init__(self, path):
        # cvae:MODEL.pt@1.3: style codes drawn wider than N(0, I); cvae:MODEL.pt@1.3:0.7: keys squashed vertically by
        # 0.7 before drawing and the path stretched back (2026-11-18: trained on FUTO's short keys, rows 1.4 key widths
        # apart; swipe-5's keyboards are taller, rows 2.25 apart, and people there cut corners instead of reaching the
        # centres: 1 = the same physical moves on any keyboard, 0.42 / 0.68 = moves in proportion to key height)
        path, _, opts = path.partition("@")
        temp, _, squash = opts.partition(":")
        self.squash = float(squash) if squash else 1.0
        s = torch.load(path, map_location="cpu")
        self.net = CVAE(s["z"]).eval()
        self.net.load_state_dict(s["model"])
        self.temp = float(temp) if temp else 1.0
        self.device, self.batch = "cpu", 256

    def to_gpu(self):
        """Draw on the GPU, thousands of swipes a batch (prepare_synth.py does this when it runs in one process:
        a CUDA model can't be shared with forked workers). 2026-11-18: one layout's 400k swipes took ~25 min on
        one CPU core."""
        if torch.cuda.is_available():
            self.device, self.batch = "cuda", 4096
            self.net.to("cuda")
        return self

    @torch.no_grad()
    def paths(self, words, centres, rng):
        if self.squash != 1.0:
            k = self.squash
            squashed = {c: (x, y * k) for c, (x, y) in centres.items()}
            self.squash = 1.0
            try:
                out = self.paths(words, squashed, rng)
            finally:
                self.squash = k
            return [None if p is None else [(x, y / k) for x, y in p] for p in out]
        out = [None] * len(words)
        items = []
        for i, w in enumerate(words):
            r = route_of(w, centres)
            if len(r) == 1:
                x, y = r[0]
                out[i] = [(x, y), (x + rng.gauss(0, 0.05), y + rng.gauss(0, 0.05))]
            elif len(r) > 1:
                items.append((i, *features(r)))
        items.sort(key=lambda it: len(it[1]))  # similar lengths per batch: less padding
        for s in range(0, len(items), self.batch):
            part = items[s:s + self.batch]
            P = max(len(f) for _, f, _ in part)
            F = torch.zeros(len(part), P, NF)
            for j, (_, f, _) in enumerate(part):
                F[j, :len(f)] = torch.from_numpy(f)
            g = torch.Generator().manual_seed(rng.getrandbits(31))
            z = torch.randn(len(part), self.net.z, generator=g) * self.temp
            Y = self.net.decode(F.to(self.device), z.to(self.device)).float().cpu().numpy()
            for j, (i, f, b) in enumerate(part):
                out[i] = [tuple(p) for p in (b + Y[j, :len(b)])]
        return out


def cmd_show(a):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    layout = json.load(open(f"{a.futo}/swipe-5/layouts/qwerty.json"))
    sx, sy = units.scale("phys", layout, 1.0, 0.42)
    keys = units.keys(layout, sx, sy)
    gen = Generator(a.model)
    words = a.words.split(",")
    real = {w: [] for w in words}
    with open(f"{a.futo}/train.jsonl") as f:
        for line in f:
            d = json.loads(line)
            w = d["word"].lower()
            if w in real and len(real[w]) < 12:
                real[w].append(np.array([(q["x"] * sx, q["y"] * sy) for q in d["data"]]))
            if all(len(v) >= 12 for v in real.values()):
                break
    fig, axs = plt.subplots(len(words), 2, figsize=(14, 3.2 * len(words)))
    rng = random.Random(1)
    for row, w in zip(np.atleast_2d(axs), words):
        sims = gen.paths([w] * 12, keys, rng)
        for ax, ps, name in ((row[0], real[w], "real"), (row[1], [np.array(s) for s in sims], "CVAE, random styles")):
            for p in ps:
                ax.plot(p[:, 0], p[:, 1], "-", lw=1, alpha=0.7)
            for ch, (x, y) in keys.items():
                ax.text(x, y, ch, ha="center", va="center", color="0.6", fontsize=8)
            ax.set_xlim(-0.3, 10.3); ax.set_ylim(sy + 0.3, -0.3); ax.set_aspect("equal"); ax.set_title(f"'{w}': {name}")
    plt.tight_layout(); plt.savefig(a.png, dpi=70)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=("prep", "train", "show"))
    ap.add_argument("--futo", default="")
    ap.add_argument("--data", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--model", default="")
    ap.add_argument("--png", default="")
    ap.add_argument("--words", default="later,people,would,the")
    ap.add_argument("-n", type=int, default=300000)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--bs", type=int, default=256)
    ap.add_argument("--z", type=int, default=16)
    ap.add_argument("--beta", type=float, default=1.0)
    ap.add_argument("--free-bits", type=float, default=0.05)
    ap.add_argument("--name", default="cvae-v1")
    ap.add_argument("--swipe5-repeat", type=int, default=0, help="prep: add swipe-5's tall-keyboard QWERTY swipes, this many times")
    ap.add_argument("--exclude-layouts", default="clearflow,dvorak,german", help="prep: sessions that swiped these stay out")
    a = ap.parse_args()
    {"prep": cmd_prep, "train": cmd_train, "show": cmd_show}[a.cmd](a)


if __name__ == "__main__":
    main()
