#!/usr/bin/env python3
"""How real are the swipe generators on layouts other than QWERTY? (tools/swipe/gen_fidelity.py, 2026-11-24, user:
"sharpen your human swipe ... model"). On each FUTO swipe-5 layout with real swipes (held-out half's people), the
same words three ways: the real swipes, and swipes drawn by each generator (h2, CVAE v2) on that layout at the real
swipe's own canvas aspect. One recogniser (uni6 by default) decodes all three with the same lexicon.
A realistic generator scores about what real swipes score and fails on the same words: reported top-1 real vs
synthetic, the gap, and per-word agreement (both right or both wrong).
The numbers for layouts with no real swipes (Arabic, Russian: eval_app_layouts.py) are as trustworthy as the gap here.

    gen_fidelity.py --model uni6/best.pt [-n 600] [--layouts azerty:fr,german:de,...]
"""
import argparse, json, random
import numpy as np
import torch

from compare_futo import D, swipe5_items
from decode import beam
from prepare_futo import features
from prepare_synth import gen_of
from human import path as human_path
from uni import UniNet, layout_keys, phys_keys
import units

GENS = {"h2": "h2", "cvae2": f"cvae:{D}/swipe/cvae-v2.pt"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("-n", type=int, default=600)
    ap.add_argument("--layouts", default="qwerty:en,azerty:fr,german:de,spanish:es,qwertz:de,dvorak:en,clearflow:en,kasroz:en")
    a = ap.parse_args()
    torch.set_num_threads(3)
    model = UniNet(); model.load_state_dict(torch.load(a.model, map_location="cpu")); model.eval()
    gens = {k: gen_of(v) for k, v in GENS.items()}

    def decode(feat, table, sy, letters, root):
        k = phys_keys(table, sy).unsqueeze(0); km = torch.ones(1, len(letters), dtype=torch.bool)
        with torch.no_grad():
            lp = model(feat.unsqueeze(0), k, km)[0].numpy().astype(np.float64)
        c = beam(lp, root, letters, width=100, top=1)
        return c[0][0] if c else ""

    for spec in a.layouts.split(","):
        lay, lang = spec.split(":")
        items, root, alphabet = swipe5_items(lay, lang, all_people=False)
        random.Random(3).shuffle(items); items = items[: a.n]
        letters, table = layout_keys(lay)
        layout = json.load(open(f"{D}/swipe/futo/swipe-5/layouts/{lay}.json"))
        rng = random.Random(5)
        right = {"real": [], **{g: [] for g in gens}}
        for w, rec in items:
            sy = 10.0 * rec["canvas_height"] / rec["canvas_width"]
            f = features([{"x": x, "y": y} for x, y in zip(rec["x"], rec["y"])], 0.3, 10.0, sy)
            right["real"].append(decode(f, table, sy, letters, root) == w)
            sx, syy = units.scale("phys", layout, 1.0, rec["canvas_height"] / rec["canvas_width"])
            centres = units.keys(layout, sx, syy)
            for g, gen in gens.items():
                if hasattr(gen, "paths"):
                    p = gen.paths([w], centres, rng)[0]
                else:
                    p = [(x, y) for x, y, _ in human_path(w, centres, rng, gen)]
                if not p:
                    right[g].append(False); continue
                fs = features([{"x": x, "y": y} for x, y in p], 0.3, 1.0, 1.0)
                right[g].append(decode(fs, table, sy, letters, root) == w)
        r = np.array(right["real"])
        line = f"{lay}-{lang} (n {len(items)}): real {100 * r.mean():.1f}"
        for g in gens:
            s = np.array(right[g])
            line += f" | {g} {100 * s.mean():.1f} (gap {100 * (s.mean() - r.mean()):+.1f}, same outcome {100 * (s == r).mean():.0f}%)"
        print(line, flush=True)


if __name__ == "__main__":
    main()
