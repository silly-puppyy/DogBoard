#!/usr/bin/env python3
"""One swipe recogniser for every layout, FUTO-style (tools/swipe/uni.py).

    uni.py train --out DIR [--epochs 8] [--synth-per-layout 200000] [--futo-shards 5] [--random-share 0.3]
    uni.py eval --model DIR/best.pt [-n 3000]

User 2026-11-19: "I assume we can make our own FUTO-style model". FUTO's encoder (compare_futo.py) never names
letters: each step it emits a smooth field over the keyboard (64 cosine coefficients) read at each key's centre, so
a new layout is only a new list of key centres. UniNet keeps our backbone (conv + 2 BiLSTM, SwipeNet) and replaces
the 27-way output with the same idea: per step a query vector, per key an embedding of its centre and size (Fourier
features, physical units: x * 10, y * 10 * canvas h / w, as the path); a key's score is query . key, plus a blank.
Training: FUTO's real QWERTY swipes (grid units, rescaled to phys at aspect 0.42), the real training halves of
swipe-5's other layouts (layout_split.py, oversampled), and CVAE-v2 synthetic swipes for each layout (the
per-layout sets, synth-cvae2-*). KASROZ is never trained on: its real swipes test a layout the model never saw.
Eval: each layout's held-out half (layout_split.py's people), all of KASROZ, FUTO's test set and How We Swipe,
through our lexicon beam (decode.beam, width 48, no context), as compare_futo.py scores FUTO's encoder.
"""
import argparse
import glob
import json
import math
import os
import random
import sys
import time

import numpy as np
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
from prepare_futo import features  # noqa: E402
from train import augment  # noqa: E402

D = "/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data"
LAYOUTS = f"{D}/swipe/futo/swipe-5/layouts"
EN = os.path.join(HERE, "../../app/src/main/assets/dogboard/en.lexicon.tsv")
TRAIN_LAYOUTS = {  # layout: (language, aspect of its synthetic set)
    "qwerty": ("en", 0.674), "german": ("de", 0.68), "dvorak": ("en", 0.674), "azerty": ("fr", 0.694),
    "spanish": ("es", 0.674), "qwertz": ("de", 0.68), "clearflow": ("en", 0.756),
}
FUTO_ASPECT = 0.42  # FUTO's main collection: canvas h / w
MAX_KEYS = 40


def layout_keys(name):
    """Letters (sorted, the label order) and per key (cx, cy, rx, ry) in 0..1 units."""
    keys = sorted(json.load(open(f"{LAYOUTS}/{name}.json"))["keys"], key=lambda k: k["letter"])
    return "".join(k["letter"] for k in keys), torch.tensor([[k["cx"], k["cy"], k["rx"], k["ry"]] for k in keys])


class UniNet(nn.Module):
    def __init__(self, hidden=128, dim=64, freqs=6):
        super().__init__()
        self.conv = nn.Conv1d(4, hidden, kernel_size=5, padding=2)
        self.bn1 = nn.BatchNorm1d(hidden)
        self.lstm1 = nn.LSTM(hidden, hidden, batch_first=True, bidirectional=True)
        self.bn2 = nn.BatchNorm1d(2 * hidden)
        self.lstm2 = nn.LSTM(2 * hidden, hidden, batch_first=True, bidirectional=True)
        self.query = nn.Linear(2 * hidden, dim)
        self.blank = nn.Linear(2 * hidden, 1)
        self.register_buffer("freq", (2.0 ** torch.arange(freqs)) * math.pi / 10.0)
        self.key = nn.Sequential(nn.Linear(4 * freqs + 4, 128), nn.ReLU(), nn.Linear(128, dim))
        self.scale = nn.Parameter(torch.tensor(1.0 / math.sqrt(dim)))

    def keys(self, k):  # k: (B, K, 4) cx, cy, rx, ry in phys units -> (B, K, dim)
        xy = k[..., :2].unsqueeze(-1) * self.freq  # (B, K, 2, F)
        f = torch.cat([torch.sin(xy).flatten(-2), torch.cos(xy).flatten(-2), k / 10.0], -1)
        return self.key(f)

    def forward(self, x, k, kmask):  # x (B, T, 4); k (B, K, 4); kmask (B, K) -> log-probs (B, T, 1 + K)
        h = self.bn1(torch.relu(self.conv(x.transpose(1, 2)))).transpose(1, 2)
        h, _ = self.lstm1(h)
        h = self.bn2(h.transpose(1, 2)).transpose(1, 2)
        h, _ = self.lstm2(h)
        scores = torch.einsum("btd,bkd->btk", self.query(h), self.keys(k)) * self.scale
        scores = scores.masked_fill(~kmask.unsqueeze(1), -1e4)
        return torch.cat([self.blank(h), scores], -1).log_softmax(-1)


def phys_keys(table, sy):
    """0..1 key boxes -> phys units for a canvas of vertical scale sy."""
    return table * torch.tensor([10.0, sy, 10.0, sy])


class Packed:
    """Swipes packed in one float16 block (offsets, lengths, words, layouts, scales): a tensor per swipe cost ~4.8 KB
    (1.14M swipes = 5.5 GB; 1.6M stalled at a 6 GB cap), packed ~0.6 KB, so all of FUTO's real data fits."""

    def __init__(self):
        self.chunks, self.lens, self.words, self.layouts, self.sy = [], [], [], [], []

    def add(self, feats, word, layout, sy):
        self.chunks.append(feats.half()); self.lens.append(feats.shape[0])
        self.words.append(word); self.layouts.append(layout); self.sy.append(sy)

    def pack(self):
        """Concatenates what was added so far (called per shard, so the separate tensors are freed as it goes)."""
        if self.chunks:
            done = [self.data] if hasattr(self, "data") else []
            self.data = torch.cat(done + self.chunks); self.chunks = []
        self.offsets = np.concatenate([[0], np.cumsum(self.lens)[:-1]]).astype(np.int64)

    def __len__(self):
        return len(self.words)

    def __getitem__(self, i):
        o, n = self.offsets[i], self.lens[i]
        return self.data[o:o + n].float(), self.words[i], self.layouts[i], self.sy[i]


def load_training(per_layout, futo_shards, random_share=0.0):
    items = Packed()
    sy_futo = 10 * FUTO_ASPECT
    for f in sorted(glob.glob(f"{D}/swipe/prepared/train-*.pt"))[:futo_shards]:
        for x, w in torch.load(f):
            x = x.clone(); x[:, 1] *= sy_futo / 3.0; x[:, 3] *= sy_futo / 3.0
            items.add(x, w, "qwerty", sy_futo)
        items.pack()
    print(f"FUTO real QWERTY {len(items)}", flush=True)
    for lay, (lang, asp) in TRAIN_LAYOUTS.items():
        syn = [it for f in sorted(glob.glob(f"{D}/swipe/synth-cvae2-{lay}/train-*.pt")) for it in torch.load(f)]
        random.Random(1).shuffle(syn)
        n_rand = int(per_layout * random_share)
        for x, w in syn[:per_layout - n_rand]:
            items.add(x, w, lay, 10 * asp)
        del syn
        if n_rand:
            # random letter strings (user 2026-11-19: so the network reads the path, not English spelling habits)
            rnd = [it for f in sorted(glob.glob(f"{D}/swipe/synth-random-{lay}/train-*.pt")) for it in torch.load(f)]
            for x, w in rnd[:n_rand]:
                items.add(x, w, lay, 10 * asp)
            del rnd
        real = []
        if lay != "qwerty":
            real = torch.load(f"{D}/swipe/split-{lay}-{lang}/train-0.pt")
            for _ in range(5):
                for x, w, sy in real:
                    items.add(x, w, lay, sy)
        items.pack()
        print(f"{lay}: synthetic {per_layout - n_rand} + random strings {n_rand}, real half {len(real)} x5", flush=True)
    return items


def batches(items, idx, size, shuffle, tables):
    idx = list(idx)
    if shuffle:
        random.shuffle(idx)
    idx = sorted(idx, key=lambda i: items.lens[i] // 8 + (random.random() if shuffle else 0))
    chunks = [idx[i:i + size] for i in range(0, len(idx), size)]
    if shuffle:
        random.shuffle(chunks)
    for c in chunks:
        feats = [items[i][0] for i in c]
        lens = torch.tensor([f.shape[0] for f in feats])
        x = nn.utils.rnn.pad_sequence(feats, batch_first=True)
        k = torch.zeros(len(c), MAX_KEYS, 4); km = torch.zeros(len(c), MAX_KEYS, dtype=torch.bool)
        targets, tlens = [], []
        for j, i in enumerate(c):
            _, w, lay, sy = items[i][:4]
            letters, table = tables[lay]
            k[j, :len(letters)] = phys_keys(table, sy); km[j, :len(letters)] = True
            targets += [letters.index(ch) + 1 for ch in w]; tlens.append(len(w))
        yield x, lens, k, km, torch.tensor(targets), torch.tensor(tlens)


def train(a):
    sys.path.insert(0, os.path.join(HERE, ".."))
    import track
    torch.set_num_threads(2)
    random.seed(0)
    tables = {lay: layout_keys(lay) for lay in TRAIN_LAYOUTS}
    items = load_training(a.synth_per_layout, a.futo_shards, a.random_share)
    idx = [i for i in range(len(items)) if all(ch in tables[items.layouts[i]][0] for ch in items.words[i])]
    random.shuffle(idx)
    dev, train_idx = idx[:5000], idx[5000:]
    print(f"train {len(train_idx)}, dev {len(dev)}", flush=True)
    os.makedirs(a.out, exist_ok=True)
    model = UniNet().cuda()
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    steps = a.epochs * ((len(train_idx) + a.batch - 1) // a.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=steps, pct_start=0.1)
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    run = track.start("swipe", "multi", os.path.basename(a.out.rstrip("/")), layout="universal",
                      data="futo-real+swipe5-halves+cvae2x7", config=dict(epochs=a.epochs, batch=a.batch, lr=a.lr,
                      train_swipes=len(train_idx), params=sum(p.numel() for p in model.parameters()), held_out_layout="kasroz"))
    best = 1e9
    for epoch in range(a.epochs):
        t0, total, n = time.time(), 0.0, 0
        for x, lens, k, km, targets, tlens in batches(items, train_idx, a.batch, True, tables):
            x = augment(x.cuda(), lens)
            logp = model(x, k.cuda(), km.cuda())
            loss = ctc(logp.transpose(0, 1), targets, lens, tlens)
            opt.zero_grad(); loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step(); sched.step()
            total += loss.item(); n += 1
            if n % 500 == 0:
                print(f"epoch {epoch} step {n} loss {total / n:.3f}", flush=True)
                run.log({"train/loss": total / n, "lr": sched.get_last_lr()[0], "epoch": epoch + n * a.batch / len(train_idx)})
        model.eval(); dl = dn = 0.0
        with torch.no_grad():
            for x, lens, k, km, targets, tlens in batches(items, dev, 512, False, tables):
                logp = model(x.cuda(), k.cuda(), km.cuda())
                dl += ctc(logp.transpose(0, 1), targets, lens, tlens).item() * len(lens); dn += len(lens)
        model.train()
        torch.save(model.state_dict(), f"{a.out}/ckpt-{epoch}.pt")
        if dl / dn < best:
            best = dl / dn; torch.save(model.state_dict(), f"{a.out}/best.pt")
        import resource
        rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024 ** 2
        print(f"epoch {epoch}: loss {total / n:.3f}, dev loss {dl / dn:.3f}, {time.time() - t0:.0f} s, peak memory {rss:.1f} GB", flush=True)
        run.log({"dev/ctc_loss": dl / dn, "epoch": epoch + 1, "epoch_seconds": time.time() - t0})
    run.finish()


def evaluate(a):
    from compare_futo import clean, hws_items, score, swipe5_items
    from decode import Trie, load_lexicon
    torch.set_num_threads(2)
    model = UniNet(); model.load_state_dict(torch.load(a.model, map_location="cpu")); model.eval()

    def logp_fn(name, sy_of):
        letters, table = layout_keys(name)
        def f(rec):
            sy = sy_of(rec)
            x = features([{"x": px, "y": py} for px, py in zip(rec["x"], rec["y"])], 0.3, 10.0, sy)
            k = phys_keys(table, sy).unsqueeze(0); km = torch.ones(1, len(letters), dtype=torch.bool)
            with torch.no_grad():
                return model(x.unsqueeze(0), k, km)[0].numpy().astype(np.float64)
        return letters, f
    lex_en = load_lexicon(EN, top=400000); root_en = Trie.build(lex_en)
    for mode in ("hws", "futo-test"):
        items = hws_items(a.n) if mode == "hws" else futo_test_items(a.n)
        letters, f = logp_fn("qwerty", lambda r: 10 * FUTO_ASPECT)
        print(f"{mode}:"); score("UniNet", items, f, root_en, letters)
    # QWERTY in other languages (every person; Polish is a language no training set has)
    unseen = [("kasroz", "en"), ("qwerty", "pl"), ("qwerty", "de"), ("qwerty", "fr")]
    for lay, lang in [(l, v[0]) for l, v in TRAIN_LAYOUTS.items() if l != "qwerty"] + unseen:
        items, root, _ = swipe5_items(lay, lang, all_people=(lay, lang) in unseen)
        letters, f = logp_fn(lay, lambda r: 10.0 * r["canvas_height"] / r["canvas_width"])
        print(f"{lay}-{lang}{', all people (not in training)' if (lay, lang) in unseen else ', held-out half'}:")
        score("UniNet", items, f, root, letters)


def futo_test_items(n):
    from compare_futo import clean
    rows = [json.loads(l) for l in open(f"{D}/swipe/futo/test.jsonl")]
    rows = [d for d in rows if (w := clean(d.get("word", ""))) and w.isascii() and w.isalpha() and d.get("data")]
    random.Random(7).shuffle(rows)
    return [(clean(d["word"]), {"x": [q["x"] for q in d["data"]], "y": [q["y"] for q in d["data"]]}) for d in rows[:n]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["train", "eval"])
    ap.add_argument("--out", default=f"{D}/swipe/runs/uni1")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--synth-per-layout", type=int, default=200000)
    ap.add_argument("--futo-shards", type=int, default=5, help="FUTO real QWERTY shards (200k swipes each)")
    ap.add_argument("--random-share", type=float, default=0.0, help="share of each layout's synthetic swipes drawn as random letter strings (synth-random-LAYOUT)")
    ap.add_argument("--model", default="")
    ap.add_argument("-n", type=int, default=3000)
    a = ap.parse_args()
    train(a) if a.mode == "train" else evaluate(a)


if __name__ == "__main__":
    main()
