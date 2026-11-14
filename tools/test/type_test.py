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
from pathlib import Path

ADB = "/opt/android-sdk/platform-tools/adb"
PKG = "io.github.sillypuppyy.dogboard.debug"  # provides the test screen
IME = f"{PKG}/dev.patrickgold.florisboard.FlorisImeService"
ACTIVITY = f"{PKG}/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity"

# Base layer geometry in keyboard3 width units (10 per row), see tools/layouts/gen_qwerty_en.py
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_X0 = [0.0, 0.5, 48 / 37.5 + 8 / 37.5]  # row 3 starts after shift + gap
SPACE_CENTER_X = (46.5 + 47 + 188 / 2) / 37.5
BACKSPACE_CENTER_X = 10 - 1.28 / 2
MODE_CENTER_X = 1.24 / 2
DIGITS = "1234567890"
DP_PER_MM = 6.3

CASES = [
    ("hello world", "Hello world"),
    ("the quick brown fox", "The quick brown fox"),
    ("jumps over the lazy dog", "Jumps over the lazy dog"),
    ("see you  soon", "See you. Soon"),  # double-space period, then auto-shift
    # neighbouring-key typos: adb taps are slow (0.4+ s apart), and iOS-rule edit costs such as a
    # swapped pair grow with the time between taps, so timing-dependent typos are unit-tested instead
    ("thw dog", "The dog"),  # autocorrect on space keeps the auto-shift capital
    ("i dont know", "I don't know"),  # smart quotes off by default (user): a straight apostrophe
    # "<" is backspace: right after an autocorrection it restores the typed word. Undoing also
    # rejects a correction (iOS rule: blocked for under a day after one undo, see Rejections.kt),
    # so smoke.sh clears the learned files before each run.
    ("hrllo <x", "Hrllox"),
    ("omw x", "On my way! X"),  # text replacement (iOS sample shortcut) beats autocorrect; "!" + space starts a sentence
    ("john smith", "John Smith", "name"),  # name field: every word capitalised
    ("on the {123}1{123}st x", "On the 1st x"),
    ("the {123}1{123}sr x", "The 1st x"),  # one slipped key in a number's suffix is fixed (DigitWords)  # {123} again = back to letters; letters after a digit are not a word: "st" stays
    ("thw dog", "Thw Dog", "name"),  # name/address fields: no autocorrect (no learning either)
    ("abc", "ABC", "caps"),  # all-caps field
    ("tehx{L}h ", "Tehh x"),
    ("hello{S-28}x", "Hexllo"),  # {S-28}: hold space, slide 28 dp left = 3 cursor steps (8 dp each), no space typed
    ("hey {123}12 x", "Hey 12 x"),  # {123} = mode key; space on the 123 plane returns to letters  # {L} = cursor left: typing then space inside a word must not autocorrect the part before the cursor
]


def adb(serial, *args):
    try:
        return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=30).stdout
    except subprocess.TimeoutExpired:
        print(f"FAIL: adb {' '.join(args)} timed out (device gone?)")
        sys.exit(3)


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


def wait_ime_shown(serial, timeout=10.0):
    """Waits for the test screen's "ime shown" mark (end of the keyboard's slide-in)."""
    end = time.time() + timeout
    while time.time() < end:
        if "ime shown" in adb(serial, "logcat", "-d", "-s", "DogBoardPerf:I"):
            time.sleep(0.2)
            return True
        time.sleep(0.2)
    return False


def wait_engine_loaded(serial, timeout=20.0):
    """Waits for the keyboard's "lexicon load took" mark: until then nothing is corrected."""
    end = time.time() + timeout
    while time.time() < end:
        if "lexicon load took" in adb(serial, "logcat", "-d", "-s", "DogBoardPerf:I"):
            return True
        time.sleep(0.2)
    return False


def key_center_units(ch):
    if ch == " ":
        return SPACE_CENTER_X, 3
    if ch == "{123}":
        return MODE_CENTER_X, 3
    if ch in DIGITS:  # top row of the 123 plane
        return DIGITS.index(ch) + 0.5, 0
    if ch == "<":
        return BACKSPACE_CENTER_X, 2
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
    ap.add_argument("--case", help="only run cases whose typed text contains this")
    ap.add_argument("--repeat", type=int, default=1, help="run each case this many times")
    ap.add_argument("--history", action="store_true", help="print every text change on failure")
    args = ap.parse_args()
    ime = args.ime
    ime_package = ime.split("/")[0]
    rng = random.Random(args.seed)

    if args.serial.startswith("emulator-"):
        # the emulator segfaults now and then in its own GL code (emulator.sh): boot it if gone
        subprocess.run([str(Path(__file__).with_name("emulator.sh")), args.serial], check=True, timeout=300)
    adb(args.serial, "shell", "ime", "enable", ime)
    adb(args.serial, "shell", "ime", "set", ime)
    density = int(adb(args.serial, "shell", "wm", "density").split(":")[-1].strip()) / 160.0

    # taps straight into the touch device (bench_keyboards.taptype) on a rooted emulator: one
    # process per word instead of one adb command per letter
    taps_dev = None
    if "uid=0" in adb(args.serial, "shell", "id"):
        sys.path.insert(0, str(Path(__file__).parent))
        import bench_keyboards
        taps_dev = bench_keyboards.Device(args.serial)
        bench_keyboards.ensure_taptype(taps_dev)

    failures = 0
    cases = [c for c in CASES if args.case is None or args.case in c[0]] * args.repeat
    for case in cases:
        typed, expected = case[0], case[1]
        field = case[2] if len(case) > 2 else None
        adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)
        adb(args.serial, "logcat", "-c")
        # NEW_TASK | CLEAR_TASK: always a fresh test screen, so the field type extra applies
        start = ["shell", "am", "start", "-f", "0x10008000", "-n", ACTIVITY, *(["--es", "field", field] if field else [])]
        adb(args.serial, *start)
        # the key area is laid out before the window has slid in; taps during the slide are lost
        wait_ime_shown(args.serial)
        geom = settled_geometry(args.serial)
        if not geom:
            # the key area may not have moved since the last run, so nothing new was logged
            adb(args.serial, "shell", "am", "force-stop", ime_package)
            adb(args.serial, "shell", "ime", "set", ime)
            adb(args.serial, "logcat", "-c")
            adb(args.serial, *start)
            geom = settled_geometry(args.serial)
            # a cold keyboard loads its word data in the background; typing before that is not corrected
            if not wait_engine_loaded(args.serial):
                print("FAIL: keyboard never finished loading its word data")
                sys.exit(2)
        if not geom:
            print("FAIL: keyboard never reported its key area")
            sys.exit(2)
        x0, y0, w, h, rows = map(int, geom.groups())
        unit = w / 10.0
        row_h = h / rows
        noise_px = args.noise_mm * DP_PER_MM * density
        pending = []  # plain taps, sent together through taptype when the device allows it

        def flush():
            if not pending:
                return
            if taps_dev:
                taps_dev.tap(pending, gap_s=max(args.delay - 0.04, 0.0))
            else:
                for x, y in pending:
                    adb(args.serial, "shell", "input", "tap", str(int(x)), str(int(y)))
                    time.sleep(args.delay)
            pending.clear()

        for ch in re.findall(r"\{L\}|\{123\}|\{S-?\d+\}|.", typed):
            if ch.startswith("{") and len(ch) > 1:
                flush()
            if ch == "{L}":
                adb(args.serial, "shell", "input", "keyevent", "KEYCODE_DPAD_LEFT")
                time.sleep(args.delay)
                continue
            if ch.startswith("{S"):
                # hold space 0.6 s (trackpad needs 0.375 s), then slide dx dp slowly (1 dp moves, where
                # iOS's acceleration adds ~4%: a precise drag), then lift
                dx_dp = int(ch[2:-1])
                dx = dx_dp * density
                n = max(abs(dx_dp), 1)
                sx, sy = x0 + SPACE_CENTER_X * unit, y0 + 3.5 * row_h
                moves = " ; ".join(f"input motionevent MOVE {int(sx + dx * i / n)} {int(sy)}" for i in range(1, n + 1))
                adb(args.serial, "shell",
                    f"input motionevent DOWN {int(sx)} {int(sy)} ; sleep 0.6 ; {moves} ; input motionevent UP {int(sx + dx)} {int(sy)}")
                time.sleep(args.delay)
                continue
            ux, row = key_center_units(ch)
            x = x0 + ux * unit + rng.gauss(0, noise_px)
            y = y0 + (row + 0.5) * row_h + rng.gauss(0, noise_px)
            pending.append((x, y))
        flush()
        time.sleep(0.8)
        m = last_match(args.serial, "DogBoardText", r"text=\[(.*)\]")
        got = m.group(1) if m else None
        ok = got == expected
        failures += 0 if ok else 1
        print(f"{'ok  ' if ok else 'FAIL'} typed {typed!r} -> {got!r} (expected {expected!r})")
        if not ok and args.history:
            for line in adb(args.serial, "logcat", "-d", "-s", "DogBoardText:I", "DogBoardTouch:I", "DogBoardGeom:I").splitlines():
                m = re.search(r"text=\[(.*)\]", line)
                if m:
                    print(f"      {line[:18]} {m.group(1)!r}")
                elif "DogBoardTouch" in line or "DogBoardGeom" in line:
                    print(f"      {line[:18]} {line.split(': ', 1)[-1]}")
    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
