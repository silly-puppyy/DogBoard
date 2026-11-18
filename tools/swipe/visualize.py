#!/usr/bin/env python3
"""Pictures of what the swipe recogniser learned (tools/swipe/visualize.py).

    visualize.py --model best.pt --layout qwerty.json --out weights.png [--word keyboard]

1. Its keyboard: for a short stroke at each spot, the letter it reads there (nobody told it where keys are).
2. The output layer's letter vectors (256 numbers each) in 2D (PCA): letters close on the keyboard close here?
3. The first-layer filters' preferred direction of movement (their weights on the dx, dy inputs).
4. One swipe as the network reads it: the path, and per step the letter it fires (CTC spikes).
"""
import argparse
import json

import matplotlib
import numpy as np
import torch

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

from human import path as human_path  # noqa: E402
from model import ALPHABET, SwipeNet  # noqa: E402
from prepare_futo import resample  # noqa: E402


def feats(xy):
    pts = resample(xy, 0.3)
    return torch.tensor([(x, y, (pts[i + 1][0] if i + 1 < len(pts) else x) - x, (pts[i + 1][1] if i + 1 < len(pts) else y) - y)
                         for i, (x, y) in enumerate(pts)], dtype=torch.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--layout", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--word", default="keyboard")
    a = ap.parse_args()
    m = SwipeNet()
    m.load_state_dict(torch.load(a.model, map_location="cpu"))
    m.eval()
    lay = json.load(open(a.layout))
    keys = {k["letter"]: (k["cx"] * 10, k["cy"] * 3) for k in lay["keys"]}
    # 26 distinct colours, neighbours on the keyboard apart in hue
    order = "qazwsxedcrfvtgbyhnujmikolp"
    colour = {c: matplotlib.colors.hsv_to_rgb(((i * 7) % 26 / 26.0, 0.65, 0.9)) for i, c in enumerate(order)}
    fig = plt.figure(figsize=(16, 13))

    # 1. where each letter fires: 400 human-model swipes of common words, every step that fires a letter
    ax = fig.add_subplot(2, 2, 1)
    import random
    rng = random.Random(1)
    words = ["the", "and", "that", "with", "have", "this", "from", "they", "would", "there", "their", "about", "which",
             "people", "because", "between", "keyboard", "quick", "brown", "jumps", "lazy", "zebra", "extra", "fox", "vivid"]
    with torch.no_grad():
        for _ in range(16):
            for w in words:
                f = feats([(x, y) for x, y, _ in human_path(w, keys, rng)])
                lp = m(f.unsqueeze(0))[0]
                k = lp.argmax(-1).tolist()
                for i, kk in enumerate(k):
                    if kk:
                        ax.scatter(f[i, 0].item(), f[i, 1].item(), s=10, color=colour[ALPHABET[kk - 1]], alpha=0.5)
    for c, (x, y) in keys.items():
        ax.text(x, y, c, ha="center", va="center", fontsize=14, weight="bold")
    ax.set_xlim(-0.2, 10.2); ax.set_ylim(3.1, -0.1)
    ax.set_title("1. Where each letter fires along 400 human-like swipes (colour = letter fired;\n"
                 "nobody told the network where the keys are: it learned these regions from swipes)")
    ax.set_xlabel("key widths"); ax.set_ylabel("rows")

    # 2. output-layer letter vectors in 2D
    ax = fig.add_subplot(2, 2, 2)
    W = m.fc.weight.detach().numpy()[1:]  # 26 x 256 (row 0 is blank)
    Wc = W - W.mean(0)
    u, s, vt = np.linalg.svd(Wc, full_matrices=False)
    p = Wc @ vt[:2].T
    # orient like the keyboard: fit the 2D points to the key positions (least squares, rotation/scale allowed)
    K = np.array([keys[c] for c in ALPHABET])
    A_ = np.c_[p, np.ones(len(p))]
    M, *_ = np.linalg.lstsq(A_, K, rcond=None)
    q = A_ @ M
    for i, c in enumerate(ALPHABET):
        ax.scatter(*q[i], s=320, color=colour[c], alpha=0.8)
        ax.text(*q[i], c, ha="center", va="center", fontsize=12, weight="bold")
    r = np.corrcoef(np.linalg.norm(K[:, None] - K[None], axis=-1).ravel(), np.linalg.norm(W[:, None] - W[None], axis=-1).ravel())[0, 1]
    ax.set_xlim(-0.5, 10.5); ax.set_ylim(3.3, -0.3)
    ax.set_title(f"2. The output layer's 26 letter vectors (256 numbers each), squashed to 2D\n"
                 f"distance between letters' vectors vs on the keyboard: correlation {r:.2f}")

    # 4. one swipe as read
    ax = fig.add_subplot(2, 2, 4)
    rng = __import__("random").Random(3)
    pts = [(x, y) for x, y, _ in human_path(a.word, keys, rng)]
    f = feats(pts)
    with torch.no_grad():
        lp = m(f.unsqueeze(0))[0].exp().numpy()
    for c, (x, y) in keys.items():
        ax.text(x, y, c, ha="center", va="center", fontsize=12, color="0.6")
    P = f[:, :2].numpy()
    ax.plot(P[:, 0], P[:, 1], "-", color="0.75", lw=3, zorder=1)
    best = lp.argmax(-1)
    for i in range(len(P)):
        k = best[i]
        if k == 0:
            ax.scatter(*P[i], s=12, color="0.55", zorder=2)
        else:
            ch = ALPHABET[k - 1]
            ax.scatter(*P[i], s=60 + 300 * lp[i, k], color=colour[ch], edgecolor="black", zorder=3)
            ax.annotate(ch, P[i], xytext=(4, -10), textcoords="offset points", fontsize=12, weight="bold")
    ax.set_xlim(-0.3, 10.3); ax.set_ylim(3.2, -0.2); ax.set_aspect("equal")
    ax.set_title(f"4. A human-like swipe of '{a.word}' as the network reads it: grey dots = 'nothing new'\n"
                 f"(CTC blank), coloured = the letter it fires at that step (size = confidence)")
    # 3. the same swipe as a timeline: each letter's probability at each step (CTC spikes)
    ax3 = fig.add_subplot(2, 2, 3)
    shown = sorted(set(a.word) | {ALPHABET[k - 1] for k in best if k})
    rows = [lp[:, 0]] + [lp[:, ALPHABET.index(c) + 1] for c in shown]
    ax3.imshow(np.array(rows), aspect="auto", cmap="magma", interpolation="nearest")
    ax3.set_yticks(range(len(rows))); ax3.set_yticklabels(["(blank)"] + shown, fontsize=11)
    ax3.set_xlabel("step along the swipe (every 0.3 key widths)")
    ax3.set_title(f"3. The same swipe as a timeline: each letter's probability at each step\n"
                  f"(bright = sure; mostly 'blank', with a short spike per letter: CTC)")
    fig.tight_layout()
    fig.savefig(a.out, dpi=80)
    print(f"letter-vector distance vs keyboard distance: r = {r:.2f}")


if __name__ == "__main__":
    main()
