#!/usr/bin/env python3
"""Exports a trained tap-typing net (mltt_train.py) for the app's TapNet.kt, plus a golden file for its test.

    tools/ml/mltt_export.py MODEL.pt OUT.bin [--golden GOLDEN.txt]

Binary layout (little-endian float32 arrays, each preceded by its element count as int32), after the
magic "DGT1" and int32 counts (input symbols, hidden size, layers, output symbols):
  input linear W (H x IN), b (H)
  per layer: BatchNorm folded to scale, shift (in each); forward LSTM W_ih (4H x in), W_hh (4H x H),
  b (4H, ih + hh); reverse LSTM the same
  output linear W (OUT x 2H), b (OUT)
Gate order is PyTorch's (i, f, g, o). The golden file holds a few key-code inputs and the expected
log-probabilities (frame by frame), to check the Kotlin forward pass against PyTorch.
"""
import argparse
import struct
import sys

import numpy as np
import torch

sys.path.insert(0, __import__("os").path.dirname(__file__))
from mltt_train import Net, frames  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("out")
    ap.add_argument("--golden", default="")
    args = ap.parse_args()
    net = Net()
    net.load_state_dict(torch.load(args.model, map_location="cpu"))
    net.eval()
    sd = {k: v.detach().numpy().astype(np.float32) for k, v in net.state_dict().items()}

    def arr(f, a):
        a = np.ascontiguousarray(a, dtype="<f4").ravel()
        f.write(struct.pack("<i", a.size))
        f.write(a.tobytes())

    H = net.inp.out_features
    with open(args.out, "wb") as f:
        f.write(b"DGT1")
        f.write(struct.pack("<4i", net.inp.in_features, H, len(net.lstms), net.out.out_features))
        arr(f, sd["inp.weight"]); arr(f, sd["inp.bias"])
        for i in range(len(net.lstms)):
            m, v = sd[f"norms.{i}.running_mean"], sd[f"norms.{i}.running_var"]
            w, b = sd[f"norms.{i}.weight"], sd[f"norms.{i}.bias"]
            scale = w / np.sqrt(v + net.norms[i].eps)
            arr(f, scale); arr(f, b - m * scale)
            for sfx in ("", "_reverse"):
                arr(f, sd[f"lstms.{i}.weight_ih_l0{sfx}"])
                arr(f, sd[f"lstms.{i}.weight_hh_l0{sfx}"])
                arr(f, sd[f"lstms.{i}.bias_ih_l0{sfx}"] + sd[f"lstms.{i}.bias_hh_l0{sfx}"])
        arr(f, sd["out.weight"]); arr(f, sd["out.bias"])
    print(f"wrote {args.out}")

    if args.golden:
        words = ["hello", "tje", "keyboard", "wprd", "qz"]
        with open(args.golden, "w") as g, torch.no_grad():
            for w in words:
                x = np.array([[ord(c) - 96 for c in w]], dtype=np.int8)
                xx, _ = frames(x, np.array([len(w)]))
                lp = net(torch.from_numpy(xx))[0].numpy()
                g.write(w + "\n")
                for row in lp:
                    g.write(" ".join(f"{v:.5f}" for v in row) + "\n")
        print(f"wrote {args.golden}")


if __name__ == "__main__":
    main()
