#!/usr/bin/env python3
"""End-to-end typing test: tap DogBoard's keys over adb and check what lands in the text field.

Uses the debug build's test screen (ImeTestActivity) and two debug log tags:
    DogBoardGeom  the key area's screen bounds
    DogBoardText  the test field's text after every change

    tools/test/type_test.py --serial emulator-5554
    tools/test/type_test.py --serial emulator-5554 --noise-mm 1.5 --seed 3   # sloppy taps

Run it on the emulator, not on a phone someone is using.
"""
import argparse
import random
import re
import subprocess
import sys
import time

ADB = "/opt/android-sdk/platform-tools/adb"
PKG = "io.github.sillypuppyy.dogboard.debug"  # provides the test screen
IME = f"{PKG}/dev.patrickgold.florisboard.FlorisImeService"
ACTIVITY = f"{PKG}/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity"

# Base layer geometry in keyboard3 width units (10 per row), see tools/layouts/gen_qwerty_en.py
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_X0 = [0.0, 0.5, 48 / 37.5 + 8 / 37.5]  # row 3 starts after shift + gap
SPACE_CENTER_X = (46.5 + 47 + 188 / 2) / 37.5
DP_PER_MM = 6.3

CASES = [
    ("hello world", "Hello world"),
    ("the quick brown fox", "The quick brown fox"),
    ("jumps over the lazy dog", "Jumps over the lazy dog"),
]


def adb(serial, *args):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True).stdout


def last_match(serial, tag, pattern):
    out = adb(serial, "logcat", "-d", "-s", f"{tag}:I")
    found = None
    for line in out.splitlines():
        m = re.search(pattern, line)
        if m:
            found = m
    return found


GEOM = r"key area (-?\d+) (-?\d+) (\d+) (\d+) rows (\d+)"


def settled_geometry(serial, tries=80):
    """Last logged key-area bounds once the slide-in animation has settled (height > 0, unchanged)."""
    previous = None
    for _ in range(tries):
        m = last_match(serial, "DogBoardGeom", GEOM)
        if m and int(m.group(4)) > 0:
            if previous == m.groups():
                return m
            previous = m.groups()
        time.sleep(0.3)
    return None


def key_center_units(ch):
    if ch == " ":
        return SPACE_CENTER_X, 3
    for row, letters in enumerate(ROWS):
        i = letters.find(ch)
        if i >= 0:
            return ROW_X0[row] + i + 0.5, row
    raise ValueError(f"no key for {ch!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--noise-mm", type=float, default=0.0, help="std-dev of random tap error")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--delay", type=float, default=0.25, help="seconds between taps")
    ap.add_argument("--ime", default=IME, help="keyboard under test (DogBoard debug or bench build)")
    args = ap.parse_args()
    ime = args.ime
    ime_package = ime.split("/")[0]
    rng = random.Random(args.seed)

    adb(args.serial, "shell", "ime", "enable", ime)
    adb(args.serial, "shell", "ime", "set", ime)
    density = int(adb(args.serial, "shell", "wm", "density").split(":")[-1].strip()) / 160.0

    failures = 0
    for typed, expected in CASES:
        adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)
        adb(args.serial, "logcat", "-c")
        adb(args.serial, "shell", "am", "start", "-n", ACTIVITY)
        geom = settled_geometry(args.serial)
        if not geom:
            # the key area may not have moved since the last run, so nothing new was logged
            adb(args.serial, "shell", "am", "force-stop", ime_package)
            adb(args.serial, "shell", "ime", "set", ime)
            adb(args.serial, "logcat", "-c")
            adb(args.serial, "shell", "am", "start", "-n", ACTIVITY)
            geom = settled_geometry(args.serial)
        if not geom:
            print("FAIL: keyboard never reported its key area")
            sys.exit(2)
        x0, y0, w, h, rows = map(int, geom.groups())
        unit = w / 10.0
        row_h = h / rows
        noise_px = args.noise_mm * DP_PER_MM * density
        for ch in typed:
            ux, row = key_center_units(ch)
            x = x0 + ux * unit + rng.gauss(0, noise_px)
            y = y0 + (row + 0.5) * row_h + rng.gauss(0, noise_px)
            adb(args.serial, "shell", "input", "tap", str(int(x)), str(int(y)))
            time.sleep(args.delay)
        time.sleep(0.8)
        m = last_match(args.serial, "DogBoardText", r"text=\[(.*)\]")
        got = m.group(1) if m else None
        ok = got == expected
        failures += 0 if ok else 1
        print(f"{'ok  ' if ok else 'FAIL'} typed {typed!r} -> {got!r} (expected {expected!r})")
    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
