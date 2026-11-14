#!/usr/bin/env python3
"""How tall a keyboard is on phones of different sizes: the emulator pretends to be each screen
(wm size / wm density), the test field is opened and the keys' bounds are read from the accessibility
dump (Gboard: key_pos_* ids; others: any clickable node inside the keyboard window).

    tools/test/keyboard_sizes.py --serial emulator-5554 [--keyboards gboard] [--widths 320,360,384,393,411,430,480]
        [--heights 640,800,915]   (screen heights in dp at the middle width, to see whether height matters)

Prints, per screen: width x height dp, keyboard height (top letter row to the bottom row), letter row
pitch, letter key width and height, all in dp. Restores the screen size and density afterwards.
"""
import argparse
import re
import statistics
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from bench_keyboards import ADB, KEYBOARDS, Device  # noqa: E402

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def keys_of(dev, pkg):
    """Bounds (px) of the keyboard's keys from `uiautomator dump --windows`."""
    dev.sh("uiautomator dump --windows /data/local/tmp/kb.xml >/dev/null 2>&1")
    xml = dev.sh("cat /data/local/tmp/kb.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    out = []
    for n in root.iter("node"):
        if n.get("package") != pkg:
            continue
        rid, text, desc = n.get("resource-id", ""), n.get("text", ""), n.get("content-desc", "")
        label = (text or desc).strip()
        if "key_pos" in rid or (n.get("clickable") == "true" and len(label) == 1 and label.isalpha()):
            m = BOUNDS.search(n.get("bounds", ""))
            if m:
                x0, y0, x1, y1 = map(int, m.groups())
                if x1 > x0 and y1 > y0:
                    out.append((label.lower(), rid, x0, y0, x1, y1))
    return out


def measure(dev, keys, density):
    dp = density / 160
    letters = [k for k in keys if len(k[0]) == 1 and k[0].isalpha()]
    use = letters if len(letters) >= 20 else keys
    rows = sorted({round((k[3] + k[5]) / 2) for k in use})
    merged = []
    for y in rows:  # centres within a few px are one row
        if not merged or y - merged[-1] > 10:
            merged.append(y)
    pitch = statistics.median(b - a for a, b in zip(merged, merged[1:])) / dp if len(merged) > 1 else 0
    top = min(k[3] for k in keys) / dp
    bottom = max(k[5] for k in keys) / dp
    kw = statistics.median(k[4] - k[2] for k in use) / dp
    kh = statistics.median(k[5] - k[3] for k in use) / dp
    return bottom - top, pitch, kw, kh, len(merged)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--keyboards", default="gboard")
    ap.add_argument("--widths", default="320,360,384,393,411,430,480")
    ap.add_argument("--heights", default="", help="screen heights (dp) to try at the middle width")
    ap.add_argument("--aspect", type=float, default=20 / 9, help="screen height / width for the width sweep")
    args = ap.parse_args()
    dev = Device(args.serial)
    density = int(re.search(r"(\d+)", dev.sh("wm density").splitlines()[0]).group(1))
    screens = [(w, round(w * args.aspect)) for w in map(int, args.widths.split(","))]
    if args.heights:
        mid = screens[len(screens) // 2][0]
        screens += [(mid, h) for h in map(int, args.heights.split(","))]
    try:
        for name in args.keyboards.split(","):
            ime = KEYBOARDS[name]
            pkg = ime.split("/")[0]
            dev.sh(f"ime enable {ime}")
            dev.sh(f"ime set {ime}")
            print(f"== {name} (density {density})")
            print("   screen dp      kb height  row pitch  key w x h   rows")
            for w, h in screens:
                dev.sh(f"wm size {round(w * density / 160)}x{round(h * density / 160)}")
                time.sleep(2)
                dev.fresh_field()
                time.sleep(1.5)
                keys = keys_of(dev, pkg)
                if not keys:
                    print(f"   {w:4d} x {h:4d}   no keys found")
                    continue
                kb, pitch, kw, kh, nrows = measure(dev, keys, density)
                print(f"   {w:4d} x {h:4d}   {kb:7.1f}    {pitch:6.1f}    {kw:4.1f} x {kh:4.1f}   {nrows}", flush=True)
    finally:
        dev.sh("wm size reset")


if __name__ == "__main__":
    main()
