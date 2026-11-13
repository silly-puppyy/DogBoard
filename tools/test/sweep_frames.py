#!/usr/bin/env python3
"""Bug-sweep screenshots: the debug keyboard in each field type, captured DURING touches.

    tools/test/sweep_frames.py [--serial emulator-5554] [--out build/sweep]

Frames are taken while a finger is down (a background `input swipe` holds it), so states that
only exist mid-touch (key preview, long-press bar, slide-off, shift held) are visible; the
long-press flash bug was invisible in before/after screenshots. Writes one contact sheet per
scenario group into --out. Key positions are for the 1080x2424 emulator (420 dpi) with the
portrait key area scaled to the screen width (feel/Geometry.kt).
"""
import argparse
import subprocess
import time
from pathlib import Path

from PIL import Image

ADB = "/opt/android-sdk/platform-tools/adb"
PKG = "io.github.sillypuppyy.dogboard.debug"
IME = f"{PKG}/dev.patrickgold.florisboard.FlorisImeService"
ACTIVITY = f"{PKG}/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity"

ROW_Y = [1756, 1910, 2066, 2220]
KEYS = {c: (54 + 108 * i, ROW_Y[0]) for i, c in enumerate("qwertyuiop")}
KEYS.update({c: (108 + 108 * i, ROW_Y[1]) for i, c in enumerate("asdfghjkl")})
KEYS.update({c: (214 + 108 * i, ROW_Y[2]) for i, c in enumerate("zxcvbnm")})
KEYS.update({"shift": (70, ROW_Y[2]), "del": (1012, ROW_Y[2]), "123": (66, ROW_Y[3]),
             "fn": (200, ROW_Y[3]), "space": (540, ROW_Y[3]), "ret": (945, ROW_Y[3])})


class Dev:
    def __init__(self, serial):
        self.serial = serial

    def sh(self, cmd, bg=False):
        args = [ADB, "-s", self.serial, "shell", cmd]
        if bg:
            return subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return subprocess.run(args, capture_output=True, text=True).stdout

    def shot(self, path):
        with open(path, "wb") as f:
            subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"], stdout=f)

    def field(self, kind):
        # no force-stop: the test activity shares the keyboard's process, a stop restarts the IME
        self.sh(f"ime set {IME}")
        extra = f"--es field {kind}" if kind != "text" else ""
        self.sh(f"am start -W -f 0x10008000 -n {ACTIVITY} {extra}")
        time.sleep(2.5)

    def tap(self, key):
        x, y = KEYS[key]
        self.sh(f"input tap {x} {y}")
        time.sleep(0.25)

    def hold(self, key, ms, to=None):
        """Starts a press on [key] (sliding to [to] if given) lasting [ms]; returns at once."""
        x, y = KEYS[key]
        x2, y2 = KEYS[to] if to else (x, y)
        return self.sh(f"input swipe {x} {y} {x2} {y2} {ms}", bg=True)


def sheet(frames, out, label):
    ims = [Image.open(f).crop((0, 1200, 1080, 2424)).resize((360, 408)) for f in frames]
    s = Image.new("RGB", (360 * len(ims), 408), "white")
    for i, im in enumerate(ims):
        s.paste(im, (360 * i, 0))
    s.save(out / f"{label}.png")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--out", default="build/sweep")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    tmp = out / "raw"
    tmp.mkdir(exist_ok=True)
    d = Dev(args.serial)

    # 1. each field type at rest
    kinds = ["text", "email", "url", "name", "caps", "password", "visiblepassword", "number", "decimal", "phone", "nosuggest"]
    frames = []
    for k in kinds:
        d.field(k)
        f = tmp / f"field_{k}.png"
        d.shot(f)
        frames.append(f)
    for i in range(0, len(frames), 4):
        sheet(frames[i:i + 4], out, f"fields_{i // 4}_" + "-".join(kinds[i:i + 4]))

    # 2. touches in a text field, frames while the finger is down
    d.field("text")
    scenarios = [
        ("tap_g", lambda: d.hold("g", 300), [0.12]),
        ("hold_e", lambda: d.hold("e", 1500), [0.2, 0.9]),
        ("slide_e_to_r", lambda: d.hold("e", 900, "r"), [0.15, 0.7]),
        ("hold_shift", lambda: d.hold("shift", 1200), [0.2, 0.9]),
        ("hold_space", lambda: d.hold("space", 1500), [0.3, 1.0]),
        ("hold_del", lambda: d.hold("del", 1500), [0.3, 1.0]),
        ("hold_123", lambda: d.hold("123", 900), [0.2]),
        ("hold_fn", lambda: d.hold("fn", 1200), [0.2, 0.9]),
    ]
    for name, start, times in scenarios:
        for c in "hello ":
            d.tap("space" if c == " " else c)
        frames = []
        t0 = time.time()
        p = start()
        for t in times:
            time.sleep(max(0.0, t0 + t - time.time()))
            f = tmp / f"{name}_{t}.png"
            d.shot(f)
            frames.append(f)
        p.wait()
        time.sleep(0.4)
        if name == "hold_fn":
            d.sh("input keyevent BACK")  # the globe key's long press opens the system keyboard picker
        f = tmp / f"{name}_after.png"
        d.shot(f)
        frames.append(f)
        sheet(frames, out, f"touch_{name}")
    print(f"sheets in {out}")


if __name__ == "__main__":
    main()
