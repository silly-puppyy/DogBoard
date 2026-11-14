#!/usr/bin/env python3
"""Autocorrect benchmark: DogBoard against other keyboards (Gboard, FUTO, HeliBoard), same taps.

    tools/test/bench_keyboards.py --serial emulator-5554 [--keyboards dogboard,gboard,futo] \
        [--sentences 40] [--noise-mm 1.2] [--seed 1]

Emulator only (needs a root shell). For each keyboard it:
  1. finds the keys by tapping a grid over the keyboard and reading what lands in the test field
     (DogBoard debug's ImeTestActivity logs every text change as DogBoardText), so no keyboard's
     layout has to be known in advance;
  2. types held-out Tatoeba sentences (ids ending in 0, never used to build DogBoard's data) and a
     list of typos and run-together words, with the same random tap errors for every keyboard,
     writing touches straight into the kernel touch device (sendevent);
  3. measures accuracy (words right after the sentence, ignoring case and final punctuation) and
     speed: from each finger lift (kernel time, getevent) to the next change of the field's text
     (logcat time), both on the device's monotonic clock.

Results go to stdout and, as JSON, to --out.
"""
import argparse
import json
import random
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

ADB = "/opt/android-sdk/platform-tools/adb"
TEST_PKG = "io.github.sillypuppyy.dogboard.debug"
ACTIVITY = f"{TEST_PKG}/io.github.sillypuppyy.dogboard.imetest.ImeTestActivity"
TOUCH_DEV = "/dev/input/event2"  # the emulator's main-screen touch device
TAPTYPE = "/data/local/tmp/taptype"  # tools/test/taptype, built with the NDK and pushed by main()
TAPTYPE_SRC = Path(__file__).parent / "taptype" / "taptype.c"
RAW_MAX = 32767

KEYBOARDS = {
    "dogboard": "io.github.sillypuppyy.dogboard.bench/dev.patrickgold.florisboard.FlorisImeService",
    "gboard": "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME",
    "futo": "org.futo.inputmethod.latin/.LatinIME",
    "heliboard": "helium314.keyboard/.latin.LatinIME",
}

TATOEBA = Path.home() / "Documents/Code/AI/keyboard/data/tatoeba/eng_sentences.tsv"

# (typed, expected): common misspellings and words run together, typed with exact taps
TYPOS = [
    ("becuse", "because"), ("teh", "the"), ("recieve", "receive"), ("tomorow", "tomorrow"),
    ("definately", "definitely"), ("wierd", "weird"), ("seperate", "separate"), ("untill", "until"),
    ("occured", "occurred"), ("thier", "their"), ("dont", "don't"), ("im", "I'm"), ("cant", "can't"),
    ("helloeveryone", "hello everyone"), ("thankyou", "thank you"), ("seeyousoon", "see you soon"),
    ("hellowvryone", "hello everyone"), ("goodmorning", "good morning"),
]


class Device:
    def __init__(self, serial):
        self.serial = serial
        self.w, self.h = map(int, self.sh("wm size").split(":")[-1].strip().split("x"))
        self.density = int(self.sh("wm density").split(":")[-1].strip()) / 160.0
        self.px_per_mm = 160 / 25.4 * self.density
        self.touch_id = 1000  # a new tracking id per touch: some keyboards ignore a repeated one

    def sh(self, cmd, timeout=60):
        return subprocess.run([ADB, "-s", self.serial, "shell", cmd], capture_output=True, text=True,
                              timeout=timeout).stdout

    def tap(self, points, gap_s=0.14, rng=None):
        """Taps through taptype (one native process per call, exact timing): 40 ms presses, gap_s
        apart, with a little human jitter when rng is given."""
        lines = []
        for x, y in points:
            rx = max(0, min(RAW_MAX, int(x * RAW_MAX / self.w)))
            ry = max(0, min(RAW_MAX, int(y * RAW_MAX / self.h)))
            gap = gap_s if rng is None else min(max(rng.gauss(gap_s, 0.03), 0.06), 0.4)
            lines.append(f"{rx} {ry} 40 {int(gap * 1000)}")
        subprocess.run([ADB, "-s", self.serial, "shell", f"{TAPTYPE} {TOUCH_DEV}"], input="\n".join(lines) + "\n",
                       text=True, timeout=300)

    def texts(self):
        """Field text changes since the last clear: [(monotonic time s, text)]."""
        out = subprocess.run([ADB, "-s", self.serial, "logcat", "-d", "-v", "monotonic", "-s", "DogBoardText:I"],
                             capture_output=True, text=True, timeout=60).stdout
        found = []
        for line in out.splitlines():
            m = re.match(r"\s*(\d+\.\d+)\s.*text=\[(.*)\]$", line)
            if m:
                found.append((float(m.group(1)), m.group(2)))
        return found

    def text(self):
        t = self.texts()
        return t[-1][1] if t else ""

    def fresh_field(self):
        subprocess.run([ADB, "-s", self.serial, "logcat", "-c"], timeout=60)
        self.sh(f"am start -W -f 0x10008000 -n {ACTIVITY}")  # -W: returns once the screen is up
        time.sleep(0.6)  # the keyboard's slide-in


class LiftClock:
    """Kernel timestamps (monotonic) of finger lifts on the touch device: getevent logs to a file on
    the device while typing (piped through adb its output is held back)."""
    LOG = "/data/local/tmp/dogboard_bench_events.txt"

    def __init__(self, dev):
        self.dev = dev

    def start(self):
        self.dev.sh(f"pkill getevent; rm -f {self.LOG}; setsid nohup getevent -t {TOUCH_DEV} > {self.LOG} 2>/dev/null < /dev/null &")
        time.sleep(0.3)

    def stop(self):
        """Lift times since start(): "[   20391.988086] 0003 0039 ffffffff" = tracking id -1."""
        out = self.dev.sh(f"pkill getevent; cat {self.LOG}")
        return [float(m.group(1)) for m in re.finditer(r"\[\s*(\d+\.\d+)\]\s+0003 0039 ffffffff", out)]


def calibrate(dev):
    """Key centres in screen pixels: letter -> (x, y), and the space bar, found by probing taps."""
    dev.fresh_field()

    def probe(x, y):
        before = dev.text()
        dev.tap([(x, y)], gap_s=0.0)
        time.sleep(0.35)
        after = dev.text()
        if len(after) == len(before) + 1 and after.startswith(before) and after[-1].isalpha():
            return after[-1].lower()
        if after != before:  # a suggestion, a deletion or a space: start again from an empty field
            dev.fresh_field()
            if after.startswith(before) and after[len(before):] == " ":
                return " "
        return None

    # rows: a vertical sweep up the middle from the bottom, stopping above the third letter row so
    # it never reaches the toolbar over the keys (Gboard has its clipboard button there)
    found = []
    y = dev.h - 10
    while y > dev.h * 0.4:
        c = probe(dev.w // 2, y)
        found.append((y, c))
        letters = [c for _, c in found if c and c != " "]
        row_starts = sum(1 for k in range(len(letters)) if k == 0 or letters[k] != letters[k - 1])
        if row_starts >= 3 and not (c and c != " "):
            break
        y -= int(dev.px_per_mm * 2)
    found.reverse()
    rows = []  # (y_from, y_to) of each letter row, top to bottom
    for y, c in found:
        if c and c != " ":
            if rows and rows[-1][2] == c:
                rows[-1][1] = y
            else:
                rows.append([y, y, c])
    rows = [r for r in rows if r[2] in "tyghbvnf"][-3:]
    space_ys = []  # the lowest unbroken run of probes that typed a space: the space bar
    for y, c in reversed(found):
        if c == " ":
            space_ys.append(y)
        elif space_ys:
            break
    if len(rows) != 3 or not space_ys:
        raise RuntimeError(f"could not find the letter rows: {found}")
    keys = {}
    step = int(dev.px_per_mm * 1.2)
    for y0, y1, _ in rows:
        yc = (y0 + y1) // 2
        spans = {}
        for direction in (-1, 1):
            x = dev.w // 2
            while 0 < x < dev.w:
                c = probe(x, yc)
                if not c or c == " ":
                    break
                spans.setdefault(c, []).append(x)
                x += direction * step
        for c, xs in spans.items():
            keys[c] = ((min(xs) + max(xs)) / 2, yc)
    keys[" "] = (dev.w / 2, (min(space_ys) + max(space_ys)) / 2)
    print(f"   rows {[(y0, y1, c) for y0, y1, c in rows]}, space {min(space_ys)}-{max(space_ys)}", flush=True)
    missing = [c for c in "abcdefghijklmnopqrstuvwxyz" if c not in keys]
    if missing:
        raise RuntimeError(f"keys not found: {missing}")
    return keys


def words_of(s):
    return [w.strip(".,!?").replace("’", "'").lower() for w in s.split()]


def type_text(dev, keys, text, rng, noise_mm, gap_s, noise_y_mm=None, far=0.0, tail=None):
    """Taps with Gaussian error (noise_mm across, noise_y_mm down); with share `far`, a tap is instead
    moved by an offset drawn from `tail` (key pitches, IMK taps >1.5 pitches off: imk-tail.tsv)."""
    sx = noise_mm * dev.px_per_mm
    sy = (noise_mm if noise_y_mm is None else noise_y_mm) * dev.px_per_mm
    pitch = keys["s"][0] - keys["a"][0]
    letters = [keys[c] for c in "abcdefghijklmnopqrstuvwxyz" if c in keys]
    left, right = min(p[0] for p in letters) - pitch / 2, max(p[0] for p in letters) + pitch / 2
    top, bottom = min(p[1] for p in letters) - pitch / 2, max(p[1] for p in letters) + pitch / 2
    points = []
    for ch in text.lower():
        x, y = keys[ch]
        if ch != " ":
            gx, gy, u = rng.gauss(0, sx), rng.gauss(0, sy), rng.random()
            if tail and u < far:
                # redrawn until it lands on the letter area: off the keyboard, a tap would move the cursor or
                # pick a suggestion (the IMK typists had no visible edge, so their tail holds such taps)
                while True:
                    dx, dy = tail[rng.randrange(len(tail))]
                    if left <= x + dx * pitch <= right and top <= y + dy * pitch <= bottom:
                        break
                x, y = x + dx * pitch, y + dy * pitch
            else:
                x, y = x + gx, y + gy
        points.append((x, y))
    dev.tap(points, gap_s, rng)
    time.sleep(0.8)


def run(dev, name, ime, sentences, args):
    print(f"== {name}", flush=True)
    pkg = ime.split("/")[0]
    if name == "dogboard":
        # start from nothing learned: earlier runs (and this run's key probing) teach it words
        dev.sh(f"am force-stop {pkg}; cd /data/data/{pkg}/files/dogboard && rm -f personal.tsv "
               "rejected_corrections.txt user_words.txt emoji_usage.tsv")
    dev.sh(f"ime enable {ime}")
    dev.sh(f"ime set {ime}")
    # a keyboard switched to cold needs a moment before it takes taps
    dev.fresh_field()
    time.sleep(5.0)
    cache = Path(args.keys_cache)
    cached = json.loads(cache.read_text()) if cache.exists() else {}
    tag = f"{name} {dev.w}x{dev.h}"
    if tag in cached and not args.recalibrate:
        keys = {c: tuple(v) for c, v in cached[tag].items()}
    else:
        keys = calibrate(dev)
        cached[tag] = keys
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_text(json.dumps(cached, indent=1))
    if name == "dogboard":  # forget what the probing taught it, then let it load its word data again
        dev.sh(f"am force-stop {pkg}; cd /data/data/{pkg}/files/dogboard && rm -f personal.tsv "
               "rejected_corrections.txt user_words.txt emoji_usage.tsv")
        dev.fresh_field()
        time.sleep(3)
    result = {"keyboard": name, "keys": keys}
    clock = LiftClock(dev)
    latencies = {"letter": [], "space": []}
    right = total = 0
    misses = []
    rng = random.Random(args.seed)
    for s in sentences:
        typed = re.sub(r"[^a-z ]", "", s.lower().replace("'", "").replace("’", ""))
        dev.fresh_field()
        clock.start()
        type_text(dev, keys, typed + " ", rng, args.noise_mm, args.gap, args.noise_y_mm, args.far_taps, args.tail_offsets)
        lifts = clock.stop()
        changes = dev.texts()
        got = changes[-1][1] if changes else ""
        exp_w, got_w = words_of(s), words_of(got)
        ok = sum(1 for a, b in zip(exp_w, got_w) if a == b) if len(exp_w) == len(got_w) else \
            len(set(exp_w) & set(got_w))  # misaligned (a split or merge): count shared words
        right += ok
        total += len(exp_w)
        if ok < len(exp_w):
            misses.append((s, got))
        # lift i belongs to character i of what was typed; the space is where autocorrection runs
        for t, ch in zip(lifts, typed + " "):
            after = [c for c, _ in changes if c >= t - 0.002]
            if after:
                latencies["space" if ch == " " else "letter"].append((after[0] - t) * 1000)
    typo_right = 0
    for typed, expected in TYPOS:
        dev.fresh_field()
        type_text(dev, keys, typed + " ", random.Random(0), 0.0, args.gap)
        got = dev.text().strip()
        ok = words_of(got) == words_of(expected)
        typo_right += ok
        if not ok:
            misses.append((typed, got))
    result.update(word_accuracy=right / total if total else 0, words=total, typos_right=typo_right,
                  typos=len(TYPOS), misses=misses)
    timing = []
    for kind, values in latencies.items():
        lat = sorted(l for l in values if l < 2000)
        if lat:
            result[f"{kind}_ms_median"] = statistics.median(lat)
            result[f"{kind}_ms_p95"] = lat[int(len(lat) * 0.95)]
            timing.append(f"{kind} median {result[f'{kind}_ms_median']:.0f} ms, p95 {result[f'{kind}_ms_p95']:.0f} ms")
    print(f"   words right {right}/{total} ({100 * right / max(total, 1):.1f}%), typos {typo_right}/{len(TYPOS)}; "
          + "; ".join(timing), flush=True)
    return result


def ensure_taptype(dev):
    """Builds tools/test/taptype for the emulator (NDK clang) if needed and pushes it."""
    binary = TAPTYPE_SRC.with_suffix("")
    if not binary.exists() or binary.stat().st_mtime < TAPTYPE_SRC.stat().st_mtime:
        ndk = sorted(Path("/opt/android-sdk/ndk").iterdir())[-1]
        abi = dev.sh("getprop ro.product.cpu.abi").strip()
        target = {"x86_64": "x86_64", "arm64-v8a": "aarch64"}[abi]
        clang = ndk / f"toolchains/llvm/prebuilt/linux-x86_64/bin/{target}-linux-android30-clang"
        subprocess.run([str(clang), "-O2", "-static", "-o", str(binary), str(TAPTYPE_SRC)], check=True)
    subprocess.run([ADB, "-s", dev.serial, "push", str(binary), TAPTYPE], check=True, capture_output=True, timeout=120)
    dev.sh(f"chmod 755 {TAPTYPE}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--keyboards", default="dogboard,gboard,futo,heliboard")
    ap.add_argument("--sentences", type=int, default=40)
    ap.add_argument("--noise-mm", type=float, default=1.2, help="std-dev of tap error")
    ap.add_argument("--noise-y-mm", type=float, default=None, help="vertical std-dev (default: --noise-mm)")
    ap.add_argument("--far-taps", type=float, default=0.0, help="share of taps replaced by a far-off tap from --tail")
    ap.add_argument("--tail", default="", help="far-off tap offsets in key pitches (data/eval/imk-tail.tsv)")
    ap.add_argument("--gap", type=float, default=0.14, help="seconds between taps (after the 40 ms press)")
    ap.add_argument("--keys-cache", default="build/bench_keys.json", help="key positions found by probing")
    ap.add_argument("--recalibrate", action="store_true", help="probe the keys again")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--out", default="build/bench_keyboards.json")
    args = ap.parse_args()
    def is_root():
        return "uid=0" in subprocess.run([ADB, "-s", args.serial, "shell", "id"], capture_output=True, text=True, timeout=60).stdout
    if not is_root() and args.serial.startswith("emulator-"):
        subprocess.run([ADB, "-s", args.serial, "root"], capture_output=True, timeout=60)  # a fresh boot starts unrooted
        time.sleep(3)
    if not is_root():
        sys.exit("needs a root shell (emulator)")
    sentences = []
    for line in TATOEBA.open(encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and p[0].endswith("0") and 4 <= len(p[2].split()) <= 9 and \
                re.fullmatch(r"[A-Za-z',.!? ’]+", p[2]) and not re.search(r"\b(Tom|Mary)\b", p[2]):
            sentences.append(p[2])
    sentences = random.Random(7).sample(sentences, args.sentences)
    args.tail_offsets = [tuple(map(float, l.split("\t"))) for l in open(args.tail, encoding="utf-8")
                         if l.strip() and not l.startswith("#")] if args.tail else None
    dev = Device(args.serial)
    ensure_taptype(dev)
    results = [run(dev, k, KEYBOARDS[k], sentences, args) for k in args.keyboards.split(",")]
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(results, indent=1, ensure_ascii=False))
    print(f"-> {args.out}")


if __name__ == "__main__":
    main()
